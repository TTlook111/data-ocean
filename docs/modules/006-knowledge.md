# 006 skills.md 业务知识库

## 概览

知识库模块是元数据治理的发布形态。Java 负责 skills.md 文档 CRUD、审核、版本、发布和向量化任务状态；Python RAG 服务负责 skills.md 切割、embedding、Milvus 写入、检索和重排。

发布不是直接生效动作。发布后文档进入 `INDEXING`，调度器调用 Python 完成切割和新版本向量写入，校验成功后才将文档标记为 `PUBLISHED`，再清理旧版本向量。失败时继续保留旧版本可检索。

## 生命周期

```text
DRAFT -> PENDING_REVIEW -> APPROVED -> INDEXING -> PUBLISHED
                                           |
                                           v
                                      APPROVED
                                    (索引失败回退)
```

## 职责边界

- Java:
  - 管理 `knowledge_doc`、`knowledge_doc_version`、`knowledge_chunk`、`vector_index_task`
  - 审核和发布前治理校验
  - 创建向量化任务
  - 保存 Python 返回的 chunk snapshot，便于预览、审计、重建和排障
  - 在新版本向量验证成功后切换文档状态，并触发旧向量清理
- Python:
  - `/internal/rag/chunk` 按 RAG 策略切割完整 skills.md
  - `/internal/rag/vectorize` 批量 embedding 并写入 Milvus
  - 校验新版本 Milvus 记录数与 chunk 数一致
  - `/internal/rag/retrieve` 检索和重排

## 切割策略

Python `dataocean.rag.chunker` 执行切割：

1. 先按 `##` 二级标题划分章节。
2. 跳过“文档来源”等无检索价值章节。
3. 每个章节再按 `###` 三级标题细切。
4. 每张表、每个指标、每对 Join Path、每个字段防坑、每个典型查询场景尽量成为独立 chunk。
5. chunk 携带 `chunkType`、`chunkText`、`tableName`、`relatedColumn`、`governanceStatus`、`reviewStatus`。

Java 不再实现切割规则，避免与 Python RAG 策略漂移。

## 发布与向量切换

```text
管理员点击发布
  -> Java 校验文档状态和治理约束
  -> Java 将文档置为 INDEXING 并创建 vector_index_task
  -> 调度器调用 Python /internal/rag/chunk
  -> Java 保存 knowledge_chunk 快照，状态 PENDING
  -> 调度器调用 Python /internal/rag/vectorize
  -> Python 写入新版本向量并校验数量
  -> Java 将新 chunk 置为 INDEXED，旧 chunk 置为 SUPERSEDED
  -> Java 将文档置为 PUBLISHED，任务置为 COMPLETED
  -> Java 调用 Python 清理 previousVersionNo 的旧 Milvus 向量
```

## 代码位置

- Java 文档服务: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocServiceImpl.java`
- Java 任务调度: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/scheduler/VectorIndexTaskScheduler.java`
- Java RAG 客户端: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/client/impl/PythonRagClientImpl.java`
- Python chunker: `python-service/dataocean/rag/chunker.py`
- Python RAG 路由: `python-service/dataocean/rag/router.py`
- Python vectorizer: `python-service/dataocean/rag/vectorizer.py`
- 数据库迁移: `backend/DataOcean/src/main/resources/db/migration/V35__rag_python_chunking_lifecycle.sql`

## 关键表

- `knowledge_doc`: 文档主表，`status` 包含 `INDEXING`
- `knowledge_doc_version`: 文档版本和依赖快照
- `knowledge_chunk`: Python 切割结果的 MySQL 快照
- `vector_index_task`: Java 管理的异步向量化任务

## 内部接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/rag/chunk` | Python 切割完整 skills.md，返回 chunk 清单 |
| POST | `/internal/rag/vectorize` | 写入新版本向量并校验数量 |
| POST | `/internal/rag/vectors/delete` | 新版本生效后清理旧版本向量 |
| POST | `/internal/rag/retrieve` | RAG 检索和重排 |
