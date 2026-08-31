# Implementation Plan: Schema RAG 召回模块

**Branch**: `001-full-module-specs` | **Date**: 2026-08-31 | **Spec**: [spec.md](spec.md)

## Summary

Schema RAG 模块是 Python AI 服务的核心组件，负责将已治理的元数据和已发布的 skills.md 向量化写入 Milvus，并在用户提问时精准召回相关表和字段作为 SQL 生成上下文。使用 LangChain 封装向量检索逻辑，强制数据源隔离和准入过滤。

## Technical Context

**Language/Version**: Python 3.13 (FastAPI)

**Primary Dependencies**:
- LangChain (langchain-openai + langchain-text-splitters；Milvus 检索使用 pymilvus `MilvusClient`)
- pymilvus (Milvus Python SDK)
- tiktoken（本地切分预算器；不代表 Qwen tokenizer 的精确计费）
- 外部 OpenAI 兼容 Embedding API（当前由 Qwen 配置提供）
- FastAPI + Pydantic v2

**Storage**: Milvus 2.x (可重建向量索引), Java MySQL 8 (knowledge_chunk 快照和发布状态)

**Testing**: pytest + pytest-asyncio；真实 Milvus 重建和检索需要外部环境验证

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
├── retriever.py           # LangChain 检索器封装
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

### Phase 2: 向量化写入（已完成，2026-08-31）
- POST /internal/rag/vectorize 接口
- 分块策略实现（按 `##`/`###` 语义单元，长单元 token-aware 切分）
- 批量 embedding + Milvus insert
- 版本切换逻辑（新旧版本共存 → 一致性检查 → 切换）
- chunk metadata（文档版本、顺序、语义分组、多表/多字段、实体 ID、可信度、hash）

### Phase 3: 语义检索（已完成基础修复，2026-08-31）
- POST /internal/rag/retrieve 接口
- pymilvus `MilvusClient` 检索封装（保持 LangChain `Document`/重排协议）
- 强制 metadata_filter 注入
- 规则加权重排 (表名命中, 可信度加权, 废弃惩罚)
- 返回 Top 5-10 结果，并按文档版本/语义分组扩展相邻 chunk

### Phase 4: 降级与运维（基础闭环已完成）
- Milvus 健康检查 + 降级触发
- 兜底方案：Java 按 active snapshot 读取已索引 chunk，按问题做确定性排序；缓存键包含问题摘要
- 旧版本向量在 Java 发布事务提交后清理，清理失败进入 `CLEANUP_PENDING` 并由调度器重试
- 按 datasource_id 批量删除

### Phase 5: skills.md 版本替换闭环（已完成基础安全语义）
- Java vector_index_task 携带 `docId`、`metadataSnapshotId`、`knowledgeVersionNo`、`previousVersionNo`
- `/internal/rag/vectorize` 接收文档版本上下文，写入新版本向量时写入 `doc_id` 和 `source_id`
- 新版本写入成功并校验数量后，Java 先提交 chunk、文档和任务状态，再删除同一文档的上一版向量
- 若发布事务失败，清理本次新版本向量但不删除旧版本向量，由 Java 将任务标记 FAILED
- 若发布事务已提交但旧版本清理失败，任务进入 `CLEANUP_PENDING`，只重试旧版本清理，不重复向量化或回滚新版本
- `force=true` 仅清理同一 doc 的向量；缺少 `doc_id` 时拒绝危险的先删后写操作

## Current Implementation Notes

- `chunker.py` 的目标为约 900 token，最大 1000 token，长语义单元 overlap 约 150 token；短但有意义的单元不强行填充或丢弃。
- `tiktoken/cl100k_base` 只用于本地切分预算，Embedding 仍调用外部 Qwen/OpenAI 兼容 API；若未来服务端提供 Qwen tokenizer，应替换预算器并补充回归测试。
- `knowledge_chunk` 的新增字段由 `V50__add_rag_chunk_context_metadata.sql` 提供。迁移只应由部署流程执行，本地开发不自动修改数据库。
- Milvus 新建 Collection 使用 `embedding` 向量字段和自动 ID；已有旧 schema（例如向量字段为 `vector`）会被拒绝，必须通过重建/迁移完成一次性切换，禁止混写。
- 关系增强已改为从召回结果的 `entity_ids` 批量读取 Java 关系；当前只有带实体 ID 的已审核 chunk 才会触发关系增强。
- `/internal/rag/re-vectorize` 仍不是完整任务入口，当前应使用 Java `vector_index_task` 发布/重建闭环。

## Complexity Tracking

无违规项。
