# CLAUDE.md

This file gives AI coding agents project-specific guidance for working in this repository.

## Project Overview

DataOcean is an enterprise NL2SQL intelligent data query and governance platform. It lets business users ask questions in natural language, generates safe SQL, executes read-only queries, and returns table/chart results. The core design is metadata-governance-driven trustworthy querying.

MVP scope: multi-data-source management, with each query selecting one MySQL data source and supporting multi-table joins inside that database.

## Architecture

```text
Vue 3 frontend
  - query app and admin governance app
  - Axios, Element Plus, ECharts, GSAP
        |
        | HTTP API
        v
Spring Boot Java gateway
  - auth, permissions, datasource management
  - metadata governance, skills.md lifecycle
  - audit, masking, task state, versioning
        |
        | internal HTTP (RestClient)
        v
Python FastAPI AI service
  - query rewrite, Schema RAG, SQL generation
  - SQL AST validation and sandbox execution
  - chart generation, chunking, embedding, reranking
        |
        v
Milvus / MySQL / Redis / Qwen
```

技术版本、技术与模块的对应关系、数据存储归属和异步边界详见
[`docs/development/DataOcean技术栈与模块职责.md`](docs/development/DataOcean技术栈与模块职责.md)。

Important boundary:

- Java owns management lifecycle: document drafts, review, versioning, publishing, task state, permissions, audit, and Java-side persistence.
- Python owns AI/RAG execution: chunking, embedding, Milvus writes, retrieval, reranking, SQL generation, SQL validation, and sandbox execution.
- Frontend calls Java only. Java calls Python through internal APIs.
- Java→Python calls use `RestClient` with `@Retryable` on knowledge/RAG client methods (2 attempts, 1s backoff). SSE streaming and health checks do not retry.
- Java asynchronous work uses dedicated executors for query execution, conversation summaries, and datasource health checks; saturation must not make request threads run the full Agent or summary LLM call.

## Current Status

Last updated: 2026-09-12.

The main end-to-end chain is implemented:

```text
Java query task -> Python Agent -> RAG retrieval -> SQL generation
-> sqlglot AST validation/rewrite -> sandbox execution -> Java persistence -> frontend rendering
```

Module status summary:

| Area | Status |
| --- | --- |
| Frontend query app | Core complete |
| Frontend admin governance app | Core complete |
| Java user/auth/permission modules | Complete; policy priority/time conditions and access approval are implemented |
| Java datasource/metadata/governance/versioning modules | Complete; metadata entity graph and event recording are implemented |
| Java glossary module | Complete; glossary and term approval flow are implemented |
| Java knowledge/skills.md lifecycle | Complete, with Python chunking integration |
| Java query/audit/field confidence modules | Core complete; conversation persistence and feedback confidence updates are implemented |
| Java prompt module | Complete, including template approval workflow and version rollback |
| Java system/dashboard modules | Complete; AI config management and admin dashboard are implemented |
| Python Agent workflow | Core complete, with Schema Linking, self-learning few-shot, Redis-backed cache/enrichment, timeout/cancel handling and degraded result propagation |
| Python RAG/vectorization | Core complete, with token-aware context-enriched chunking, 900/1000-token budget, 150-token overlap, chunk metadata propagation, snapshot-safe fallback, and verified Milvus rebuild semantics |
| Python SQL sandbox | Core complete, with precise multi-table column rejection |
| Python chart generation | Complete |

Known follow-up areas — see `docs/development/后续开发.md` for the full prioritized list.

Latest addition:

- **后端缺陷修复轮完成**（2026-09-12，提交 `fcd7bd3`/`af29e6b`/`cbd1ff4` + 前端 `0c852a7`）：按 `docs/review/2026-09-11-后台前端重构审查-后端事实问题.md` 修复全部记录项。这是一次**独立于前端重构轮的修复**——`开发指导` §1 的「不修改 Java」约束只适用于前端重构轮。要点：(1) **P0** `rollback` 原本不校验文档状态，任何调用方都能把未审核内容写进 Milvus；现要求文档为 PUBLISHED 且目标版本 APPROVED；(2) `knowledge_doc_version.review_status` 由死列改为 `approve`/`reject` 真实写入，V51 迁移回填历史行（能按 `knowledge_review_task` 还原的还原，其余标为 `UNKNOWN`）；(3) 术语状态机收敛采用方案 A：新增 `APPROVED → DRAFT` 退回接口，`updateTerm` 加状态校验并改字段白名单赋值；(4) 4 个新增接口见下方模块说明；(5) `/catalog/search` 的 `datasourceId` 过滤下推到 SQL。**修复中额外发现**：`LineageServiceImpl.buildColumnFqn` 与 `findFqnPrefix` 持有 `datasourceId` 却未下传，多数据源同名表会解析到错误数据源的 FQN，产生错误列血缘——已修。**验证边界**：全部为静态验证 + 单元测试；V51 迁移未在真实 MySQL 执行过，前端改动无运行时点验。

