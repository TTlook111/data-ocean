# DataOcean 项目优化指导文档

> 生成日期：2026-07-10 | 基于 RAG 系统、数据治理、Agent 链路三大维度深度分析

---

## 1. 项目现状评估

DataOcean 作为企业级 NL2SQL 智能数据查询平台，核心链路已跑通：用户自然语言 → Java 任务管理 → Python Agent 执行 → RAG 检索 → SQL 生成 → sqlglot 校验 → 沙箱执行 → 结果返回。七个重构阶段全部完成，权限治理、RAG 重构、实体图谱、术语表、标签质量、事件驱动均已落地。

但从工程质量角度审视，系统在**检索精度、治理闭环、链路鲁棒性**三个维度存在可量化改进空间：

| 维度 | 当前评级 | 核心瓶颈 |
|------|---------|----------|
| RAG 检索质量 | B | IVF_FLAT 小数据集退化、无混合检索、chunk 间无上下文衔接 |
| 数据治理闭环 | B+ | DATA 级规则未执行、质量结果未持久化、治理状态未与质量问题联动 |
| Agent 链路可靠性 | A- | 降级路径完整但 fallback 丢失关键 chunk 类型、无 Schema Linking 独立节点 |
| 工程可观测性 | C+ | 无 RAG 召回指标、LangSmith 默认关闭、质量趋势表未写入 |

---

## 2. 问题清单与优先级

### 2.1 RAG 与检索问题

#### [P1] IVF_FLAT 索引在小数据集上召回率极低

- **位置**：`python-service/dataocean/rag/milvus_client.py` 第 61-69 行、`vector_store.py` 第 111 行
- **描述**：索引参数 `nlist=128` 将向量空间分为 128 个聚类，但典型 skills.md 仅生成 10-30 个 chunk。128 个聚类中大部分为空，`nprobe=16` 只搜索 16 个聚类，命中率极低。
- **影响**：小数据源的核心表可能检索不到，这是当前 RAG 召回质量最大的隐患。
- **建议**：在 `ensure_collection` 中根据 chunk 数量动态选择索引类型——chunk 数 < 256 时用 `FLAT`（暴力搜索），否则用 `IVF_FLAT`；或降低 `nlist` 到 `min(chunk_count, 16)`。
- **参考**：Haystack 的多后端适配策略。

#### [P1] Reranker JOIN 意图关键词过于通用

- **位置**：`python-service/dataocean/rag/reranker.py` 第 29-33 行
- **描述**：`"和"`, `"与"`, `"包含"`, `"以及"`, `"同时"` 等高频连词被标记为 JOIN 意图关键词。"订单金额和退款金额分别是多少"不涉及 JOIN，却会触发 `has_join=True`，给所有 JOIN_PATH chunk 额外 +0.15 分。
- **影响**：大量非 JOIN 类查询被误判，JOIN_PATH 类 chunk 被不合理提升，挤占真正相关的 METRIC 或 TABLE_DESC chunk。
- **建议**：移除通用词，改为精确模式如 `"跨表"`, `"关联查询"`, `"join"`, `"关联...表"` 的 n-gram 匹配；或用 LLM 做轻量意图分类。
- **参考**：MAC-SQL 的独立 Selector Agent 负责 Schema Linking。

#### [P1] 标题级 chunk 之间无上下文衔接

- **位置**：`python-service/dataocean/rag/chunker.py` 第 27-33 行
- **描述**：`MarkdownHeaderTextSplitter` 按 `##`/`###` 硬切，相邻 chunk 零重叠。当 SQL 需同时参考 JOIN_PATH 和 METRIC 时，两个 chunk 被切到不同段落，向量检索只能命中其中一个。
- **影响**：跨表聚合类问题的召回质量显著下降。
- **建议**：实现"上下文扩展"——命中一个 chunk 时自动带出相邻 chunk（基于 source_id 或文档内顺序）；或在 chunking 阶段为每个 chunk 附加上下文前缀。
- **参考**：Anthropic Contextual Retrieval 的 context-enriched chunking 方案（检索失败率降低 67%）。

