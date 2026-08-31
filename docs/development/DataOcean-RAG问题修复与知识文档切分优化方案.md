# DataOcean RAG 问题修复与知识文档切分优化方案

## 1. 文档目的

本文档用于指导 DataOcean 当前 RAG 链路的修复和优化，范围包括：

1. `skills.md` 知识文档的生成质量和结构。
2. 文档切分为 chunk 的规则、长度和上下文重叠策略。
3. chunk 元数据、Milvus 向量索引和检索结果协议。
4. 召回、重排、关系增强、降级和缓存。
5. 向量发布一致性、测试和 RAG 效果评估。

本方案不引入 Google ADK、API Gateway、新的消息队列或新的向量数据库。继续使用现有的 Java、Python、LangChain、Milvus、MySQL 和 Redis 架构。

## 2. 当前结论

当前 RAG 主链路已经具备基本运行能力：

```text
Java 发布 skills.md
  -> Python 切分
  -> Embedding
  -> Milvus 写入
  -> 数据源/快照/治理状态过滤
  -> 向量召回和规则重排
  -> Schema Linking
  -> SQL 生成
```

但是当前实现有三类问题：

1. **知识文档内容本身存在可信度问题**：模板允许模型根据字段名推测业务含义、推断关联、生成指标和查询场景，这些内容不一定来自真实业务事实。
2. **chunk 切分策略与目标不一致**：当前代码使用字符数配置，不是目标的 token 配置；同时依赖 Markdown 标题，缺少稳定的 chunk 顺序、分组和来源信息。
3. **检索链路的结构化数据没有完整传递**：相邻 chunk、关系增强、列级置信度和 fallback 版本隔离都存在实际缺口。

因此，本次不建议重写 RAG，而是先修复知识内容和数据协议，再优化召回算法。

## 3. 已确认的问题

### 3.1 `skills.md` 生成内容存在未经证实的推断

当前模板中存在以下风险：

- 无注释字段允许根据字段名和类型推测用途。
- 没有外键关系时，要求模型根据字段命名推断关联。
- 仅根据元数据生成指标口径、筛选条件和 SQL 表达式。
- 仅根据表结构生成常见查询场景和 SQL 骨架。
- 文档要求每个三级标题控制为 3-8 行，容易为了满足格式压缩或遗漏必要事实。

这些内容如果进入已审核文档并向量化，模型可能把“推测”当成真实的表关系、指标口径或字段含义，进而影响 SQL 生成。

当前模板位置：

`python-service/dataocean/knowledge/prompts/skills_md_template.j2`

### 3.2 当前 chunk 代码不是 800-1000 token 方案

当前 `chunker.py` 使用的是：

```python
MAX_CHUNK_TEXT_LENGTH = 8000
LONG_CHUNK_SIZE = 3000
LONG_CHUNK_OVERLAP = 200
```

这些值是字符数，不是 token 数。当前流程还有以下问题：

- 只有超过 8000 字符才进入递归切分。
- 递归切分目标为 3000 字符，通常大于推荐的 800-1000 token 范围。
- 最后使用 `enriched_text[:MAX_CHUNK_TEXT_LENGTH]` 截断，可能截断 SQL、Join 条件或字段说明。
- overlap 没有按 token 计算。
- 结构化小节、SQL、Join Path 没有独立的顺序标识。
- `source_id` 是单条 `knowledge_chunk.id`，不能用于查找同一文档的相邻 chunk。

当前实现位置：

`python-service/dataocean/rag/chunker.py`

### 3.3 相邻 chunk 扩展缺少必要的数据

`vector_store.py` 查询了 `source_id`，但构造返回 `Document` 时没有把它完整放入 metadata，因此当前扩展逻辑无法正常触发。

即使补充 `source_id`，它仍然只是 chunk 自身 ID，不表示：

- 所属文档。
- 所属文档版本。
- chunk 在文档内的顺序。
- 所属语义小节或 chunk 组。

因此必须新增文档版本和 chunk 顺序信息，不能只补一个字段。

### 3.4 关系增强和列级 Schema Linking 信息不完整

当前 `RetrievedSchema` 和 Milvus metadata 没有稳定传递：

- `entity_id`。
- `trust_score`。
- 完整的字段列表。
- 一个 chunk 关联的多个表和多个字段。

结果是关系查询可能被跳过，字段置信度可能回落为默认值，列级 Schema Linking 得到的往往只是一个 `related_column` 字符串，而不是完整结构化元数据。

相关位置：

