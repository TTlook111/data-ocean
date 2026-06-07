# Tasks: Schema RAG 召回模块

**Input**: Design documents from `specs/007-schema-rag/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [X] T001 在 `docker-compose.yml` 中添加 Milvus 2.x Standalone 服务配置（含 etcd、minio 依赖），锁定具体镜像版本，配置持久化卷
- [X] T002 创建 Python 包结构 `python-service/dataocean/rag/`，包含 `__init__.py`、`router.py`、`service.py`、`retriever.py`、`vectorizer.py`、`chunker.py`、`embedder.py`、`reranker.py`、`schema.py`、`milvus_client.py`、`fallback.py`、`config.py`
- [X] T003 实现 RAG 配置 `python-service/dataocean/rag/config.py`，从环境变量读取：MILVUS_HOST、MILVUS_PORT、EMBEDDING_MODEL（默认 text-embedding-v4）、EMBEDDING_DIMENSION（默认 1024）、RAG_TOP_K（默认 10）、SIMILARITY_THRESHOLD（默认 0.6）

## Phase 2: Foundational — Milvus 基础设施

- [X] T004 [P] 实现 `python-service/dataocean/rag/milvus_client.py`：pymilvus 连接管理（连接池、健康检查 ping 方法、自动重连），提供 get_collection 方法
- [X] T005 [P] 实现 `python-service/dataocean/rag/embedder.py`：封装 dashscope text-embedding-v4 调用，支持单条和批量 embedding（batch_size=25），返回 List[List[float]]，包含重试逻辑（最多 3 次，指数退避）
- [X] T006 创建 Milvus Collection 初始化脚本 `python-service/dataocean/rag/init_collection.py`：定义 schema_knowledge Collection（字段：vector_id、datasource_id、snapshot_id、knowledge_version_no、doc_id、source_id、chunk_type、governance_status、review_status、chunk_text、related_table、related_column、embedding），创建 IVF_FLAT 索引，metric_type=IP

## Phase 3: User Story 2 (P1) — 向量化写入

**Goal**: 已发布内容向量化入库
**Independent Test**: 发布 skills.md 后，5 分钟内新内容能被召回

- [X] T007 [US2] 实现 `python-service/dataocean/rag/chunker.py`：分块策略——表级分块（表数量<100 时每表一个 chunk）、字段组分块（单表字段>50 时按 10 字段一组）、skills.md 按 `##` + `###` 细粒度切分；每个 chunk 携带 metadata（datasource_id、snapshot_id、chunk_type、related_table、related_column）
- [X] T008 [US2] 实现 `python-service/dataocean/rag/vectorizer.py`：接收 chunks 列表 → 调用 embedder 批量生成向量 → 写入 Milvus（upsert 模式）→ 返回写入数量和失败列表
- [X] T009 [US2] 实现版本切换逻辑：在 vectorizer.py 中实现 switch_version 方法——新版本写入完成后，校验向量数量一致性（新版本 chunk 数 == Milvus 中新版本记录数），通过后删除旧版本向量
- [X] T010 [US2] 实现 Pydantic 模型 `python-service/dataocean/rag/schema.py`：VectorizeRequest（datasource_id、snapshot_id、version_no、chunks: List[ChunkItem]）、VectorizeResponse（success_count、failed_count、errors）、ChunkItem（chunk_type、chunk_text、related_table、related_column、governance_status、review_status）
- [X] T011 [US2] 实现路由 `python-service/dataocean/rag/router.py` 中 POST /internal/rag/vectorize 接口：接收 VectorizeRequest → 调用 chunker 分块 → 调用 vectorizer 写入 → 返回 VectorizeResponse

## Phase 4: User Story 1 (P1) — 语义检索

**Goal**: 用户提问时系统精准召回相关表
**Independent Test**: 用户问"上月退款金额"时，召回结果包含 refund_record 表和 actual_refund 字段