#### [P1] 无 RAG 召回质量自动化度量

- **位置**：`python-service/dataocean/rag/service.py`
- **描述**：系统没有任何指标追踪"RAG 召回了多少 chunk、命中了哪些表、最终 SQL 用了哪些表、用户是否满意"。
- **影响**：RAG 是黑盒，无法量化改进效果，也无法发现退化。
- **建议**：在 `retrieve_schemas` 中记录召回数量、chunk_type 分布、top-1 score、过滤后数量；Agent 完成后对比"RAG 召回的表"与"SQL 实际使用的表"计算命中率；写入 `rag_recall_log` 表供 admin dashboard 展示。
- **参考**：LangSmith 的全链路 tracing。

#### [P2] 无混合检索（Hybrid Search）

- **位置**：`python-service/dataocean/rag/vector_store.py`
- **描述**：仅使用向量相似度检索，无 BM25/关键词检索组件。用户直接输入表名 "orders" 或字段名 "pay_amount" 时，向量检索精度不如关键词检索。
- **影响**：精确匹配场景召回偏差。
- **建议**：Milvus 2.4+ 支持 `hybrid_search`，结合 BM25 稀疏向量检索；或在 reranker 层增加精确匹配加分（当前已有表名匹配 +0.2，但仅限 reranker 阶段）。
- **参考**：Haystack 的 BM25 + Dense Embedding + Reciprocal Rank Fusion 管线。

#### [P2] Reranker 加分幅度与 base_score 量级不匹配

- **位置**：`python-service/dataocean/rag/reranker.py` 第 153-176 行
- **描述**：base_score 通常 0.4-0.9，chunk type bonus 叠加后最高可达 0.53，接近 base_score 本身。一个 base_score 0.4 的 chunk 可因各种加分被提升到 0.93，超过 base_score 0.85 但无加分的 chunk。
- **影响**：重排结果可能与向量相似度严重偏离。
- **建议**：将 base_score 和 bonus 分开记录，最终排序使用 `base_score * 0.7 + bonus * 0.3` 加权；或限制 bonus 总和上限为 0.25。

#### [P2] chunk_type 推断依赖粗糙关键词匹配

- **位置**：`python-service/dataocean/rag/chunker.py` 第 179-194 行
- **描述**：先检查 header 关键词，再 fallback 到 body。如果 header 不含关键词但 body 含 "join"，会被标记为 JOIN_PATH，但实际内容可能是 METRIC。
- **影响**：chunk_type 误分类导致 reranker 加分错位。
- **建议**：限制 body 关键词匹配的优先级，仅当 header 未命中任何类型时才用 body 推断；或引入多标签机制。

#### [P2] LangSmith tracing 默认关闭

- **位置**：`python-service/dataocean/infra/config.py` 第 50 行
- **描述**：`langchain_tracing_v2: "false"` 默认关闭，即使开启也仅覆盖 LangChain 调用链，不覆盖自定义 Milvus 检索和 reranker 逻辑。
- **建议**：开发环境默认开启；生产环境提供开关；在 reranker 和 vector_store 关键路径添加 span 标记。

#### [P2] 用户反馈未闭环到 RAG 召回

- **位置**：Java 端 Field Confidence 模块
- **描述**：用户反馈仅更新 Field Confidence 分数，在 reranker 中仅作为 +0.1 加分（阈值 > 80）。反馈信号不直接影响 RAG 的索引、embedding 或检索策略。
- **建议**：低置信度（< 30）的表在 filter 阶段降权或排除；高频被点踩的 chunk 在 reranker 中增加惩罚；记录"用户问了 X，RAG 召回了 Y，用户最终用了 Z"完整链路用于离线评估。
- **参考**：Vanna AI 的 Self-learning 闭环——用户修正的 question-SQL 对自动加入训练集。

---

### 2.2 数据治理问题