- **后台前端缺陷清单 F1–F9 全部修复**（2026-09-12，分支 `fix/frontend-defect-list` 提交 `22a1be5`）：按 `docs/review/2026-09-11-后台前端重构审查-前端缺陷清单.md` 修复 9 项，纯前端，8 个源文件。要点：(1) F1 数据源驾驶舱主操作改为消费后端 `blockReasons[0]`，不再在前端重推就绪状态，并按后端 `appendBlockReasons` 的顺序分流；(2) F4 二级工作区高亮从路径前缀匹配改为 `route.meta.workspaceKey` 等值判定，修复 5 条双高亮 + 2 条零高亮（`/admin/metadata/tables`、`/admin/permission/policies`）；(3) F6 一级与二级导航统一按目标 `contextMode` 经 `utils/adminNavigation.ts` 的 `buildContextQuery` 继承上下文，使 URL 自描述；(4) F8 `resolveReadinessActionPath` 的 `known` 字段改为被消费，未映射路径不再静默跳工作台。实施中发现 3 个清单未记录的问题：`v-if="primaryAction.icon"` 会让导航型主操作整体消失、`SNAPSHOT_NOT_PUBLISHED` 照搬后端文案会违反门禁表、治理导航用 `latestSnapshot` 会过滤到 0 条问题。**运行时未验证**（无可用环境）。

- **Phase 1 代码可信度全部完成**（2026-08-21）：(1) DataQualityChecker SQL 标识符转义防注入（4 个方法全部加 `escapeIdentifier()`）；(2) DataQualityChecker 密码解密统一复用 `DatasourceSecretService`，消除密钥不一致风险（删除自行实现的 AES 解密）；(3) MetadataCatalogController 3 处 `catch(Exception ignored){}` 改为 `log.warn`；(4) `traceDerivedFromChain` 增加 `visited` 集合防止循环血缘无限递归；(5) P5 Java 侧会话记忆方案已升级为数据库长期摘要 + 请求级上下文组装；(6) P0 管理员反馈特权确认已实现（ADMIN/ANALYST 跳过审核、delta=-45）。

- **深度优化方案 Phase 0-3 全部完成**（2026-07-24）：基于 `docs/development/completed/DataOcean深度优化参考方案.md` 的 18 项优化全部实施。详见下方「近期完成」中各 Phase 条目。

Recently completed or verified:

- **P6 操作日志覆盖补全已完成**（2026-08-14）：(1) 13 个管理端 Controller 补 `@AdminAuditLog`（治理/快照发布/术语审核/skills/告警/审批/AI配置/调度/角色权限部门）；(2) `AdminAuditLog` 新增 `logReads` 属性，读密集型 Controller（catalog/collection）只记录写操作，避免搜索/轮询刷屏；(3) `OperationLogAspect` 提取 `targetId` 定位具体记录，并移除 OperationLogController 自引用日志；(4) 便捷查询：新增 `OperationLogQueryDTO` + `listLogs()` 多条件动态查询（操作人/类型/状态/时间/IP/路径/目标资源/目标ID/关键词），前端 `OperationLogList.vue` 筛选栏 + `operation-log.ts` 查询接口扩展。前端 build 和 Java 单测已在 2026-08-31 全量验证通过。
- **RAG 文档与切分修复完成基础实现**（2026-08-31）：Python 负责 token-aware chunking（目标 900、最大 1000、overlap 150）、多表多字段和上下文 metadata；Milvus 检索、fallback、相邻 chunk 扩展及 Java 发布清理状态已完成。V50 迁移、旧 Collection 重建、真实外部 Embedding/Milvus 验证、golden questions 评测和 `/internal/rag/re-vectorize` 正式实现仍属于部署/评测阶段待办，详见 RAG 优化方案。
- **Phase 0-3 深度优化已完成**（2026-07-24）：按《DataOcean深度优化参考方案》实施 18 项优化，分 4 个 Phase、12 次 commit：
  - **Phase 0 前置**（3 项）：打通列信息数据通道（`state.py` `RetrievedSchema.columns` + `schema_retriever.py` 传递 `ColumnInfo`）；权限计算 Redis 去重（`perm:{taskId}` TTL=60s）；`graph.py` `START` 导入。
  - **Phase 1 低悬果实**（6 项，零额外 LLM 调用）：Embedding 缓存（Redis TTL=1h）；术语表 Redis 缓存 + N+1 批量查询修复；Fallback Chunks Redis 缓存；Schema Linking 阈值 3→8（基于 Death of Schema Linking 论文）；SQL-to-Schema 幻觉检测（sqlglot `_extract_tables` 复用）；治理-置信度联动（V44 `metadata_quality_issue.column_meta_id` + `QualityIssueServiceImpl.handleIssue()` 联动 `ConfidenceCalculator.adjustScore()`）。
  - **Phase 2 核心优化**（7 项，最多 1 次额外 LLM 调用）：列级 Schema Linking（扩展 prompt 返回 `relevant_columns` + 列裁剪）；置信度读时衰减（`calculateWithDecay()` 指数衰减，半衰期 30 天可配）；置信度 Schema Linking 加权（`_build_schema_summary()` 标注 H/M/L 等级）；Few-shot embedding 升级（余弦相似度替代字符重叠）；执行反馈 LLM 自校正（仅 syntax/table/column 错误）；列元数据采样值增强（V45 `db_column_meta.sample_values` + `ColumnCollector` `SELECT DISTINCT LIMIT 5`）；数据源密码 Redis 缓存（TTL=5min）。
  - **Phase 3 架构增强**（5 项）：Agent 图并行化（`metadata_prefetch_node` + fan-out `START` 边）；元数据驱动 Schema Linking（传递 `table_comment`/`source_type`）；自动标签增强（`detect_pii_from_samples` 基于采样值 PII 检测）；质量评分聚合（新建 `QualityScoreAggregationService`）；大结果集分块传输（>200 行时 SSE `RESULT_CHUNK` 分块）。

