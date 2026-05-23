# 006 skills.md 业务知识库

## 概述
知识库模块是元数据治理的发布形态，将治理后的元数据通过 AI 生成结构化的 skills.md 业务知识文档，经人工审核后发布并向量化，成为 Schema RAG 的核心知识来源。支持文档 CRUD、AI 草稿生成、审核流程、版本管理和向量化触发。

## 解决的问题
- 基于元数据快照自动生成 skills.md 草稿（降低人工编写成本）
- 文档审核流程（DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED）
- 乐观锁防止并发编辑冲突
- 版本管理与回滚（支持行级 diff 对比）
- 发布前校验（阻止引用 DEPRECATED/BLOCKED 字段）
- 发布后自动切片并触发向量化任务

## 实现方案
- **AI 生成**: Java 读取元数据 → 通过 HTTP 调用 Python 服务 → Python 用 Jinja2 填充 Prompt 模板 → 调用 Qwen API 生成 Markdown
- **乐观锁**: MyBatis-Plus @Version 注解，编辑时前端传入当前版本号
- **审核流程**: 状态机模式，每次状态变更创建审核任务记录
- **切片策略**: 按 Markdown 二级标题（##）拆分，根据内容关键词推断切片类型
- **向量化**: 发布时创建 PENDING 任务，定时任务每 5 分钟扫描处理
- **版本 diff**: LCS（最长公共子序列）算法实现行级差异对比

## 代码位置

### 后端
- Controller: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java`
- Service: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/`
  - `KnowledgeDocService.java` / `impl/KnowledgeDocServiceImpl.java`
  - `KnowledgeVersionService.java` / `impl/KnowledgeVersionServiceImpl.java`
  - `VectorIndexTaskService.java` / `impl/VectorIndexTaskServiceImpl.java`
- Client: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/client/`
  - `PythonKnowledgeClient.java` / `impl/PythonKnowledgeClientImpl.java`
- Scheduler: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/scheduler/VectorIndexTaskScheduler.java`
- Entity: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/entity/`
  - `KnowledgeDoc.java`, `KnowledgeDocVersion.java`, `KnowledgeChunk.java`, `KnowledgeReviewTask.java`, `VectorIndexTask.java`
- Enum: `backend/DataOcean/src/main/java/com/dataocean/module/knowledge/enums/`
  - `DocStatus.java`, `ReviewStatus.java`, `GenerationSource.java`, `ChunkType.java`, `VectorTaskStatus.java`

### Python 服务
- Router: `python-service/dataocean/knowledge/router.py`
- Service: `python-service/dataocean/knowledge/service.py`
- Schema: `python-service/dataocean/knowledge/schema.py`
- Prompt 模板: `python-service/dataocean/knowledge/prompts/skills_md_template.j2`

### 前端
- 页面:
  - `frontend/src/views/admin/knowledge/KnowledgeDashboard.vue` — 知识库总览
  - `frontend/src/views/admin/knowledge/SkillsEditor.vue` — Skills 编辑器
  - `frontend/src/views/admin/knowledge/VersionList.vue` — 版本历史
  - `frontend/src/views/admin/knowledge/ReviewPage.vue` — 知识审核
- API: `frontend/src/api/admin/knowledge.ts`

### 数据库
- 迁移脚本: `V13__create_knowledge_tables.sql`
- 涉及表: `knowledge_doc`, `knowledge_doc_version`, `knowledge_chunk`, `knowledge_review_task`, `vector_index_task`

## API 接口清单

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| GET | `/api/admin/knowledge-docs` | 文档列表 | 前端知识库总览 |
| GET | `/api/admin/knowledge-docs/{id}` | 文档详情 | 前端编辑器 |
| POST | `/api/admin/knowledge-docs` | 创建文档 | 前端编辑器 |
| PUT | `/api/admin/knowledge-docs/{id}` | 编辑文档 | 前端编辑器 |
| POST | `/api/admin/knowledge-docs/{id}/submit-review` | 提交审核 | 前端编辑器 |
| POST | `/api/admin/knowledge-docs/{id}/approve` | 审核通过 | 前端审核页 |
| POST | `/api/admin/knowledge-docs/{id}/reject` | 审核拒绝 | 前端审核页 |
| POST | `/api/admin/knowledge-docs/{id}/publish` | 发布文档 | 前端编辑器 |
| POST | `/api/admin/knowledge-docs/{id}/generate-draft` | 生成 AI 草稿 | 前端编辑器 |
| GET | `/api/admin/knowledge-docs/{id}/versions` | 版本列表 | 前端版本历史 |
| GET | `/api/admin/knowledge-docs/{id}/versions/{versionNo}` | 版本详情 | 前端版本历史 |
| GET | `/api/admin/knowledge-docs/{id}/versions/diff?v1=x&v2=y` | 版本对比 | 前端版本历史 |
| POST | `/api/admin/knowledge-docs/{id}/rollback` | 版本回滚 | 前端版本历史 |
| POST | `/api/admin/knowledge-docs/{id}/preview-chunks` | 预览切片 | 前端编辑器 |

### Python 内部接口

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| POST | `/internal/knowledge/generate-draft` | AI 生成 skills.md 草稿 | Java KnowledgeDocServiceImpl |

## 模块间依赖
- **被依赖**: 007 Schema RAG（向量化后的知识切片供 RAG 检索）
- **依赖**: 003 元数据采集（读取表/字段元数据）、004 元数据治理（发布前校验治理状态）、005 版本审核（基于已发布快照）、Python AI 服务（草稿生成）