- `python-service/dataocean/rag/schema.py`
- `python-service/dataocean/agent/nodes/schema_retriever.py`
- `python-service/dataocean/agent/nodes/schema_linker.py`

### 3.5 fallback 可能使用错误版本或无关内容

当前 Java fallback 存在以下问题：

- Redis key 只有 `datasourceId`，没有 active snapshot。
- 数据库查询没有严格绑定当前生效快照。
- 没有严格过滤 `vector_status = INDEXED`。
- 固定取前 5 条 `TABLE_DESC`，不是按当前问题筛选。
- Python fallback 不根据问题做相关性排序。

相关位置：

`backend/DataOcean/src/main/java/com/dataocean/module/query/client/impl/PythonAgentClientImpl.java`

### 3.6 向量发布和缓存存在一致性风险

- 向量删除接口忽略了底层删除失败结果，可能把失败当成功。
- 向量清理成功与否没有形成严格的任务状态。
- Embedding 缓存 key 没有包含模型、提供商、维度和配置版本。
- Milvus 已有 collection 的维度和 metric 没有充分校验。
- `/re-vectorize` 当前不是完整的重建任务入口。

## 4. 目标设计

### 4.1 知识文档的事实来源分层

生成 `skills.md` 时按以下优先级处理：

| 优先级 | 内容来源 | 是否可以作为 SQL 依据 |
|---|---|---|
| 1 | 已发布元数据快照：表、字段、类型、主键、索引、治理状态 | 可以 |
| 2 | 已审核外键关系、Join Path、Glossary、指标口径 | 可以 |
| 3 | 人工确认的业务说明和查询场景 | 可以 |
| 4 | 模型根据字段名推测的描述 | 只能作为待确认草稿 |

必须调整生成规则：

1. 没有明确关系时写“未确认”，不能直接推断为 Join Path。
2. 没有已审核指标口径时，不生成看似确定的 SQL 指标公式。
3. 没有真实业务依据时，不生成确定性的常见查询场景。
4. 字段用途可以由模型提出候选，但必须标记“待人工确认”，且未确认内容不能进入已发布 RAG 索引。
5. 文档内容要保留来源和置信度，不能只保留模型改写后的自然语言。

### 4.2 知识文档按业务域组织

不建议把整个数据源的所有表、关联、指标和场景塞进一个超大的 `skills.md`。文档应按业务域或强关联表集合组织：

- 一个文档对应一个相对完整的业务主题。
- 表说明、Join Path、指标和字段防坑仍然可以放在同一主题文档内。
- 不属于该业务域的表和关系不放入文档。
- 文档中的每个三级小节必须是一个语义完整单元。

当前已有批量按业务域生成文档的能力，应优先修正单文档生成路径，使两种生成方式遵守同一结构契约。

### 4.3 文档结构必须可验证

`skills.md` 继续使用 Markdown，但发布前必须进行确定性校验：

1. 必须存在文档来源、核心表说明、Join Path、指标口径、字段防坑和查询场景六类顶级章节。
2. 每个三级标题必须有非空内容。
3. Join Path 必须包含明确的关联条件，不能只有自然语言描述。
4. 指标必须包含来源、适用表和审核状态；没有公式时不能伪造公式。
5. SQL 代码块、表名、字段名必须可以追溯到当前元数据快照或已审核知识。
6. 未确认内容必须有明确标记，不能被误认为已发布事实。
7. 失败时不能进入 `PUBLISHED` 和 Milvus 向量化流程。

## 5. 目标 chunk 切分方案

### 5.1 长度规则

目标采用 token，而不是字符数：

| 项目 | 目标 |
|---|---|
| 普通 chunk | 约 800-1000 token |
| 长文本切分后的 overlap | 约 150 token |
| 上下文前缀 | 尽量控制在最终 chunk 的 10%-15% 内 |
| 长度计算范围 | 包含上下文前缀在内的最终 embedding 文本 |

这里的 800-1000 token 是推荐范围，不要求所有小节强行填满。一个完整的 Join Path 或字段防坑只有 200 token 时，应保持为一个完整 chunk，不应为了凑长度和其他无关内容拼接。

同时，不再使用“超过 8000 字符才切分、切成 3000 字符”的规则，也不在末尾使用固定字符截断。只允许过滤空白、标题残片等没有语义的噪声，不能因为 chunk 较短而删除有意义内容。

### 5.2 切分顺序

