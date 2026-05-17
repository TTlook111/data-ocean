# Implementation Plan: skills.md 业务知识库模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

skills.md 模块是元数据治理的发布形态，Java 层管理文档 CRUD、版本控制、审核流程，Python 层负责基于元数据快照调用 LLM 生成草稿。发布后触发异步向量化任务，将知识切片写入 Milvus 供 RAG 召回。

## Technical Context

**Language/Version**: Java 17 (Spring Boot 3.x) + Python 3.13 (FastAPI)

**Primary Dependencies**:
- Java: Spring Boot 3.x, MyBatis-Plus, Flyway, OpenFeign (调 Python)
- Python: FastAPI, Qwen API (dashscope SDK), Jinja2 (Prompt 模板)

**Storage**: MySQL 8 (平台管理库: knowledge_doc, knowledge_doc_version, knowledge_chunk, knowledge_review_task, vector_index_task)

**Testing**: JUnit 5 + MockMvc (Java), pytest + httpx (Python)

**Target Platform**: Docker Compose

**Performance Goals**: 草稿生成 < 60s, 向量化任务 5 分钟内完成

**Constraints**: 乐观锁防并发编辑, 发布前校验引用完整性

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. 元数据治理优先 | ✅ PASS | skills.md 绑定 metadata_snapshot_id，是治理结果的发布形态 |
| II. SQL 安全与只读执行 | N/A | 本模块不执行业务 SQL |
| III. 三层分离架构 | ✅ PASS | Java 管理 CRUD + 审核，Python 仅生成草稿 |
| IV. RAG 准入控制 | ✅ PASS | 仅 APPROVED + PUBLISHED 内容触发向量化 |
| V. 可信度驱动生成 | ✅ PASS | 草稿生成时注入字段可信度信息 |
| VI. 渐进式 MVP | ✅ PASS | MVP 仅支持单数据源 skills.md，不做跨源合并 |

**Gate Result**: PASS

## Project Structure

### Java (backend)

```text
backend/src/main/java/com/dataocean/module/knowledge/
├── controller/
│   └── KnowledgeDocController.java
├── service/
│   ├── KnowledgeDocService.java
│   ├── KnowledgeVersionService.java
│   ├── KnowledgeReviewService.java
│   ├── KnowledgeChunkService.java
│   └── VectorIndexTaskService.java
├── mapper/
│   ├── KnowledgeDocMapper.java
│   ├── KnowledgeDocVersionMapper.java
│   ├── KnowledgeChunkMapper.java
│   ├── KnowledgeReviewTaskMapper.java
│   └── VectorIndexTaskMapper.java
├── entity/
│   ├── KnowledgeDoc.java
│   ├── KnowledgeDocVersion.java
│   ├── KnowledgeChunk.java
│   ├── KnowledgeReviewTask.java
│   └── VectorIndexTask.java
├── dto/
│   ├── KnowledgeDocVO.java
│   ├── KnowledgeVersionVO.java
│   ├── GenerateDraftRequest.java
│   ├── PublishRequest.java
│   └── ReviewRequest.java
├── enums/
│   ├── DocStatus.java
│   └── ReviewStatus.java
└── feign/
    └── PythonKnowledgeClient.java
```

### Python (python-service)

```text
python-service/dataocean/knowledge/
├── router.py
├── service.py
├── schema.py
└── prompts/
    └── skills_md_template.j2
```

### Database Migration

```text
backend/src/main/resources/db/migration/
└── V6__create_knowledge_tables.sql
```

## Implementation Phases

### Phase 1: Java CRUD + 状态流转
- knowledge_doc / knowledge_doc_version 表 + Flyway 迁移
- 文档列表、详情、编辑接口
- 状态机: DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED → DEPRECATED
- 乐观锁 (version 字段)

### Phase 2: 审核流程
- knowledge_review_task 表
- 提交审核、审核通过/拒绝接口
- 发布前校验: 引用表字段存在性 + governance_status 检查

### Phase 3: AI 草稿生成 (Python)
- Python 端 POST /internal/knowledge/generate-draft
- 读取快照数据，填充 Prompt 模板，调用 Qwen API
- Java 通过 OpenFeign 调用，结果写入 knowledge_doc_version

### Phase 4: 向量化触发
- vector_index_task 表
- 发布时创建向量化任务 (status=PENDING)
- 定时任务每 5 分钟扫描 PENDING 任务，调用 007 模块向量化接口
- 任务状态: PENDING → PROCESSING → COMPLETED / FAILED

## Complexity Tracking

无违规项。