#### [P0] DATA 级质量规则定义了但未实际执行

- **位置**：`backend/DataOcean/src/main/java/com/dataocean/module/governance/checker/` 目录
- **描述**：V39 迁移定义了 4 条 DATA 级规则（`DATA_NULL_RATE_HIGH`、`DATA_UNIQUE_VIOLATION`、`DATA_FK_ORPHAN`、`DATA_STALE_TABLE`），包含 `check_expression` 和 `threshold` 字段，但所有 Checker 实现只做 SCHEMA 级检查（检查元数据属性），没有 Checker 实际连接数据库执行 SQL 验证 DATA 级规则。
- **影响**：`metadata_quality_rule` 表中 DATA 类型规则是"死规则"，质量检查结果不反映真实数据质量。
- **建议**：创建 `DataQualityChecker`，连接目标数据源执行 SQL 查询（空值率、唯一性、外键孤儿、数据陈旧），将结果写入问题表。
- **参考**：Great Expectations 的 Expectation Suite 和 Checkpoint 机制。

#### [P0] 质量检查结果时序表未被写入

- **位置**：`QualityCheckServiceImpl.java` 第 172-190 行
- **描述**：V39 迁移创建了 `quality_check_result` 时序表用于质量趋势分析，但 `executeQualityCheck()` 计算完分数后只返回结果对象，从未 INSERT 写入该表。
- **影响**：前端无法展示质量趋势图，质量改进效果无法量化追踪。
- **建议**：在 `executeQualityCheck()` 末尾增加 INSERT 语句写入 `quality_check_result` 表。预估 0.5 天。

#### [P0] 实体同步为破坏式重建，实体 ID 不稳定

- **位置**：`SnapshotEntitySyncListener.java` 第 208-217 行
- **描述**：`cleanupOldEntities()` 在每次快照发布时删除该数据源所有旧实体和关系，然后重新创建，导致实体 ID 每次发布后都变化。
- **影响**：任何对实体 ID 的外部引用（书签、通知关联、审计日志）在快照发布后失效。
- **建议**：改为增量更新——先比对 FQN，只更新变化的实体，保持实体 ID 稳定。
- **参考**：LightRAG 的增量向量化策略。

#### [P0] 质量问题与治理状态未联动

- **位置**：`QualityCheckServiceImpl.java`、`GovernanceStatusServiceImpl.java`
- **描述**：质量检查发现 HIGH 严重度问题时，不会自动影响表/列的治理状态。一张有大量 HIGH 级质量问题的表仍可保持 `NORMAL` 状态，甚至可被发布到 RAG 供查询使用。
- **影响**：治理状态不能真实反映数据质量，降低了治理状态的可信度。
- **建议**：HIGH 级问题自动将治理状态设为 `RECOMMENDED`（待审核），管理员确认后才改为 `BLOCKED`/`DEPRECATED`。
- **参考**：OpenMetadata 的 Data Quality SLA 概念。

#### [P1] `AccessPolicyServiceImpl.logChange()` ObjectMapper 重复创建

- **位置**：`AccessPolicyServiceImpl.java` 第 382-383 行
- **描述**：每次记录审计日志时通过反射创建新 ObjectMapper 实例，策略 CRUD 是高频操作，有不必要的性能开销。
- **建议**：注入共享 ObjectMapper 替换反射创建。预估 0.5 天。

#### [P1] `AccessPolicyCreateDTO` 缺少 `priority` 字段

- **位置**：`AccessPolicyCreateDTO.java`
- **描述**：实体有 `priority` 字段（越低越优先：系统级 0-99，管理员 100-199，默认 200+），但创建 DTO 中没有暴露该字段。
- **影响**：管理员无法精细控制策略优先级。
- **建议**：DTO 增加 `priority` 字段，前端策略创建表单增加优先级选择器。

#### [P1] 质量检查无自动触发机制

