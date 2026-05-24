# Implementation Plan: Schema RAG 召回模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

Schema RAG 模块是 Python AI 服务的核心组件，负责将已治理的元数据和已发布的 skills.md 向量化写入 Milvus，并在用户提问时精准召回相关表和字段作为 SQL 生成上下文。使用 LlamaIndex 封装向量检索逻辑，强制数据源隔离和准入过滤。

## Technical Context

**Language/Version**: Python 3.13 (FastAPI)

**Primary Dependencies**:
- LlamaIndex (core + milvus integration)
- pymilvus (Milvus Python SDK)
- dashscope (text-embedding-v4 API)
- FastAPI + Pydantic v2

**Storage**: Milvus 2.x (向量库), MySQL 8 (vector_index_item 映射表, 由 Java 管理)

**Testing**: pytest + pytest-asyncio, Milvus testcontainer

**Target Platform**: Docker Compose (python-service container)

**Performance Goals**: 单次召回 < 2s, 向量化单表 < 5s

**Constraints**: 强制 datasource_id 隔离, 仅召回 APPROVED + NORMAL/RECOMMENDED 内容

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | ✅ PASS | 仅向量化已治理通过的内容 |
| II. SQL 安全与只读执行 | N/A | 本模块不执行 SQL |
| III. 三层分离架构 | ✅ PASS | Python 内部服务，Java 通过内部 API 调用 |
| IV. RAG 准入控制 | ✅ PASS | metadata_filter 强制 APPROVED + NORMAL/RECOMMENDED |
| V. 可信度驱动生成 | ✅ PASS | 向量 metadata 携带可信度，重排时加权 |
| VI. 渐进式 MVP | ✅ PASS | MVP 仅向量语义检索，Hybrid Search 阶段二 |

**Gate Result**: PASS

## Project Structure

```text
python-service/dataocean/rag/
├── __init__.py
├── router.py              # FastAPI 路由
├── service.py             # RAG 业务逻辑
├── retriever.py           # LlamaIndex 检索器封装
├── vectorizer.py          # 向量化写入逻辑
├── chunker.py             # 分块策略
├── embedder.py            # Embedding 调用封装
├── reranker.py            # 规则加权重排
├── schema.py              # Pydantic 请求/响应模型
├── milvus_client.py       # Milvus 连接管理
├── fallback.py            # 降级方案
└── config.py              # RAG 配置
```

## Implementation Phases

### Phase 1: Milvus 基础设施
- Milvus Docker 配置 (docker-compose.yml)
- Collection schema 定义和初始化脚本
- pymilvus 连接管理 (连接池, 健康检查)
- Embedding 调用封装 (dashscope text-embedding-v4)

### Phase 2: 向量化写入
- POST /internal/rag/vectorize 接口
- 分块策略实现 (表级 / 字段组 / skills.md 标题)
- 批量 embedding + Milvus insert
- 版本切换逻辑 (新旧版本共存 → 一致性检查 → 切换)

### Phase 3: 语义检索
- POST /internal/rag/retrieve 接口
- LlamaIndex VectorStoreIndex 封装
- 强制 metadata_filter 注入
- 规则加权重排 (表名命中, 可信度加权, 废弃惩罚)
- 返回 Top 5-10 结果

### Phase 4: 降级与运维
- Milvus 健康检查 + 降级触发
- 兜底方案: 从 knowledge_chunk 读取核心表
- 旧版本向量延迟清理
- 按 datasource_id 批量删除

### Phase 5: skills.md 版本替换闭环
- Java vector_index_task 携带 `docId`、`metadataSnapshotId`、`knowledgeVersionNo`、`previousVersionNo`
- `/internal/rag/vectorize` 接收文档版本上下文，写入新版本向量时写入 `doc_id` 和 `source_id`
- 新版本写入成功并校验数量后，删除同一文档的上一版向量
- 若新版本写入失败或数量不一致，清理本次半成品向量但不删除旧版本向量，由 Java 将任务标记 FAILED
- `force=true` 仅清理同一 doc 的向量；缺少 doc_id 时才退化为 datasource 级重建

## Complexity Tracking

无违规项。