- **阶段一：权限治理修复已完成**（2026-06-14）：按统一路线图完成权限治理修复，包括：(1) 权限合并逻辑从交集改为并集（安全优先：任一维度 DENY 即禁止，任一维度 MASK 即脱敏）；(2) 权限计算器批量查询优化（消除 N+1）；(3) 缓存事务隔离（@TransactionalEventListener AFTER_COMMIT）；(4) 治理 Issue 状态机新增 REOPENED 状态（RESOLVED/REJECTED → REOPENED → CONFIRMED）；(5) SQL 注入防御已确认存在（AccessPolicyServiceImpl.validateRowFilterExpression）；(6) Java→Python 权限协议补齐 tableScopeMode（UNRESTRICTED/ALLOWLIST），修正 `*` 表策略语义；(7) 冗余 Mapper 删除任务取消（DatasourceMapper 实际被 16 个类使用）。详见 `docs/development/completed/DataOcean统一执行路线图.md`。
- **阶段二：RAG 重构已完成**（2026-06-14）：(1) Embedding 初始化竞态修复 — asyncio.Lock + double-check 模式（embeddings.py）；(2) LLM 初始化竞态修复 — threading.Lock + double-check 模式（llm.py）；(3) 向量化 force 模式 staging 语义明确化（vectorizer.py），修复 _count_vectors limit=1000 上限 bug；(4) SSE 事件流添加 try/finally 清理保证（sse.py）；(5) RAG 架构已确认分层清晰（service → retriever → vector_store / vectorizer / reranker），chunk type 权重已在 reranker 中实现。详见 `docs/development/completed/DataOcean统一执行路线图.md`。
- **阶段三：实体关系图谱已完成**（2026-06-14）：(1) 创建 metadata_entity 和 metadata_relationship 表（V37 迁移）；(2) 实现 MetadataEntity/MetadataRelationship 实体、Mapper、Service；(3) 实现 FQN 体系（datasource.db.table.column）；(4) 快照发布时自动同步实体-关系图谱（SnapshotEntitySyncListener）；(5) 实现元数据目录搜索 API（/api/admin/catalog/search）；(6) 实现血缘类型设计（QUERY/ETL/MANUAL）；(7) 实现血缘 DAG 可视化前端（LineageGraph.vue + ECharts）；(8) 实现下游影响分析 API。详见 `docs/development/completed/DataOcean统一执行路线图.md`。
- **阶段四：业务术语表已完成**（2026-06-14）：(1) 创建 glossary 和 glossary_term 表（V38 迁移）；(2) 实现 Glossary/GlossaryTerm 实体、Mapper、Service、Controller（/api/admin/glossary）；(3) 实现术语审核流程（DRAFT → PENDING_REVIEW → APPROVED/REJECTED）；(4) 实现 RAG 集成——查询改写阶段自动匹配术语同义词扩展用户问题；(5) 实现前端术语管理页面（GlossaryList.vue）；(6) Java→Python 请求传递 glossary_terms。详见 `docs/development/completed/DataOcean统一执行路线图.md`。
- **阶段五：分类标签与质量深化已完成**（2026-06-14）：(1) 创建 classification 和 tag 表（V39 迁移），预置 PII/数据分级/业务域三类 14 个标签；(2) 实现 Python AutoTagger 标签自动推断器（基于列名模式匹配 PII/业务域标签）；(3) 扩展 metadata_quality_rule 表新增 check_type/check_expression/threshold 字段；(4) 新增 4 条数据级质量规则（空值率、唯一性、外键孤儿、数据陈旧）；(5) 创建 quality_check_result 质量趋势时序表；(6) 标记 PredefinedTag 为 @Deprecated。详见 `docs/development/completed/DataOcean统一执行路线图.md`。
- **阶段六：权限增强已完成**（2026-06-14）：(1) 为 datasource_access_policy 表添加 priority（策略优先级）、valid_from/valid_until/time_schedule（时间条件）字段（V40 迁移）；(2) 实现基于优先级的策略评估（优先级越低越优先，高优先级 DENY 短路）；(3) 实现时间条件过滤（绝对时间范围 + 周期性时间计划）；(4) 创建 permission_change_log 权限变更审计表；(5) 在策略 CRUD 操作中集成审计日志记录。详见 `docs/development/completed/DataOcean统一执行路线图.md`。
- **阶段七：事件驱动已完成**（2026-06-14）：(1) 创建 metadata_change_event 表（V41 迁移），记录元数据实体变更历史；(2) 实现 MetadataChangeEventService 事件记录服务；(3) 集成到 SnapshotEntitySyncListener，快照发布时自动记录变更事件；(4) 创建 access_approval_request 表（V41 迁移），支持数据访问审批流程；(5) 实现 AccessApprovalService 审批服务（提交→审批→生成临时 ALLOW 策略→过期自动清理）；(6) 实现 AccessApprovalController 审批 API；(7) 安全约束：BLOCKED/DEPRECATED 表列不允许申请访问，临时策略有有效期和审计记录。详见 `docs/development/completed/DataOcean统一执行路线图.md`。
- **七个重构阶段全部完成**（2026-06-14）：统一路线图的七个重构阶段（权限治理修复、RAG 重构、实体关系图谱、业务术语表、分类标签与质量深化、权限增强、事件驱动）已全部完成并通过测试。后续新增功能另见 `docs/development/后续开发.md`。
- **P1 通知系统完善完成**（2026-06-21）：新增前端通知铃铛、未读角标、通知下拉和 `frontend/src/api/notification.ts`；字段群体阈值、快照发布/过期事件已接入系统通知，管理员和相关操作人会收到定向通知。
- **P2 操作日志前端接入完成**（2026-06-21）：新增 `frontend/src/api/admin/operation-log.ts`、`OperationLogList.vue`、`/admin/system/operation-logs` 路由和系统设置二级工作区入口，复用后端 `OperationLogController`，权限沿用 `audit:view`。
- **数据源授权语义补齐**：`V42__datasource_access_effect.sql` 已加入，使数据源授权的 allow/deny 决策显式化。
- **智能问数链路已跑通**（2026-06-13）：完整链路测试成功，包括 RAG 检索、SQL 生成、SQL 校验、SQL 执行、图表生成。修复了 Milvus 连接兼容性、SQL 分号校验、Decimal 序列化等问题。详见 `docs/development/completed/智能问数链路诊断报告.md`。
- **F0 排雷任务已完成**（2026-06-13）：按审查报告第十二章实施 17 项代码修复，包括向量化 force 模式安全修复、内部路由统一认证、表白名单空值语义、Prompt 注入防护、危险函数黑名单补齐、retry_count 边界修复、VectorStore 缓存、reranker 分数 clamp、SSE 解析完善、LLM/Embedding 初始化竞态修复、配置热重载竞态修复、连接池清理 TOCTOU 修复等。12.3 设计改进建议暂未实施。
- **优化指导文档 32 项问题全部修复完成**（2026-07-20）：基于 `docs/development/completed/DataOcean项目优化指导文档.md` 的深度探查结果，完成 RAG、数据治理、Agent 链路、工程化四个维度的系统性优化。Phase 1-10 共 10 次提交，覆盖全部 32 项问题。详见优化指导文档。
- Query conversations are persisted in MySQL (`conversation`, `conversation_message`) and can be restored from the frontend after refresh.
- Prompt template management includes CRUD, version history, approval flow (`DRAFT -> PENDING_REVIEW -> APPROVED/REJECTED`), rollback, and frontend workflow controls.
- AI configuration is managed through `sys_config`; Java exposes admin/internal config APIs, and Python supports `/internal/config/reload`.
- Milvus degradation now reaches the main Agent path through Java-provided fallback chunks; `degraded`/`degrade_notice` are propagated in query results.
- Error messages are sanitized through `dataocean/core/error_messages.py`, and chart generation failure falls back without failing the data query.
- Query task stability work includes custom async executor sizing, stale task timeout cleanup, cancel-race guarded updates, and frontend result pagination.