- **位置**：`MetadataGovernanceController.java` 第 48-56 行
- **描述**：质量检查仅通过手动 API 触发，无定时任务、快照发布后自动触发、或数据同步后自动触发的机制。
- **建议**：快照发布后自动触发全量质量检查，或添加 Spring `@Scheduled` 定时任务。

#### [P1] DENY 策略时间计划解析失败时默认放行

- **位置**：`PermissionCalculatorImpl.java` 第 380-384 行
- **描述**：`isPolicyActiveByTime()` 中时间计划 JSON 解析失败时默认放行（`return true`）。如果管理员配置了 DENY 策略并设置了时间计划但 JSON 格式有误，该策略会被跳过。
- **影响**：可能导致意外数据暴露。
- **建议**：对 DENY 类策略，解析失败应默认生效（deny-by-default）。

#### [P2] 标签推断在 Java 和 Python 中有重复实现

- **位置**：Java `SnapshotEntitySyncListener.java` 第 241-280 行、Python `auto_tagger.py`
- **描述**：两者的模式列表和匹配逻辑不完全一致（Java 用 `contains` + 少量 regex，Python 用完整 regex）。
- **建议**：移除 Java 端重复实现，改为调用 Python auto_tagger API 或共享规则配置。

#### [P2] 质量检查维度权重硬编码

- **位置**：`QualityCheckServiceImpl.java` 第 83-89 行
- **描述**：维度权重（完整性 30%、准确性 25%、一致性 25%、时效性 10%、可追溯性 10%）硬编码在代码中。
- **建议**：存储到 `sys_config` 表，支持管理员按业务场景调整。

---

### 2.3 Agent 链路问题

#### [P1] fallback_chunks 降级丢失关键 chunk 类型

- **位置**：`python-service/dataocean/rag/fallback.py` 第 52 行
- **描述**：降级时只保留 `TABLE_DESC/CORE_TABLE/SCHEMA` 类型，丢失了 `JOIN_PATH`、`METRIC`、`FIELD_NOTE`、`QUERY_SCENE` 等对 SQL 生成质量至关重要的 chunk 类型。
- **影响**：降级后 SQL 生成质量显著下降。
- **建议**：将 chunk_type 过滤条件扩展为包含所有类型。这是最低成本最高收益的 Agent 改进。

#### [P1] 多表查询无限定符列的拒绝检查过于保守

- **位置**：`python-service/dataocean/sandbox/rewriter.py` 第 163-166 行
- **描述**：多表查询中无限定符的列名如果在任一表的拒绝列表中就被拒绝。`SELECT name FROM t1 JOIN t2`，如果 `t2.name` 被拒绝但 `t1.name` 未被拒绝，查询仍会被拒绝。
- **建议**：增加精确匹配路径——列名只在一个表的拒绝列表中出现时精确拒绝，多表同时出现时才保守拒绝。

#### [P2] 术语匹配为子串匹配，短术语易误匹配

- **位置**：`python-service/dataocean/agent/nodes/query_rewriter.py` 第 167 行
- **描述**：`name in question_lower` 做子串包含而非词边界匹配，"订单"会匹配"订单号"、"订单量"、"下订单"。短术语误匹配概率更高。
- **建议**：使用 `\b` 正则词边界或要求匹配子串前后为非中文字符。

#### [P2] 相似度阈值过滤为空时无降级/重试策略

- **位置**：`python-service/dataocean/rag/service.py` 第 49 行
- **描述**：阈值过滤后 `filtered` 为空直接返回"未找到相关数据表"，没有尝试降低阈值重试或扩大 top_k。
- **建议**：阈值过滤为空时，将阈值降低 0.1 重试一次，或扩大 top_k 到 20。

#### [P2] 连接池无定时清理/驱逐机制

- **位置**：`python-service/dataocean/sandbox/pool_manager.py`
- **描述**：`_pools` 是进程级字典，只在 `cleanup_idle_pools` 被调用时清理。长期运行的进程会积累连接池。
- **建议**：添加后台定时任务每 5 分钟调用 `cleanup_idle_pools()`。