```text
规范化 Markdown
  -> 校验顶级章节和三级标题
  -> 按语义单元切分
  -> 为每个单元补充上下文元数据
  -> 仅对超长语义单元进行 token 切分
  -> 生成 chunk_index 和 chunk_group_id
  -> 计算 content_hash
  -> 保存 MySQL 快照
  -> Embedding 并写入 Milvus
```

具体规则：

1. 先按 `##` 和 `###` 找到语义单元，而不是先按字符切断。
2. 表说明、单条 Join Path、单个指标、单个字段防坑、单个查询场景优先保持完整。
3. 只有语义单元超过 1000 token 时，才使用递归文本切分。
4. 超长切分优先在段落、列表项、句子边界切分。
5. SQL、Join 条件、字段定义等代码或结构化内容不能从中间切开。
6. 超长切分时使用约 150 token overlap，overlap 只复制相邻内容，不重复堆叠上下文前缀。
7. 每个 chunk 都保留文档标题、业务域、章节、表名和字段名等上下文。
8. 关联多张表的 Join Path 必须保存多个表名，不能只保存一个 `related_table`。

### 5.3 chunk 元数据

每个 chunk 至少应包含：

```json
{
  "doc_id": 101,
  "knowledge_version_no": 3,
  "snapshot_id": 5,
  "source_id": 205,
  "chunk_index": 12,
  "chunk_group_id": "doc-101-v3-join-orders-users",
  "chunk_type": "JOIN_PATH",
  "related_tables": ["orders", "users"],
  "related_columns": ["orders.user_id", "users.user_id"],
  "entity_ids": [301, 401],
  "trust_score": 92,
  "content_hash": "...",
  "review_status": "APPROVED",
  "governance_status": "NORMAL"
}
```

字段语义必须明确：

- `source_id`：仍表示 MySQL `knowledge_chunk.id`，只代表当前 chunk。
- `doc_id`：表示所属知识文档。
- `knowledge_version_no`：表示知识文档版本。
- `chunk_index`：表示该版本文档内的稳定顺序。
- `chunk_group_id`：表示同一语义小节或可一起扩展的 chunk 组。
- `related_tables`、`related_columns`：允许多值，不能继续依赖单值字符串。

### 5.4 相邻上下文扩展

相邻扩展应改为：

```text
命中 chunk
  -> 找到相同 doc_id + knowledge_version_no + chunk_group_id
  -> 按 chunk_index 查询前后相邻 chunk
  -> 去重
  -> 保持原始召回分数和来源标记
```

不允许使用 `source_id in (...)` 代替相邻查询，因为 `source_id` 只是 chunk ID，不能表达顺序。

## 6. 目标检索方案

### 6.1 基础检索和准入过滤

继续使用现有的强制过滤：

```text
datasource_id == 当前数据源
snapshot_id == activeSnapshotId
review_status == APPROVED
governance_status in [NORMAL, RECOMMENDED]
```

这部分属于安全和数据隔离要求，不应删除。

### 6.2 结构化 Schema 获取

RAG 不应只把自然语言 chunk 文本直接交给 SQL 生成器。推荐分两步：

1. RAG 根据问题召回相关表、字段、Join Path 和指标候选。
2. Python 根据候选表的 `entity_id` 或表名，批量向 Java 获取当前快照下的完整结构化元数据。

最终传给 SQL Agent 的上下文应同时包含：

- 表名和表注释。
- 完整字段、类型、注释、主键和索引。
- 字段可信度和治理状态。
- 已审核 Join Path。
- 已审核指标口径。
- RAG 命中的原始 chunk 和分数。

这样可以避免因某个 chunk 只提到一个字段，就误认为该表只有一个字段。

### 6.3 重排和去重

当前规则加分可以继续作为 MVP，但要增加：

- 按表和语义类型去重，避免同一张表占满 Top K。
- Join Path 命中多个表时提升相关表的联合召回。
- 区分“向量相似度”和“业务规则加分”，不要直接混成无法解释的单一分数。
- 低分结果标记为低可信候选，不能仅因为没有结果就无条件当作可靠上下文。

后续再根据真实评测集决定是否引入混合检索或模型重排，不在本次直接引入新的外部服务。

## 7. fallback 方案

fallback 必须和当前快照绑定：

```text
fallback:chunks:{datasource_id}:{active_snapshot_id}
```

数据库查询至少应满足：

- 当前 `datasource_id`。
- 当前 `metadata_snapshot_id`。
- `review_status = APPROVED`。
- `vector_status = INDEXED`，或明确允许的已发布状态。
- 文档版本属于当前生效知识版本。

