# Data Model: Schema RAG 召回模块

## Milvus Collection: `schema_knowledge`

| Field | Type | Description |
|---|---|---|
| id | INT64 | Milvus 自动生成主键 |
| datasource_id | INT64 | 数据源隔离字段 |
| snapshot_id | INT64 | 元数据快照 ID |
| knowledge_version_no | INT64 | skills.md 版本号 |
| doc_id | INT64 | skills.md 文档 ID |
| source_id | INT64 | MySQL `knowledge_chunk.id` |
| chunk_type | VARCHAR | `TABLE_DESC` / `JOIN_PATH` / `METRIC` / `FIELD_NOTE` / `QUERY_SCENE` |
| governance_status | VARCHAR | `NORMAL` / `RECOMMENDED` 等准入状态 |
| review_status | VARCHAR | `APPROVED` 才可召回 |
| chunk_text | VARCHAR | 检索文本 |
| related_table | VARCHAR | 关联表名 |
| related_column | VARCHAR | 关联字段名 |
| chunk_index | INT64 | 同一文档版本内的稳定顺序 |
| chunk_group_id | VARCHAR | 同一语义小节的分组标识 |
| related_tables | VARCHAR/JSON | 多表关联信息，Milvus 中以 JSON 文本保存 |
| related_columns | VARCHAR/JSON | 多字段关联信息，Milvus 中以 JSON 文本保存 |
| entity_ids | VARCHAR/JSON | `metadata_entity` ID 列表，Milvus 中以 JSON 文本保存 |
| trust_score | INT64 | 来源可信度，可选 |
| content_hash | VARCHAR | chunk 文本 SHA-256 |
| embedding | FLOAT_VECTOR | embedding 向量 |

Milvus 中的结构化字段是检索过滤和上下文扩展所需的轻量副本；Java MySQL
`knowledge_chunk` 才是 chunk metadata 的完整持久化快照。Milvus Collection
必须使用 `embedding` 作为向量字段并与当前 Embedding 维度一致，旧 schema 不允许静默混写。

Milvus 是 RAG 检索主存储。检索强制过滤：

```text
datasource_id == 当前数据源
snapshot_id == activeSnapshotId
review_status == "APPROVED"
governance_status in ["NORMAL", "RECOMMENDED"]
```

## MySQL Snapshot Tables

### `knowledge_chunk`

`knowledge_chunk` 保存 Python 返回的 chunk snapshot，用于预览、审计、回滚重建和 Milvus 不可用时的 fallback。它不是 RAG 检索主存储。

| Field | Description |
|---|---|
| doc_id | skills.md 文档 ID |
| version_no | skills.md 版本号 |
| metadata_snapshot_id | 元数据快照 ID |
| chunk_type | Python 推断的 chunk 类型 |
| chunk_text | Python 切割后的 chunk 文本 |
| related_table | Python 提取的关联表 |
| related_column | Python 提取的关联字段 |
| chunk_index | 文档版本内顺序，从 0 开始 |
| chunk_group_id | 同一语义小节或相邻扩展分组 |
| related_tables | 多表关联 JSON 数组 |
| related_columns | 多字段关联 JSON 数组 |
| entity_ids | `metadata_entity` ID JSON 数组 |
| trust_score | 来源可信度 |
| content_hash | chunk 文本 SHA-256 |
| review_status | `APPROVED` |
| vector_status | `PENDING` / `INDEXED` / `SUPERSEDED` / `FAILED` |

### `vector_index_task`

Java 管理任务生命周期：

| Field | Description |
|---|---|
| datasource_id | 数据源 ID |
| target_type | 当前为 `DOC` / `KNOWLEDGE_DOC` |
| target_id | skills.md 文档 ID |
| metadata_snapshot_id | 快照 ID |
| knowledge_version_no | 待写入的新版本 |
| previous_version_no | 新版本生效后待清理的旧版本 |
| status | `PENDING` / `PROCESSING` / `CLEANUP_PENDING` / `COMPLETED` / `FAILED` |

## Version Switch Rule

1. Java 发布或回滚 skills.md 时创建 `vector_index_task`，文档状态进入 `INDEXING`。
2. Java 调用 Python `/internal/rag/chunk`，Python 按 `##` + `###` 切割完整 skills.md。
3. Java 将 chunk 清单保存到 `knowledge_chunk`，状态为 `PENDING`。
4. Java 调用 Python `/internal/rag/vectorize`。
5. Python 写入新版本 Milvus 向量，并校验新版本向量数等于 chunk 数。
6. Java 在一个事务中将新 chunk 标记为 `INDEXED`，文档标记为 `PUBLISHED`，任务标记为 `COMPLETED`。
7. Java 在发布事务提交后调用 Python `/internal/rag/vectors/delete` 清理 `previous_version_no` 的旧向量。
8. 向量化或发布事务失败时，任务标记为 `FAILED`，文档回到 `APPROVED`，旧版本继续可检索。
9. 发布已提交但旧向量清理失败时，任务进入 `CLEANUP_PENDING`；下一次调度只执行清理，成功后再标记 `COMPLETED`。

## Chunking Strategy

Python `dataocean.rag.chunker.chunk_skills_md` 是唯一切割实现：

- 按 `##` 划分大章节。
- 跳过“文档来源”。
- 按 `###` 切成细粒度 chunk。
- 每张表、每个指标、每条 Join Path、每个字段防坑、每个典型查询场景独立成 chunk。
- 普通 chunk 目标约 800-1000 token，目标值约 900 token。
- 长语义单元在段落/句子边界递归切分，overlap 约 150 token。
- 最终写入文本不再使用固定字符数静默截断，SQL 和 Join 条件必须完整保留。
- 短但有意义的语义单元保持原样，不为了填充 token 与无关内容拼接。
- `tiktoken/cl100k_base` 仅作为本地预算器；它不是 Qwen 服务端 tokenizer 的精确计费结果。