- [X] T012 [US1] 实现 `python-service/dataocean/rag/retriever.py`：使用 LangChain Milvus VectorStore 封装 Milvus 检索，调用 similarity_search 时强制注入 metadata_filter（datasource_id={当前数据源} AND review_status=APPROVED AND governance_status IN [NORMAL, RECOMMENDED]）
- [X] T013 [US1] 实现 `python-service/dataocean/rag/reranker.py`：规则加权重排逻辑——基础分为 Milvus 相似度分数，加权规则：表名命中用户问题关键词 +0.2、字段可信度>80 的 +0.1、governance_status=RECOMMENDED +0.05、废弃字段 -0.5；按加权后分数降序取 Top K
- [X] T014 [US1] 实现检索 schema 模型：RetrieveRequest（datasource_id、question、top_k、confidence_scores: Optional[Dict]）、RetrieveResponse（results: List[RetrievedSchema]）、RetrievedSchema（table_name、columns、score、chunk_type、source_version、join_paths）
- [X] T015 [US1] 实现路由中 POST /internal/rag/retrieve 接口：接收 RetrieveRequest → 调用 embedder 生成问题向量 → 调用 retriever 检索 → 调用 reranker 重排 → 过滤低于 SIMILARITY_THRESHOLD 的结果 → 返回 RetrieveResponse
- [X] T016 [US1] 实现 `python-service/dataocean/rag/service.py`：编排 retrieve 流程，包含相似度阈值检查（全部低于阈值时返回空结果 + 提示信息"未找到相关数据表，请换个问法"）

## Phase 5: User Story 3 (P1) — 数据源隔离与准入过滤

**Goal**: RAG 检索时强制按数据源隔离
**Independent Test**: 用户选择数据源 A 时，绝不会召回数据源 B 的内容

- [X] T017 [US3] 在 retriever.py 的 metadata_filter 构建中确保 datasource_id 为必填参数，缺失时抛出 ValueError 而非默认查全部
- [X] T018 [US3] 实现按 datasource_id 批量删除向量接口：在 router.py 中添加 DELETE /internal/rag/vectors/{datasource_id}，调用 Milvus delete by expr（datasource_id == {id}）

## Phase 6: 降级与运维

- [X] T019 实现 `python-service/dataocean/rag/fallback.py`：Milvus 健康检查失败时的降级方案——从 Java 传入的 knowledge_chunk 中读取 chunk_type=TABLE_DESC 的前 5 条作为兜底召回结果
- [X] T020 在 retriever.py 中添加 try/except 包裹 Milvus 调用，捕获连接异常时自动切换到 fallback 方案，并在响应中标记 degraded=true
- [X] T021 实现旧版本向量延迟清理：在 vectorizer.py 中添加 cleanup_old_versions 方法，删除 snapshot_id 不等于当前生效版本的向量记录（在新版本切换成功 10 分钟后执行）

## Phase 7: Polish & Cross-Cutting

- [X] T022 在 config.py 中添加所有可配置项的环境变量文档注释，并在 `python-service/.env.example` 中列出所有 RAG 相关环境变量
- [X] T023 在 router.py 中添加 GET /internal/rag/health 健康检查接口，返回 Milvus 连接状态和 Collection 统计信息

## Phase 8: Manual Re-vectorize

- [X] T024 在 Python 路由中确保 POST /internal/rag/vectorize 支持 force=true 参数（强制全量重建，忽略增量逻辑）
- [X] T025 [Frontend] 在数据源详情页或 skills.md 管理页添加"重新向量化"按钮，调用 Java → Python 触发全量重建

## Phase 9: Versioned skills.md Replacement

- [X] T026 在 VectorizeRequest 中支持 docId 和 previousVersionNo，ChunkItem.sourceId 对应 knowledge_chunk.id
- [X] T027 在 Milvus schema 中增加 doc_id 和 source_id，用于按文档版本精确删除旧向量
- [X] T028 在 vectorizer.py 中实现文档级版本替换：新版本数量校验通过后删除同 doc 的 previousVersionNo
- [X] T029 扩展 DELETE /internal/rag/vectors 支持 datasourceId + docId + knowledgeVersionNo 精确删除

## Dependencies

```
T001 → T004, T006
T002 → T004~T006
T005 + T006 → T007~T011
T004 + T005 → T012~T016
T012 → T017~T018
T004 → T019~T021
T015 → T019~T020 (降级依赖主流程)
```

## Implementation Strategy

MVP-first approach:
1. 先搭建 Milvus 基础设施和 Embedding 封装（Phase 2），确保向量库可用
2. 实现向量化写入（Phase 3），打通 skills.md 发布 → 向量入库链路
3. 实现语义检索（Phase 4），这是 008 NL2SQL Agent 的前置依赖
4. 数据源隔离（Phase 5）与检索同步实现，确保安全性
5. 降级方案（Phase 6）最后补充，确保生产可用性
