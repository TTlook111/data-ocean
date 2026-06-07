# Research: Schema RAG 召回模块

## 向量库选型

**Decision**: Milvus 2.x Standalone (Docker)

**Rationale**: 开源、支持 metadata filtering、Python SDK 成熟、LangChain 原生集成。Standalone 模式适合 MVP 单机部署。

**Alternatives considered**:
- Qdrant: 轻量但 LangChain 集成不如 Milvus 成熟
- Weaviate: 功能全面但部署复杂
- pgvector: 性能不足以支撑大规模向量检索
- OpenSearch: 过重，阶段二 Hybrid Search 再评估

## Embedding 模型

**Decision**: text-embedding-v4 (阿里云 DashScope API)，默认 1024 维

**Rationale**: 中文语义理解能力强，与 Qwen 生态一致，API 调用简单。1024 维在精度和存储间平衡。

**维度配置**: 通过 EMBEDDING_DIMENSION 环境变量控制，支持 1024/1536/2048，但切换需重建 Collection。

**Alternatives considered**:
- BGE-M3: 开源但需自部署 GPU，MVP 阶段成本高
- OpenAI text-embedding-3: 英文优先，中文效果不如 DashScope
- 本地 sentence-transformers: 需 GPU，部署复杂

## Chunking 策略

**Decision**: 分层切分策略

**规则**:
1. **元数据 Schema**: 表级切分（一张表一个 chunk），包含表名、注释、全部字段信息
2. **大表处理**: 单表字段 > 50 时，按字段组切分（每组 20-30 字段）
3. **skills.md**: 由 Python RAG 服务按二级标题 (##) 和三级标题 (###) 细粒度切分

**Rationale**: 表级切分保证语义完整性，大表拆分避免单 chunk 过长影响检索精度。

## 检索策略 (MVP)

**Decision**: 纯向量语义检索 + 规则加权重排

**流程**:
1. Query embedding → Milvus ANN search (top_k=20)
2. 强制 metadata_filter: datasource_id + review_status=APPROVED + governance_status in [NORMAL, RECOMMENDED]
3. 规则加权重排: 表名命中 +0.3, 高可信字段命中 +0.2, 废弃字段 -0.5
4. 返回 Top 5-10

**Alternatives considered**:
- Hybrid Search (BM25 + Dense): 阶段二引入，需要额外索引
- qwen3-rerank: 阶段二引入，增加延迟

## Collection Schema 设计

**Decision**: 单 Collection `schema_vectors`，通过 metadata 字段区分数据源和内容类型

**Fields**:
- id (INT64, PK, auto_id)
- vector (FLOAT_VECTOR, dim=1024)
- datasource_id (INT64) — partition key
- chunk_type (VARCHAR) — SCHEMA / KNOWLEDGE
- source_id (INT64) — 来源记录ID
- metadata_snapshot_id (INT64)
- knowledge_version_no (INT32)
- table_name (VARCHAR)
- governance_status (VARCHAR)
- review_status (VARCHAR)
- chunk_text (VARCHAR) — 原文存储，用于返回

**Partition**: 按 datasource_id 分区，加速单数据源检索

## 降级方案

**Decision**: Milvus 不可用时，从 skills.md 中提取核心表前 5 张作为兜底上下文

**实现**: 
1. 健康检查: 每次检索前 ping Milvus (timeout 2s)
2. 降级触发: 连接失败或超时
3. 兜底数据: 从 knowledge_chunk 表查询 chunk_type=TABLE_DESC 的前 5 条
4. 降级标记: 返回结果中标记 `degraded=true`，Agent 生成 SQL 时降低置信度

## 版本切换策略

**Decision**: 新版本向量化完成后，通过 metadata_snapshot_id 过滤实现无缝切换

**流程**:
1. 新版本向量写入 Milvus（新 snapshot_id）
2. 一致性检查: 新版本 chunk 数量 >= 旧版本 80%
3. 更新 datasource 表的 active_snapshot_id
4. 旧版本向量延迟 24h 后清理（防止切换期间查询中断）
