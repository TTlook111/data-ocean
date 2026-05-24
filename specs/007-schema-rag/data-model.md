# Data Model: Schema RAG 召回模块

## Milvus Collection Schema

### Collection: `schema_knowledge`

| Field | Type | Description |
|-------|------|-------------|
| id | INT64 (PK, auto_id) | 向量记录ID |
| vector | FLOAT_VECTOR (dim=1024) | Embedding 向量 |
| datasource_id | INT64 (partition key) | 数据源ID，分区键 |
| chunk_type | VARCHAR(30) | SCHEMA / KNOWLEDGE |
| doc_id | INT64 | skills.md 文档ID，用于同一文档版本替换 |
| source_id | INT64 | 来源切片ID (knowledge_chunk.id) |
| snapshot_id | INT64 | 元数据快照ID |
| knowledge_version_no | INT32 | 知识版本号 (chunk_type=KNOWLEDGE 时有值) |
| table_name | VARCHAR(200) | 关联表名 |
| governance_status | VARCHAR(20) | NORMAL / RECOMMENDED / DEPRECATED / BLOCKED |
| review_status | VARCHAR(20) | PENDING / APPROVED / REJECTED |
| trust_score | INT32 | 可信度评分 (0-100) |
| chunk_text | VARCHAR(8000) | 原文内容 |
| created_at | INT64 | 创建时间戳 (Unix ms) |

**Index**: IVF_FLAT on `vector` field, nlist=128
**Partition**: By `datasource_id`

### Version Replacement Rule

向量写入以 `(datasource_id, doc_id, knowledge_version_no)` 作为文档版本作用域：

1. Java 发布或回滚 skills.md 时传入 `docId`、`metadataSnapshotId`、`knowledgeVersionNo` 和 `previousVersionNo`。
2. Python 先写入新版本向量并 flush。
3. Python 查询新版本向量数量，必须等于请求中的 chunk 数。
4. 校验通过后删除同一 `(datasource_id, doc_id, previousVersionNo)` 的旧向量。
5. 校验失败时清理本次新写入的半成品向量，但不删除旧向量；Java 任务标记 FAILED，RAG 仍可继续召回上一版。

---

## MySQL 映射表 (Java 管理库)

### vector_index_item

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 映射记录ID |
| datasource_id | BIGINT | NOT NULL | 数据源ID |
| milvus_id | BIGINT | NOT NULL | Milvus 中的向量 ID |
| chunk_type | VARCHAR(30) | NOT NULL | SCHEMA / KNOWLEDGE |
| source_type | VARCHAR(30) | NOT NULL | KNOWLEDGE_CHUNK / METADATA_TABLE |
| source_id | BIGINT | NOT NULL | 来源记录ID |
| metadata_snapshot_id | BIGINT | NOT NULL | 快照ID |
| knowledge_version_no | INT | | 知识版本号 |
| table_name | VARCHAR(200) | | 关联表名 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'ACTIVE' | ACTIVE / DELETED |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| deleted_at | DATETIME | | 删除时间 |

INDEX: (datasource_id, status)
INDEX: (metadata_snapshot_id)
INDEX: (source_type, source_id)

---

## Chunk 结构 (内存模型)

### SchemaChunk (表级)

```
表名: orders
表注释: 订单主表
字段列表:
  - order_id (BIGINT, PK) 订单ID [可信度:95]
  - user_id (BIGINT, FK→users.id) 用户ID [可信度:90]
  - pay_amount (DECIMAL(10,2)) 实付金额 [可信度:95]
  - status (TINYINT) 订单状态 1=待付款 2=已付款 3=已完成 [可信度:88]
索引: PRIMARY(order_id), idx_user_id(user_id), idx_status(status)
外键: user_id → users.id
```

### KnowledgeChunk (skills.md 段落)

```
## 核心表说明

orders 表是订单主表，记录所有交易订单。
核心字段: order_id, user_id, pay_amount, status
常用查询维度: 按时间、按用户、按状态
注意: old_amount 字段已废弃，请使用 pay_amount
```

## Retrieval Result Structure

```python
@dataclass
class RetrievedContext:
    table_name: str
    table_comment: str
    columns: list[ColumnInfo]
    relevance_score: float      # Milvus 原始分数
    weighted_score: float       # 规则加权后分数
    source_type: str            # SCHEMA / KNOWLEDGE
    chunk_text: str             # 原文
    trust_score: int            # 表级平均可信度
    snapshot_id: int
    degraded: bool = False      # 是否降级结果
```