#### [P2] 分号注入检测可能误杀字符串常量

- **位置**：`python-service/dataocean/sandbox/validator.py` 第 93 行
- **描述**：字符串常量中的分号（如 `WHERE name = 'a;b'`）会被 `_check_injection_patterns` 误判为多语句。
- **建议**：先用 sqlglot 解析，如果解析成功则跳过正则分号检测。

#### [P3] 追问建议 LLM 调用无超时保护

- **位置**：`python-service/dataocean/agent/nodes/data_visualizer.py` 第 94 行
- **建议**：`_generate_suggestions` 外层包裹 `asyncio.wait_for(timeout=10)`。

#### [P3] 密码解密失败静默回退到密文

- **位置**：`python-service/dataocean/sandbox/pool_manager.py` 第 171 行
- **描述**：`_decrypt_password` 解密失败时返回原始 encrypted 值作为密码，导致 MySQL 认证失败，错误信息不明确。
- **建议**：解密失败时抛出明确异常。

---

### 2.4 工程化问题

#### [P2] 无 A/B 测试框架

- **描述**：prompt 模板迭代无法量化对比效果，改了模板不知道是否真的提升了 SQL 准确率。
- **建议**：在 query audit 中记录 prompt_version，按版本聚合成功率和用户满意度。

#### [P2] similarity_threshold 固定为 0.4

- **位置**：`python-service/dataocean/infra/config.py` 第 40 行、`service.py` 第 48 行
- **描述**：所有查询统一使用 0.4 阈值，简单查询可能召回过多噪音，复杂查询可能过滤掉相关 chunk。
- **建议**：根据查询意图复杂度动态调整阈值。简单查询提高到 0.5-0.6，复杂查询降低到 0.3-0.35。

#### [P3] TABLE_DESC 类型仅在 fallback 路径生成

- **位置**：`python-service/dataocean/rag/chunker.py` 第 56-90 行
- **描述**：主检索路径只从 skills.md 生成 chunk，TABLE_DESC 仅由 `chunk_tables` 为降级方案生成。
- **建议**：主路径中也补充 TABLE_DESC chunk 作为基线召回保障。

#### [P3] 无向量检索缓存层

- **位置**：`python-service/dataocean/rag/vector_store.py`
- **描述**：每次查询直接访问 Milvus，同一数据源的重复查询（同一会话多次追问）每次都走 Milvus。
- **建议**：增加基于 `(embedding_hash, datasource_id, snapshot_id)` 的 LRU 缓存，TTL 5-10 分钟。

---

## 3. 优化路线图

### 3.1 短期优化（1-2 周）

**目标**：以最小投入修复最高影响问题，快速提升 RAG 召回质量和治理闭环。

| 序号 | 任务 | 预估工时 | 涉及文件 |
|------|------|----------|----------|
| S1 | 修正 reranker `_JOIN_KEYWORDS`，移除通用词 | 1-2h | `rag/reranker.py` |
| S2 | 动态索引选择：chunk < 256 用 FLAT，否则 IVF_FLAT | 1h | `rag/milvus_client.py` |
| S3 | 扩展 fallback_chunks 保留所有 chunk 类型 | 0.5h | `rag/fallback.py` |
| S4 | `executeQualityCheck()` 写入 `quality_check_result` 表 | 0.5h | `QualityCheckServiceImpl.java` |
| S5 | `AccessPolicyServiceImpl` 注入共享 ObjectMapper | 0.5h | `AccessPolicyServiceImpl.java` |
| S6 | DENY 策略时间计划解析失败时 deny-by-default | 0.5h | `PermissionCalculatorImpl.java` |
| S7 | 追问建议 LLM 调用加超时保护 | 0.5h | `data_visualizer.py` |

### 3.2 中期优化（1-2 月）

**目标**：建立可观测性基础，实现治理闭环，提升 Agent 链路鲁棒性。