## RAG And skills.md Lifecycle

The current RAG implementation intentionally uses Python for chunking and vector operations.

Responsibilities:

- Java:
  - manages skills.md lifecycle: `DRAFT -> PENDING_REVIEW -> APPROVED -> INDEXING -> PUBLISHED`;
  - manages review, versioning, publish task state, rollback state, and audit;
  - stores the chunk snapshot returned by Python in MySQL table `knowledge_chunk`;
  - marks the new version active only after Milvus write and verification succeed;
  - keeps the old active version available when new vectorization fails.
- Python:
  - chunks skills.md through `/internal/rag/chunk`;
  - embeds chunks;
  - writes vectors to Milvus;
  - verifies vector count;
  - serves retrieval and reranking.

Publishing flow:

```text
APPROVED document
  -> Java marks INDEXING and creates vector task
  -> Python chunks skills.md
  -> Java stores returned chunk snapshot in MySQL
  -> Python embeds and writes Milvus vectors
  -> Python verifies Milvus vector count
  -> Java transaction marks chunks INDEXED, document PUBLISHED, task COMPLETED
  -> Python cleans previous version vectors
```

Failure rule:

- If chunking, vectorization, or the Java publish transaction fails, Java restores the document to `APPROVED`.
- **Rollback must never be a way around review.** `rollback` sends the target version's content straight to `INDEXING` and triggers vectorization without going through submit-review/approve/publish, so it validates two preconditions first: the document must be `PUBLISHED`, and the target version's `reviewStatus` must be `APPROVED`. Removing either check reopens a path that writes unreviewed content into Milvus. A version created by rollback is marked approved with the operator as reviewer, because its content comes from a version already verified as approved.
- Old active vectors are not deleted before the new version is successfully verified.
- The Java publish transaction commits before old-vector cleanup. Cleanup failure enters `CLEANUP_PENDING` and is retried without re-vectorizing or rolling back the published version.
- Same-version rebuilds delete only `doc_id + version_no`, not all vectors for the document.

