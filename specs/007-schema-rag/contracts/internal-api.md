# Internal API Contracts: Schema RAG 召回模块

## Base URL

Python 内部服务: `/internal/rag/*`

仅 Java 网关层通过 OpenFeign 调用，不对外暴露。

---

## POST /internal/rag/retrieve

语义检索，返回与用户问题最相关的表和字段上下文。

**Request**:
```json
{
  "datasourceId": 10,
  "query": "上月华东区退款金额",
  "topK": 10,
  "minScore": 0.5,
  "activeSnapshotId": 5
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| datasourceId | int | yes | 数据源ID，强制隔离 |
| query | string | yes | 用户自然语言问题 |
| topK | int | no | 返回数量，默认 10，最大 20 |
| minScore | float | no | 最低相似度阈值，默认 0.5 |
| activeSnapshotId | int | yes | 当前生效快照ID |

**Response 200**:
```json
{
  "contexts": [
    {
      "tableName": "refund_record",
      "tableComment": "退款记录表",
      "columns": [
        {
          "name": "actual_refund",
          "type": "DECIMAL(10,2)",
          "comment": "实际退款金额",
          "trustScore": 92
        },
        {
          "name": "region",
          "type": "VARCHAR(50)",
          "comment": "区域",
          "trustScore": 88
        }
      ],
      "relevanceScore": 0.89,
      "weightedScore": 0.94,
      "sourceType": "SCHEMA",
      "chunkText": "refund_record 退款记录表...",
      "snapshotId": 5
    }
  ],
  "totalFound": 15,
  "returned": 10,
  "degraded": false,
  "retrievalTimeMs": 320
}
```

**Response 200 (降级)**:
```json
{
  "contexts": [...],
  "totalFound": 5,
  "returned": 5,
  "degraded": true,
  "degradeReason": "Milvus 连接超时，使用核心表兜底",
  "retrievalTimeMs": 50
}
```

**Response 400** (数据源无向量数据):
```json
{
  "error": "NO_VECTOR_DATA",
  "message": "该数据源尚未完成知识准备，请先发布 skills.md"
}
```

---

## POST /internal/rag/vectorize

将内容向量化写入 Milvus。由 Java 定时任务调用。

**Request**:
```json
{
  "datasourceId": 10,
  "taskId": 100,
  "targetType": "KNOWLEDGE_DOC",
  "metadataSnapshotId": 5,
  "knowledgeVersionNo": 3,
  "chunks": [
    {
      "sourceId": 201,
      "chunkType": "CORE_TABLE",
      "chunkText": "## 核心表说明\n\norders 表是订单主表...",
      "tableName": "orders",
      "governanceStatus": "NORMAL",
      "reviewStatus": "APPROVED",
      "trustScore": 90
    }
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| datasourceId | int | yes | 数据源ID |
| taskId | int | yes | vector_index_task.id，用于回调状态 |
| targetType | string | yes | KNOWLEDGE_DOC / METADATA_SCHEMA |
| metadataSnapshotId | int | yes | 快照ID |
| knowledgeVersionNo | int | no | 知识版本号 |
| chunks | array | yes | 待向量化的切片列表 |

**Response 200**:
```json
{
  "taskId": 100,
  "status": "COMPLETED",
  "vectorizedCount": 15,
  "failedCount": 0,
  "vectorIds": [1001, 1002, 1003],
  "durationMs": 4500
}
```

**Response 200 (部分失败)**:
```json
{
  "taskId": 100,
  "status": "PARTIAL_FAILED",
  "vectorizedCount": 12,
  "failedCount": 3,
  "failedChunks": [
    { "sourceId": 205, "error": "Embedding API timeout" }
  ],
  "vectorIds": [1001, 1002],
  "durationMs": 8000
}
```

---

## DELETE /internal/rag/vectors

按条件批量删除向量。数据源禁用/删除时调用。

**Request**:
```json
{
  "datasourceId": 10,
  "snapshotId": 3
}
```

至少提供一个过滤条件。snapshotId 可选，不传则删除该数据源全部向量。

**Response 200**:
```json
{
  "deletedCount": 150,
  "durationMs": 200
}
```

---

## GET /internal/rag/health

Milvus 健康检查。

**Response 200**:
```json
{
  "status": "healthy",
  "milvusConnected": true,
  "collectionExists": true,
  "totalVectors": 5000
}
```

**Response 200 (不健康)**:
```json
{
  "status": "unhealthy",
  "milvusConnected": false,
  "error": "Connection refused"
}
```