| 序号 | 任务 | 预估工时 | 涉及模块 |
|------|------|----------|----------|
| M1 | RAG 召回指标日志（召回数量、chunk_type 分布、top-1 score） | 4-8h | rag, query |
| M2 | Reranker bonus 上限 0.25 或加权混合排序 | 4h | rag/reranker.py |
| M3 | 术语匹配改为词边界匹配 | 2h | query_rewriter.py |
| M4 | 相似度阈值过滤为空时降级重试 | 2h | rag/service.py |
| M5 | 连接池定时清理任务 | 2h | sandbox/pool_manager.py |
| M6 | 多表列拒绝检查增加精确匹配路径 | 4h | sandbox/rewriter.py |
| M7 | DATA 级质量规则执行器实现 | 3-5 天 | governance, datasource |
| M8 | 质量问题与治理状态自动联动 | 1-2 天 | governance, metadata |
| M9 | 快照发布后自动触发质量检查 | 1-2 天 | governance, system |
| M10 | chunk 上下文扩展（命中一个 chunk 时带出相邻 chunk） | 1-2 天 | rag/retriever |
| M11 | `AccessPolicyCreateDTO` 增加 priority 字段 | 1 天 | permission, frontend |

### 3.3 长期演进（3-6 月）

**目标**：架构级能力提升，向业界最佳实践看齐。

| 序号 | 任务 | 预估工时 | 参考项目 |
|------|------|----------|----------|
| L1 | 混合检索：BM25 稀疏向量 + Dense 向量 + RRF 融合 | 2-3 周 | Haystack, RAGFlow |
| L2 | Context-enriched chunking：每个 chunk 附加 LLM 生成的上下文摘要 | 1-2 周 | Anthropic Contextual Retrieval |
| L3 | 独立 Schema Linking 节点：RAG 检索后、SQL 生成前增加 schema pruning | 1-2 周 | MAC-SQL, CHESS |
| L4 | SQL 自修复循环：执行 → 结果检查 → 诊断 → 修正的迭代 | 2-3 周 | MAC-SQL, ReFoRCE |
| L5 | Multi-candidate SQL 生成：2-3 种 prompt 策略生成候选 + 执行引导选择 | 2-3 周 | XiYan-SQL, SQLFixAgent |
| L6 | Few-shot example 自动检索：历史成功 SQL 入库 + embedding 检索 | 1-2 周 | Vanna AI, DAIL-SQL |
| L7 | 实体同步改为增量更新，保持实体 ID 稳定 | 2-3 天 | LightRAG |
| L8 | 实体关系图谱辅助 RAG 检索扩展 | 1-2 周 | LightRAG, GraphRAG |
| L9 | Self-learning 闭环：用户确认正确的 SQL 自动归入 few-shot 检索库 | 2-3 周 | Vanna AI |
| L10 | Agent Checkpointing：查询任务断点恢复 | 1-2 周 | LangGraph |

---

## 4. 开源最佳实践借鉴

### 4.1 NL2SQL 优化建议

**Vanna AI 的 Self-learning 闭环**：用户修正的 question-SQL 对自动加入训练集，形成正反馈循环。DataOcean 的 query feedback 模块已有点赞/点踩，可进一步将确认正确的 SQL 自动归入 few-shot 检索库，在 RAG 检索阶段增加对历史成功 SQL 的检索权重。

**MAC-SQL 的多 Agent 协作**：Decomposer + Selector + Refiner 三角色分工。Selector Agent 专门负责 Schema Linking，过滤无关表/列。DataOcean 可在 SQL 生成前增加独立的 Schema Linking 节点，在 SQL 生成后增加 Refiner 节点（执行 → 检查结果合理性 → 必要时修正）。

**XiYan-SQL 的 Multi-candidate 架构**：没有单一生成器在所有查询类型上都最优。对同一问题用 2-3 种不同 prompt 策略生成候选 SQL，利用 sqlglot 校验 + 沙箱执行结果作为候选排序信号。

### 4.2 RAG 优化建议