fallback 不再固定取任意前 5 条。应优先按当前问题中的表名、字段名、术语和已识别业务域筛选，再返回相关表结构和核心知识。数量可以配置，但不能把固定 5 条作为正确性前提。

如果无法做到查询相关性筛选，宁可返回当前快照的结构化表元数据，也不要返回可能属于旧版本的随机 chunk。

## 8. 向量化、缓存和发布一致性

### 8.1 向量化协议

Java 到 Python 的向量化请求需要传递完整的 chunk metadata，包括 `chunk_index`、`chunk_group_id`、多表关联、实体 ID 和可信度。

Python 写入 Milvus 前要校验：

- embedding 维度与目标 collection 一致。
- collection 的 metric 与当前向量处理方式一致。
- 当前 doc/version 的 chunk 数和 payload 数一致。
- 同一 `doc_id + version_no + source_id` 不重复写入。

### 8.2 删除和重建

向量删除必须满足：

1. 检查底层 `delete_by_expr` 的返回值。
2. 删除失败时任务不能标记为成功。
3. 删除操作保持幂等，重试不会误删其他文档版本。
4. 删除后按 doc/version 查询数量进行确认。
5. 指定目标 collection，不能默认所有请求都使用同一个 collection。

### 8.3 Embedding 缓存

Embedding 缓存 key 至少包含：

```text
embedding:{provider}:{model}:{dimension}:{config_version}:{question_hash}
```

切换模型、提供商、维度或相关配置版本时，旧缓存不能直接复用。

## 9. 实施步骤

### 阶段一：修正文档生成和切分协议

涉及：

- `python-service/dataocean/knowledge/prompts/skills_md_template.j2`
- `python-service/dataocean/rag/chunker.py`
- `python-service/dataocean/rag/schema.py`
- Java `KnowledgeChunk` 和 chunk 保存逻辑

工作内容：

1. 删除“根据字段名推断关联”的确定性表述。
2. 将指标和查询场景区分为已审核事实和待确认候选。
3. 增加文档结构校验。
4. 使用 token-aware splitter。
5. 实现 800-1000 token 目标和约 150 token overlap。
6. 增加 chunk 顺序、分组、来源和内容 hash。

### 阶段二：修复 Milvus metadata 和上下文扩展

涉及：

- `python-service/dataocean/rag/vectorizer.py`
- `python-service/dataocean/rag/vector_store.py`
- `python-service/dataocean/rag/retriever.py`
- Java `PythonRagClientImpl`
- Milvus collection schema

工作内容：

1. 完整传递并保存 chunk metadata。
2. 实现真实的 doc/version/group/index 相邻查询。
3. 传递实体 ID、可信度和多表/多字段信息。
4. 对 collection 维度和 metric 做启动或写入前校验。
5. 对旧 collection 做兼容迁移或重建，不能直接混写不同维度向量。

### 阶段三：修复结构化检索和 fallback

涉及：

- `python-service/dataocean/agent/nodes/schema_retriever.py`
- `python-service/dataocean/agent/nodes/schema_linker.py`
- `python-service/dataocean/rag/fallback.py`
- Java `PythonAgentClientImpl`

工作内容：

1. 关系查询改为批量获取或复用 HTTP Client。
2. 按 active snapshot 获取完整结构化 schema。
3. fallback 按数据源和快照隔离。
4. fallback 使用当前问题做确定性筛选。
5. 表级召回结果去重，并补充 Join Path 和指标上下文。

### 阶段四：修复一致性、缓存和评测

涉及：

- `python-service/dataocean/rag/service.py`
- `python-service/dataocean/rag/router.py`
- `python-service/dataocean/rag/vectorizer.py`
- RAG 测试目录

工作内容：

1. 删除失败不能被当作成功。
2. Embedding 缓存 key 增加模型和配置版本。
3. 明确 `/re-vectorize` 是真实重建入口，或在文档中标为未实现。
4. 增加 RAG golden questions。
5. 统计表、字段、Join Path 的 Hit@K、MRR 或 nDCG。
6. 增加快照切换、旧版本隔离、相邻 chunk 和 fallback 测试。

## 10. 测试和验收标准

### 10.1 文档和切分

- 文档六类顶级章节完整。
- 未确认关系、指标和场景有明确标记。
- 任何非空业务语义单元不会仅因为长度较短而被丢弃。
- 长 chunk 的最终文本约为 800-1000 token。
- 长 chunk overlap 约为 150 token。
- SQL 和 Join 条件不会从关键表达式中间截断。
- 每个 chunk 都有稳定的 doc/version/group/index/source metadata。

