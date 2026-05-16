# Data Model: skills.md 业务知识库模块

## Entity Relationship

```
knowledge_doc (1) ──< knowledge_doc_version (N)
knowledge_doc_version (1) ──< knowledge_chunk (N)
knowledge_doc_version (1) ──< knowledge_review_task (N)
knowledge_doc (1) ──< vector_index_task (N)
knowledge_doc.datasource_id ──> datasource.id
knowledge_doc_version.metadata_snapshot_id ──> metadata_snapshot.id
```

## Tables

### knowledge_doc

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 文档ID |
| datasource_id | BIGINT | FK, NOT NULL | 所属数据源 |
| title | VARCHAR(200) | NOT NULL | 文档标题 |
| current_version_no | INT | NOT NULL, DEFAULT 0 | 当前生效版本号 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'DRAFT' | DRAFT/PENDING_REVIEW/APPROVED/PUBLISHED/DEPRECATED |
| updated_by | BIGINT | | 最后编辑人 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT NOW() ON UPDATE | 更新时间 |
| deleted | TINYINT | NOT NULL, DEFAULT 0 | 逻辑删除 |

UNIQUE INDEX: (datasource_id, deleted) — 每个数据源仅一份 skills.md

### knowledge_doc_version

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 版本ID |
| doc_id | BIGINT | FK → knowledge_doc.id, NOT NULL | 所属文档 |
| datasource_id | BIGINT | NOT NULL | 冗余数据源ID，便于查询 |
| metadata_snapshot_id | BIGINT | FK, NOT NULL | 绑定的元数据快照 |
| version_no | INT | NOT NULL | 版本号（递增） |
| content | MEDIUMTEXT | NOT NULL | Markdown 全文内容 |
| generation_source | VARCHAR(20) | NOT NULL | AI_GENERATED / MANUAL / ROLLBACK |
| review_status | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING/APPROVED/REJECTED |
| reviewer_id | BIGINT | | 审核人 |
| change_summary | VARCHAR(500) | | 变更摘要 |
| created_by | BIGINT | NOT NULL | 创建人 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| version | INT | NOT NULL, DEFAULT 1 | 乐观锁版本 |

UNIQUE INDEX: (doc_id, version_no)

### knowledge_chunk

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 切片ID |
| doc_id | BIGINT | FK → knowledge_doc.id, NOT NULL | 所属文档 |
| version_no | INT | NOT NULL | 所属版本号 |
| metadata_snapshot_id | BIGINT | NOT NULL | 绑定快照 |
| chunk_type | VARCHAR(30) | NOT NULL | CORE_TABLE/JOIN_PATH/METRIC/FIELD_NOTE/CUSTOM |
| chunk_text | TEXT | NOT NULL | 切片文本内容 |
| chunk_order | INT | NOT NULL | 切片顺序 |
| related_table | VARCHAR(100) | | 关联表名 |
| related_column | VARCHAR(100) | | 关联字段名 |
| review_status | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING/APPROVED |
| vector_status | VARCHAR(20) | NOT NULL, DEFAULT 'NOT_INDEXED' | NOT_INDEXED/INDEXED/FAILED |
| vector_id | VARCHAR(100) | | Milvus 向量ID |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

INDEX: (doc_id, version_no)
INDEX: (vector_status)

### knowledge_review_task

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 任务ID |
| doc_version_id | BIGINT | FK → knowledge_doc_version.id, NOT NULL | 关联版本 |
| reviewer_id | BIGINT | FK → sys_user.id | 审核人 |
| review_status | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING/APPROVED/REJECTED |
| review_comment | TEXT | | 审核意见 |
| submitted_at | DATETIME | NOT NULL | 提交时间 |
| reviewed_at | DATETIME | | 审核时间 |

### vector_index_task

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | BIGINT | PK, AUTO_INCREMENT | 任务ID |
| datasource_id | BIGINT | NOT NULL | 数据源ID |
| target_type | VARCHAR(30) | NOT NULL | KNOWLEDGE_DOC / METADATA_SCHEMA |
| target_id | BIGINT | NOT NULL | 目标记录ID |
| target_version_no | INT | | 目标版本号 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING/PROCESSING/COMPLETED/FAILED |
| retry_count | INT | NOT NULL, DEFAULT 0 | 重试次数 |
| max_retry | INT | NOT NULL, DEFAULT 3 | 最大重试 |
| started_at | DATETIME | | 开始时间 |
| finished_at | DATETIME | | 完成时间 |
| error_message | TEXT | | 错误信息 |
| created_at | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

INDEX: (status, created_at) — 定时任务扫描用

## State Transitions

### Document Status

```
DRAFT ──[提交审核]──> PENDING_REVIEW
PENDING_REVIEW ──[审核通过]──> APPROVED
PENDING_REVIEW ──[审核拒绝]──> DRAFT (退回修改)
APPROVED ──[发布]──> PUBLISHED
PUBLISHED ──[废弃]──> DEPRECATED
PUBLISHED ──[新版本编辑]──> DRAFT (创建新版本)
```

### Vector Index Task Status

```
PENDING ──[定时任务拾取]──> PROCESSING
PROCESSING ──[成功]──> COMPLETED
PROCESSING ──[失败且 retry < max]──> PENDING (等待重试)
PROCESSING ──[失败且 retry >= max]──> FAILED
```