**Anthropic Contextual Retrieval**：在每个 chunk 前附加由 LLM 生成的上下文摘要，将检索失败率降低约 67%。DataOcean 可在 skills.md chunking 阶段为每个 chunk 生成上下文前缀（如"这是关于 sales_order 表与 customer 表的 JOIN 路径定义"），embedding 时包含上下文前缀。

**Haystack 的 Hybrid Search**：`BM25 Retriever + Embedding Retriever → Document Joiner (RRF) → Reranker → LLM`。DataOcean 当前仅使用 Milvus 稠密检索，应增加 BM25 稀疏检索实现 RRF 融合。

**RAGFlow 的 Template-based chunking**：不同文档类型使用不同 chunk 策略。DataOcean 的 skills.md 可为 JOIN_PATH、METRIC、FIELD_NOTE、QUERY_SCENE 设计专用 chunk 大小和 overlap 策略。

### 4.3 数据治理优化建议

**OpenMetadata 的 Data Quality SLA**：基于指标的治理，设定质量阈值并监控达标率。DataOcean 已有 quality_check_result 表结构，可借鉴 SLA 概念在 Admin Dashboard 增加质量趋势和治理健康度指标。

**Great Expectations 的 Expectation Suite**：将数据质量规则组织为套件，可批量执行。DataOcean 的 `metadata_quality_rule` + `check_expression` 已具备基础，可进一步实现套件化管理和自动执行。

**DataHub 的事件驱动元数据变更**：元数据变更通过事件传播，支持实时响应。DataOcean 已有 `metadata_change_event` 表，可进一步将事件驱动扩展到更多场景。

### 4.4 Agent 框架优化建议

**LangGraph 的 Checkpointing**：每个 Agent 节点执行后持久化状态快照，支持暂停/恢复/重放。DataOcean 的 Python Agent 已基于 LangGraph，可利用 Checkpointing 实现查询任务的断点恢复。

**CHESS 的 Schema Pruning**：先通过 LLM 过滤无关表/列，减少 context 噪声。在 RAG 检索后、SQL 生成前增加 schema pruning 步骤，利用 Milvus 中的字段值 embedding 进行 value-aware linking。

**ReFoRCE 的 Execution-driven refinement**：生成 → 执行 → 结果检查 → 修正的闭环。在 Agent 的 SQL 生成节点后增加 refinement 循环（最大 2-3 次迭代），利用 conversation_history 中的成功查询作为 few-shot examples。

---

## 5. 实施建议

### 5.1 执行原则

1. **先修后建**：短期修复现有 bug 和配置问题（S1-S7），再建设新能力（M1-M11），最后架构演进（L1-L10）。
2. **度量先行**：M1（RAG 召回指标）应最先完成，为后续所有优化提供量化基线。没有度量就无法证明改进有效。
3. **单点突破**：不要同时推进多个 P0 任务。建议顺序：S3（fallback 扩展）→ S1（reranker 修正）→ S2（动态索引）→ M1（指标）→ M7（DATA 规则执行）。
4. **回归保护**：每个改动配套测试用例，特别是 RAG 和 Agent 链路的改动。当前 Python 139 个测试、Java 95 个测试是基线，改动后必须不低于此数。

### 5.2 风险控制

| 风险 | 缓解措施 |
|------|----------|
| Reranker 调整导致排序退化 | 在 M1 完成前，先用离线数据集评估当前 baseline，调整后对比 |
| 动态索引选择引入新 bug | 只在 `ensure_collection` 中增加分支，不改现有 IVF_FLAT 路径 |
| DATA 级规则执行增加数据库负载 | 限制为只读查询，设置超时，仅在非高峰时段执行 |
| 混合检索增加系统复杂度 | 分阶段实施：先在 reranker 层增加精确匹配加分，再引入 BM25 |

### 5.3 开发环境准备

```bash
# 运行 Python 测试
cd python-service && uv run pytest

# 运行 Java 测试
cd backend/DataOcean && mvn test

# 启动开发环境
docker compose up -d
cd frontend && npm run dev
cd backend/DataOcean && mvn spring-boot:run
cd python-service && uv run uvicorn dataocean.main:app --reload --port 8000
```