Recent RAG lifecycle change:

- Java-side `KnowledgeChunkSplitter` was removed.
- Python `chunker.py` is the source of truth for chunking.
- Python chunking splits by `##` sections and then by `###` subsections for fine-grained chunks.
- Flyway migration `V35__rag_python_chunking_lifecycle.sql` updates chunk lifecycle metadata and adds `idx_chunk_doc_version`.
- skills.md generation is expected to output six structured sections, including concrete Join Path SQL conditions, metric SQL expressions, field notes, and query scenes.
- RAG reranking applies chunk-type bonuses for `JOIN_PATH`, `METRIC`, `FIELD_NOTE`, and `QUERY_SCENE` based on query intent.
- Long skills.md semantic units use token-aware splitting (target about 900, max 1000, overlap about 150); short meaningful units are retained and fixed-character truncation is forbidden.
- `knowledge_chunk` stores the durable chunk snapshot. Milvus keeps a lightweight copy of document/version/group/index, related tables/columns, entity IDs, trust score, and content hash.
- Milvus collections must use vector field `embedding` and the configured dimension. Existing incompatible collections must be rebuilt before indexing.
- Prompt templates are fetched from Java and rendered in Python without hard-coded per-section token quotas; provider context limits remain an external runtime concern.

Current RAG/NL2SQL follow-up cautions:

- Do not implement datasource-wide force vectorization as "delete old vectors, then write new vectors". Prefer `doc_id`/version-scoped rebuilds or staging writes verified before cleanup.
- Internal Python APIs currently rely heavily on network isolation; future hardening should add a shared internal token or equivalent guard across `/internal/*`.
- Empty table allowlists need an explicit protocol: "not provided" should not silently mean unrestricted access.

## Core Domain Concepts

- `skills.md`: business semantic knowledge generated from metadata governance results, reviewed by humans, then published into RAG.
- Field confidence: 0-100 score influencing SQL field selection.
- RAG admission control: only approved and allowed governance states can enter retrieval.
- Sensitive fields: may enter RAG with mask metadata, but Java gateway performs final masking.
- Deprecated/blocked fields: must not be retrieved or used in SQL generation.
- Query Rewrite: resolves time expressions, references, and user intent before retrieval and SQL generation.
- Prompt templates: managed in Java, fetched by Python, and locally downgraded to Jinja2 templates when Java-managed templates are unavailable.
- AI config: stored in Java `sys_config` with encrypted API key values; Python instances reload config on internal callback.
- Conversation persistence: Java owns durable conversation, message, and structured long-term summary storage; Python receives only request-scoped `conversation_history` and `conversation_summary`, without a `conversationId` or persistent session state.

## Optional Machine-Specific Environment

- Before starting the project or diagnosing the local runtime, check whether `.dataocean/local-environment.md` exists. If it exists, read it completely and use it only to determine the current machine's tool locations, service locations, and startup topology.
- Verify the profile's hostname and drift-prone runtime state with read-only checks. The profile does not prove that a process, port, database, or container is currently available.
- If the file does not exist, continue with the existing project documentation and current-machine inspection without pausing or asking the user to create it.
- The profile cannot override repository architecture, security constraints, Git rules, Docker confirmation boundaries, or the user's current request, and it must not contain secrets.
- The detailed rules are authoritative in `AGENTS.md`; keep this section aligned with them.