### 10.2 检索和上下文

- 数据源和 active snapshot 过滤始终生效。
- 命中 chunk 可以找到同一文档版本内的正确相邻 chunk。
- 多表 Join Path 能返回多个相关表。
- Schema Linking 能取得完整字段和可信度信息。
- 关系增强不再因为缺少 entity ID 而全部跳过。

### 10.3 降级和发布

- Milvus 不可用时不会返回旧快照 chunk。
- fallback 只使用当前允许状态的数据。
- 向量删除失败会让任务失败或进入可重试状态。
- 新版本向量验证失败时，旧版本仍然可用。
- 模型或维度变更后不会复用不兼容的 embedding cache。

### 10.4 质量评测

建立真实问题集，至少覆盖：

- 单表查询。
- 多表 Join。
- 指标计算。
- 时间范围和相对日期。
- 中文业务术语、别名和缩写。
- 字段防坑和敏感字段。
- 当前快照切换后的新旧版本隔离。

## 11. 文档同步要求

代码实施后同步更新：

- `AGENTS.md`：不能继续笼统地写“列级 Schema Linking 和关系增强已完成”，除非验收标准通过。
- `CLAUDE.md`：同步 RAG 的真实状态和新的 chunk metadata 契约。
- `specs/007-schema-rag/plan.md`：更新 token 切分、chunk metadata、fallback 和评测计划。
- `specs/007-schema-rag/data-model.md`：补充 chunk 顺序、分组、实体和可信度字段。
- `specs/007-schema-rag/contracts/internal-api.md`：补充 Java-Python 向量化和检索响应字段。
- `docs/modules/006-knowledge.md`：同步文档生成、发布和切分流程。
- `docs/development/DataOcean技术栈与模块职责.md`：补充 RAG 具体职责和数据流。

## 12. 不在本次修改范围内的内容

1. 不引入 Google ADK。
2. 不引入 API Gateway。
3. 不让 Python 保存 Java 的完整会话历史。
4. 不让 Java 和 Python 同时写同一份会话事实。
5. 不因为存在 chunk 长度参数，就删除所有切分规则。
6. 不在没有评测数据的情况下直接引入更复杂的模型重排服务。

本方案的核心原则是：先保证知识文档内容可信、chunk 能完整表达语义、版本和来源可追踪，再通过评测数据优化召回和重排，而不是继续堆叠固定阈值或额外服务。

## 13. 本次实施结果（2026-08-31）

已完成：

1. skills.md 模板已禁止把字段名推测、未审核指标、未确认 Join 和无事实依据的查询场景写成确定事实，并增加待确认约束。
2. Python chunker 已改为语义单元优先、token-aware 切分，目标约 900、最大 1000、长单元 overlap 约 150；短语义单元和完整字段列表会保留，不再固定字符截断。
3. chunk metadata 已贯通 Java/MySQL、Python 和 Milvus，包括文档/版本、顺序、语义分组、多表多字段、实体 ID、可信度和内容 hash。
4. Milvus 检索已显式使用 `embedding` 字段，写入前校验 Collection 向量字段和维度；上下文扩展按文档、版本、分组和顺序查找相邻 chunk。
5. fallback 已绑定 `datasource_id + active_snapshot_id`，严格拒绝无快照 chunk，缓存 key 增加问题摘要，并按 ASCII/中文关键词做确定性排序；Embedding 缓存 key 已包含提供商、模型、维度和配置版本。
6. 关系增强已改为从 `entity_ids` 批量并发读取 Java 关系；Java 在保存 chunk 时会根据当前数据源补齐表级和列级实体 ID，使 FOREIGN_KEY 列到列关系可被读取。
7. 向量任务先提交 Java 的 chunk、文档和任务状态，再清理旧版本向量；旧版本清理失败时进入 `CLEANUP_PENDING`，后续只重试清理，不重复向量化或回滚新版本。发布事务失败时会补偿删除本次新版本向量。

仍需部署/评测阶段完成：

1. 执行 `V50__add_rag_chunk_context_metadata.sql` 数据库迁移。
2. 对旧的 Milvus Collection 做一次重建；若旧 Collection 使用 `vector` 字段或旧维度，代码会拒绝混写。
3. 在已有 Milvus/外部 Embedding API 环境中验证真实写入、检索、删除和性能。
4. 建立 golden questions，统计表、字段、Join Path 的 Hit@K、MRR 或 nDCG。
5. `/internal/rag/re-vectorize` 目前仍是占位入口，正式重建继续使用 Java `vector_index_task` 编排。