---

## 6. 附录

### 6.1 问题统计

| 优先级 | RAG | 治理 | Agent | 工程化 | 合计 |
|--------|-----|------|-------|--------|------|
| P0 | 0 | 4 | 0 | 0 | 4 |
| P1 | 4 | 4 | 2 | 0 | 10 |
| P2 | 4 | 2 | 4 | 3 | 13 |
| P3 | 0 | 0 | 3 | 2 | 5 |
| **合计** | **8** | **10** | **9** | **5** | **32** |

### 6.2 关键参考链接

**NL2SQL**：
- [Vanna AI](https://github.com/vanna-ai/vanna) - RAG-based text-to-SQL，Self-learning 闭环
- [MAC-SQL](https://github.com/wbbeyourself/MAC-SQL) - Multi-agent text-to-SQL（ACL 2024）
- [XiYan-SQL](https://arxiv.org/abs/2411.08599) - Multi-generator architecture（阿里巴巴，2024）
- [DAIL-SQL](https://github.com/BeihangUniversity/DAIL-SQL) - Prompt engineering for text-to-SQL（2024）
- [CHESS](https://arxiv.org/abs/2405.16755) - Contextual Harnessing for Efficient SQL Synthesis（2024）

**RAG**：
- [RAGFlow](https://github.com/infiniflow/ragflow) - Deep document understanding RAG engine
- [Haystack](https://github.com/deepset-ai/haystack) - End-to-end RAG pipeline with Hybrid Search
- [LightRAG](https://github.com/HKUDS/LightRAG) - Graph-based lightweight RAG
- [Anthropic Contextual Retrieval](https://www.anthropic.com/news/contextual-retrieval) - Context-enriched chunking

**数据治理**：
- [OpenMetadata](https://github.com/open-metadata/OpenMetadata) - Unified metadata platform
- [DataHub](https://github.com/datahub-project/datahub) - LinkedIn metadata platform
- [Great Expectations](https://github.com/great-expectations/great_expectations) - Data quality framework

**Agent 框架**：
- [LangGraph](https://github.com/langchain-ai/langgraph) - Stateful agent workflow with Checkpointing
- [ReFoRCE](https://arxiv.org/abs/2410.16944) - Self-Refinement text-to-SQL（2024）
- [SQLFixAgent](https://arxiv.org/abs/2410.14564) - Consistency-Enhanced Multi-Agent SQL Fix（2024）

### 6.3 投入产出比排序

按"预期收益 / 预估工时"排序的 Top 10 改进项：

| 排名 | 任务 | 工时 | 预期收益 |
|------|------|------|----------|
| 1 | S3: 扩展 fallback_chunks 保留所有 chunk 类型 | 0.5h | 降级场景 SQL 质量显著提升 |
| 2 | S1: 修正 reranker JOIN 关键词 | 1-2h | 消除大量非 JOIN 查询的排序偏差 |
| 3 | S2: 动态索引选择 | 1h | 小数据源召回率从极低恢复正常 |
| 4 | S4: 质量结果写入时序表 | 0.5h | 解锁质量趋势分析功能 |
| 5 | S6: DENY 策略解析失败 deny-by-default | 0.5h | 修复潜在安全漏洞 |
| 6 | M1: RAG 召回指标日志 | 4-8h | 为所有后续优化提供量化基线 |
| 7 | M2: Reranker bonus 上限 | 4h | 防止重排过度干预向量排序 |
| 8 | M10: chunk 上下文扩展 | 1-2 天 | 解决跨节语义断裂，提升复杂查询召回 |
| 9 | M7: DATA 级质量规则执行 | 3-5 天 | 治理结果反映真实数据质量 |
| 10 | L1: 混合检索 BM25 + Dense | 2-3 周 | 精确匹配场景召回率大幅提升 |