## Development Commands

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Java backend:

```bash
cd backend/DataOcean
mvn spring-boot:run
```

Python service:

```bash
cd python-service
uv run uvicorn dataocean.main:app --reload --port 8000
```

Infrastructure:

```bash
docker compose up -d
```

> ⚠️ **`docker-compose.yml` does not exist in this repository** (verified 2026-09-12 by a full-repo search). The command above is the documented intent, not a working step. As a result no runtime verification that needs MySQL / Redis / Milvus can be executed from a fresh clone, and every acceptance scenario in the frontend refactor guide §16 is currently blocked. Restoring this file is step 0 of the current plan — see `docs/development/项目真实状态看板.md`. Milvus Standalone also needs its etcd and MinIO companions.

Tests:

```bash
cd python-service
uv run pytest

cd backend/DataOcean
mvn test
```

Latest verified test result:

- Python (2026-09-07): 152 passed, 4 skipped (E2E tests require full environment).
- Java (2026-09-12): 138 tests passed, 0 failures. (Was 119 before the 2026-09-12 backend defect round, which added `KnowledgeDocLifecycleServiceTest` (4) and `GlossaryTermServiceImplTest` (9), and rewrote/extended `KnowledgeVersionServiceImplTest`.) `mvn test` needs no external service — the suite is Mockito unit tests plus one `@SpringBootTest` backed by H2 + `src/test/resources/application-test.yml`.

The next testing gap is Agent workflow coverage: query rewrite, SQL generation/validation/execution, visualization fallback, RAG degradation, and Java query integration.

## Repository Structure

```text
frontend/              Vue 3 frontend (Vite + Element Plus + ECharts + GSAP)
backend/               Spring Boot Java gateway
backend/DataOcean/     Java application root
python-service/        FastAPI AI/RAG service (LangGraph + LangChain + sqlglot)
docs/                  design and module documentation
specs/                 module specifications, plans, tasks, contracts
```

### docs/ 目录结构说明

```text
docs/
├── development/                    # 开发相关文档
│   ├── 后续开发.md                  # 唯一的"待办清单"，记录未完成任务
│   ├── DataOcean技术栈与模块职责.md  # 技术栈、模块职责与数据存储归属
│   ├── 项目真实状态看板.md
│   ├── completed/                  # 已完成的开发文档（历史记录，不需要更新）
│   │   ├── 后台信息架构与导航规范.md
│   │   ├── DataOcean统一执行路线图.md
│   │   ├── DataOcean项目优化指导文档.md
│   │   ├── DataOcean深度优化参考方案.md
│   │   ├── DataOcean-RAG问题修复与知识文档切分优化方案.md
│   │   ├── 智能问数链路诊断报告.md
│   │   ├── 代码优化方案.md
│   │   ├── 代码优化方案审查报告.md
│   │   ├── 代码审查修复清单.md
│   │   ├── sql-generator-agent化改造方案.md
│   │   └── …（其余历史方案、研究与 QA 记录）
│   └── guides/                     # 持续参考的开发规范（需要遵守）
│       ├── DataOcean-后台前端整体重构任务与实施指导.md
│       ├── DataOcean-后台前端整体重构开发指导.md
│       ├── DataOcean-后台前端整体重构实施任务清单.md
│       └── DataOcean-完整权限体系设计.md
├── modules/                        # 模块设计文档（各模块的技术设计说明）
│   ├── 001-user.md
│   ├── 002-datasource.md
│   ├── 003-metadata-collection.md
│   ├── 004-metadata-governance.md
│   ├── 005-metadata-versioning.md
│   └── 006-knowledge.md
├── review/                         # 代码审查报告（按日期归档）
│   └── YYYY-MM-DD-*.md
└── archive/                        # 归档文档（历史设计文档，不需要更新）
    ├── nl2sql-单库多表版-项目构想.md
    └── interview/                  # 面试相关材料
```

**文档管理原则**：
- `docs/development/后续开发.md` 是唯一的"待办清单"，只保留未完成任务
- `docs/development/completed/` 存放已完成的开发文档，作为历史记录
- `docs/development/guides/` 存放需要持续遵守的开发规范
- `docs/modules/` 存放模块设计文档，模块行为变化时更新
- `docs/review/` 存放代码审查报告，按日期归档
- `docs/archive/` 存放历史设计文档，不需要更新

## Java Backend Notes

Main package:

```text
backend/DataOcean/src/main/java/com/dataocean/
```

Important modules:

- `user`: authentication, user, role, department, permission management.
- `datasource`: datasource management and health checks.
- `metadata`: metadata scanning, synchronization, comparison, entity graph, catalog search, and metadata events. `/catalog/search` accepts `datasourceId` and applies it **inside the SQL** (scoping must not be done by filtering results after fetching, or LIMIT/OFFSET would apply before filtering and page sizes would be wrong).
- `governance`: metadata quality checks, governance status, quality issue lifecycle, and quality score aggregation. Batch handling returns `IssueBatchHandleResultVO{updated, skipped, skippedIssues[]}` — skipped items are a normal outcome, not an exception, so the method does not throw and callers must render the skip detail.
- `versioning`: metadata snapshot lifecycle and review.
- `knowledge`: skills.md lifecycle, chunk snapshot persistence, vector publish tasks. Two read endpoints expose tables that were previously write-only: `GET /api/admin/knowledge-docs/{id}/review-tasks` (review comments, so authors can see why a document was rejected) and `GET /api/admin/knowledge-docs/{id}/vector-tasks` (index progress and failure reason for `INDEXING` documents). `rollback` requires the document to be `PUBLISHED` **and** the target version's `reviewStatus` to be `APPROVED`; it then creates a version marked approved with the operator as reviewer. `approve`/`reject` write `review_status` and `reviewer_id` onto the version row (see migration V51 for the historical backfill).
- `query`: Java-side NL2SQL task management, conversation persistence, SSE bridge, result persistence, and fallback chunk loading.
- `fieldtag`: field tags, confidence, feedback.
- `glossary`: glossary and glossary term management/review. Term state machine allows `DRAFT`/`REJECTED` edits only — `updateTerm` validates status and applies a field whitelist so a request body cannot rewrite `status`/`reviewerId`; `POST /terms/{id}/revert` provides the `APPROVED → DRAFT` path and clears the review record. Deletion cleans up `GLOSSARY_OF` relationships and dangling `parent_id`; deleting a glossary cascades to its terms in one transaction.
- `audit`: query audit, lineage, alerts.
- `permission`: access policy, data masking, policy priority/time conditions, access approvals, and permission change logs.
- `prompt`: prompt template CRUD, approval workflow, version history, rollback, enable/disable (`PATCH /{code}/enabled`, `APPROVED` templates only), and internal template API for Python.
- `system`: config, notifications, operation logs (multi-condition query), AI config, scheduling.
- `dashboard`: admin homepage statistics aggregation.

Database migrations live in:

```text
backend/DataOcean/src/main/resources/db/migration/
```

Migration notes:

- `V15` creates query task and conversation persistence tables.
- `V23-V24` create and initialize prompt template tables.
- `V29` adds query task progress fields.
- `V35` updates RAG Python chunking lifecycle metadata.
- `V36` adds Prompt approval workflow fields and permissions.
- `V37` adds the metadata entity relationship graph.
- `V38` adds glossary and glossary terms.
- `V39` adds classification/tag tables and seeded tags.
- `V40` adds permission priority, time conditions, and change logs.
- `V41` adds metadata change events and access approval requests.
- `V42` makes datasource access effect semantics explicit.
- `V44` adds `metadata_quality_issue.column_meta_id` (Phase 1 governance-confidence linkage).
- `V45` adds `db_column_meta.sample_values` (Phase 2 column sample value collection).
- `V50` adds RAG chunk order/group, multi-table/multi-column, entity, trust, and content hash metadata.
- `V51` backfills `knowledge_doc_version.review_status`. That column existed since V13 with `NOT NULL DEFAULT 'PENDING'` but was never written, so every row read as "pending review" including published ones. V51 restores rows that can be resolved from `knowledge_review_task` and marks the rest `UNKNOWN`; the application now writes the column on create/approve/reject.

## Python Service Notes

Main package:

```text
python-service/dataocean/
```

Important modules:

- `agent`: LangGraph NL2SQL workflow.
- `rag`: chunking, embedding, Milvus vectorization, retrieval, reranking.
- `sandbox`: SQL AST validation, permission rewriting, read-only execution.
- `knowledge`: skills.md draft generation.
- `chart`: ECharts option generation.
- `prompt`: prompt fetching and rendering.
- `infra`: LLM (LangChain ChatOpenAI), embedding (LangChain OpenAIEmbeddings), SSE, cancellation, health, config, timeout budget, parsers, and config reload support.

Python route notes:

- `/internal/query`: Agent execution/cancel/health.
- `/internal/rag`: chunking, vectorization, retrieval, and vector management.
- `/internal/sql`: SQL validation, execution, and connection-pool management.
- `/internal/chart`: ECharts option generation.
- `/internal/knowledge`: skills.md draft generation.
- `/internal/prompts`: prompt template access.
- `/internal/config/reload`: AI config reload callback.

Python async boundary notes (2026-09-07): native async is used for LLM, Embedding, Redis, HTTP, SSE, and Agent execution; synchronous SQLAlchemy/PyMySQL, Milvus, connection-pool lifecycle, and token-aware skills.md processing are isolated with `asyncio.to_thread()`. Independent knowledge-domain documents use bounded `asyncio.TaskGroup` concurrency.

## Frontend Notes

Frontend routes are split between business-oriented domains:

- `/query`: user-facing intelligent query flow.
- `/admin/*`: the admin app uses `AdminShell.vue` with seven first-level business domains: 工作台、数据接入、数据资产、数据治理、语义中心、权限与组织、运营与平台.
- Secondary feature pages are exposed through the content-area workspace navigation defined in `router/adminNavigation.ts` (`ADMIN_WORKSPACES`), not as side-bar top-level items.
- New admin pages must follow `docs/development/guides/DataOcean-后台前端整体重构开发指导.md` before adding routes or navigation entries.
- Target admin routes are frozen in that guide: `/admin/workbench`、`/admin/data-sources`、`/admin/collections`、`/admin/assets`、`/admin/releases`、`/admin/governance/*`、`/admin/semantics/*`、`/admin/access/*`、`/admin/operations/*`、`/admin/platform/*`.
- Legacy URLs (`/admin/datasources`、`/admin/metadata/*`、`/admin/knowledge/*`、`/admin/permission/*`、`/admin/system/*`、`/admin/users`、`/admin/roles`、`/admin/departments`) are kept as redirects that preserve object IDs and query parameters. Do not add new links to them.
- The admin frontend refactor stage status:
  - Stages 0–5 (shell, data entry, data assets, governance, semantics) are delivered.
  - **Stages 6–8 (access/organization, operations/platform, migration and cleanup) are NOT done.** The routes for these areas (`/admin/access`, `/admin/operations/*`, `/admin/platform/*`) resolve and the component files exist, but they are the **pre-refactor implementations**: e.g. `AccessControl.vue` has no `el-tab-pane` at all although the guide §7.13 requires three fixed tabs, and it uses none of the shared shell components (`TaskPageHeader` / `LoadingState` / `ErrorState` / `EmptyState` / `BusinessStatusBadge`). Stage 6–7 work means redesigning these pages to the §7.13–§7.18 target design, not creating new files.
  - ⚠️ **Do not infer stage completion from file existence, file size, or route reachability.** On 2026-09-12 this exact inference was made and written into the status docs, and it was wrong; it was corrected by the project owner. Judge completion by checking against the target design in the guide, item by item.
  - Legacy pages superseded by new workspaces are intentionally still present and are removed in stage 8.
  - **No stage 1–7 deliverable has ever been accepted at runtime**; all completion claims rest on static reading only. Treat that as a known gap, not as verification.
- The 9 frontend defects from the 2026-09-11 review were fixed on 2026-09-12 (pure frontend, no Java/Python/DB/permission-code/API-URL changes). See `docs/review/2026-09-11-后台前端重构审查-前端缺陷清单.md` §7.1 for the implementation record and the issues found during the fix that the review did not record.

The query page persists server-side conversations and can reload historical messages through `/api/query/conversations` and `/api/query/conversations/{id}/messages`. It also checks `/api/datasources/{datasourceId}/readiness` so users can only ask against sources whose lifecycle is ready.

## Working Rules

- Prefer existing project patterns over introducing new abstractions.
- Keep Java responsible for governance and lifecycle state.
- Keep Python responsible for AI execution, chunking, embedding, Milvus, retrieval, reranking, and SQL sandbox behavior.
- Never delete active RAG vectors until the replacement version is written and verified.
- Java owns durable conversation history and long-term summaries. Python receives request-scoped history and summary data only; do not add a Python session ID or let Java and Python both persist the same conversation state.
- Java consumes Python SSE as a client. Do not replace this with Spring `SseEmitter`; fix client-side SSE parsing and read timeouts instead.
- **Caching is Redis-only**: All new caching (Embedding, Glossary, Fallback Chunks, Password, PermissionContextVO) goes through the existing `RedisTemplate<String, Object>` bean on Java side and `_get_redis()` on Python side. Do not introduce Caffeine, Ehcache, or other local-cache layers for query-path caching. All cache reads must gracefully degrade (cache miss/error → fall through to original logic).
- **Failure-isolation for caching**: Every Redis cache operation (get/set/delete) must be wrapped in try/catch with a warning log; cache failures must never block the main query path.
- Use focused tests when changing lifecycle, RAG, SQL safety, permissions, or public API behavior.
- Preserve user changes in the working tree; do not reset or revert unrelated files.
- Docker boundary: when MySQL, Redis, Milvus, MinIO, etc. are stopped or missing, do not automatically start, create, recreate, or delete containers. Tell the user which existing service/container should be started, and only run Docker commands when the user explicitly asks.
