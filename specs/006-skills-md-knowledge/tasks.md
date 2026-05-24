# Tasks: skills.md 业务知识库模块

**Input**: Design documents from `specs/006-skills-md-knowledge/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [X] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V7__create_knowledge_tables.sql`，包含 knowledge_doc、knowledge_doc_version、knowledge_chunk、knowledge_review_task、vector_index_task 五张表的 DDL
- [X] T002 创建 Java 包结构 `backend/src/main/java/com/dataocean/module/knowledge/`，包含 controller/service/mapper/entity/dto/enums/feign 子包和对应的空类骨架
- [X] T003 创建 Python 包结构 `python-service/dataocean/knowledge/`，包含 `__init__.py`、`router.py`、`service.py`、`schema.py` 和 `prompts/` 目录

## Phase 2: Foundational — Java CRUD + 状态流转

- [X] T004 [P] 实现实体类 `backend/src/main/java/com/dataocean/module/knowledge/entity/KnowledgeDoc.java`，包含 id、datasource_id、title、content、current_version、status、review_status、updated_by、version（乐观锁）字段
- [X] T005 [P] 实现实体类 `backend/src/main/java/com/dataocean/module/knowledge/entity/KnowledgeDocVersion.java`，包含 id、doc_id、datasource_id、metadata_snapshot_id、version_no、content、generation_source、review_status、reviewer_id、change_summary、created_by、created_at 字段
- [X] T006 [P] 实现枚举 `backend/src/main/java/com/dataocean/module/knowledge/enums/DocStatus.java`（DRAFT/PENDING_REVIEW/APPROVED/PUBLISHED/DEPRECATED）和 `ReviewStatus.java`
- [X] T007 [P] 实现 Mapper 接口 `backend/src/main/java/com/dataocean/module/knowledge/mapper/KnowledgeDocMapper.java` 和 `KnowledgeDocVersionMapper.java`，继承 BaseMapper
- [X] T008 实现 `backend/src/main/java/com/dataocean/module/knowledge/service/KnowledgeDocService.java`，包含文档列表（分页+按数据源筛选）、详情、创建、编辑（乐观锁校验 version 字段）方法
- [X] T009 实现 `backend/src/main/java/com/dataocean/module/knowledge/service/KnowledgeVersionService.java`，包含版本列表、版本详情、创建新版本、版本对比（返回两个版本的 content diff）方法
- [X] T010 实现 `backend/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java`，暴露 REST API：GET /api/knowledge/docs（列表）、GET /api/knowledge/docs/{id}（详情）、POST /api/knowledge/docs（创建）、PUT /api/knowledge/docs/{id}（编辑）、GET /api/knowledge/docs/{id}/versions（版本列表）

## Phase 3: User Story 2 (P1) — 审核流程

**Goal**: 分析师审核并发布 skills.md
**Independent Test**: 发布后的 skills.md 能被向量化模块检测到并触发向量化任务

- [X] T011 [P] [US2] 实现实体类 `backend/src/main/java/com/dataocean/module/knowledge/entity/KnowledgeReviewTask.java` 和对应 Mapper
- [X] T012 [P] [US2] 实现实体类 `backend/src/main/java/com/dataocean/module/knowledge/entity/KnowledgeChunk.java` 和对应 Mapper，包含 chunk_type（TABLE_DESC/JOIN_PATH/METRIC/FIELD_NOTE）、chunk_text、related_table、related_column、review_status、vector_status 字段
- [X] T013 [US2] 实现 `backend/src/main/java/com/dataocean/module/knowledge/service/KnowledgeReviewService.java`，包含：提交审核（状态 DRAFT→PENDING_REVIEW）、审核通过（PENDING_REVIEW→APPROVED）、审核拒绝（PENDING_REVIEW→DRAFT 并记录拒绝原因）
- [X] T014 [US2] 实现发布前校验逻辑：在 KnowledgeDocService 中添加 validateBeforePublish 方法，校验引用的表字段是否存在、governance_status 是否为 NORMAL/RECOMMENDED、是否有 DEPRECATED 字段被引用
- [X] T015 [US2] 实现发布逻辑：审核通过后管理员点击发布（APPROVED→PUBLISHED），触发创建 vector_index_task（status=PENDING），同时将 content 按模板模块切分为 knowledge_chunk 记录
- [X] T016 [US2] 实现 `backend/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java` 中审核相关接口：POST /api/knowledge/docs/{id}/submit-review、POST /api/knowledge/docs/{id}/approve、POST /api/knowledge/docs/{id}/reject、POST /api/knowledge/docs/{id}/publish

## Phase 4: User Story 1 (P1) — AI 草稿生成

**Goal**: 系统基于元数据快照生成 skills.md 草稿
**Independent Test**: 生成的草稿包含所有必填模块（文档来源、核心表、Join Path、指标口径、字段防坑），且引用的表字段在快照中存在

- [X] T017 [US1] 创建 Jinja2 Prompt 模板 `python-service/dataocean/knowledge/prompts/skills_md_template.j2`，模板变量包含：tables（表名+注释+字段列表）、foreign_keys、indexes、confidence_scores；输出结构包含：文档来源、核心表说明、Join Path、指标候选、字段防坑
- [X] T018 [US1] 实现 Python schema `python-service/dataocean/knowledge/schema.py`，定义 GenerateDraftRequest（snapshot_id、datasource_id、tables_metadata）和 GenerateDraftResponse（content、generation_source、warnings）Pydantic 模型
- [X] T019 [US1] 实现 Python service `python-service/dataocean/knowledge/service.py`，包含 generate_draft 方法：接收快照元数据 → 填充 Jinja2 模板 → 调用 Qwen API（dashscope SDK）→ 解析返回的 Markdown → 标记无注释字段为"待人工确认"
- [X] T020 [US1] 实现 Python router `python-service/dataocean/knowledge/router.py`，暴露 POST /internal/knowledge/generate-draft 接口
- [X] T021 [US1] 实现 Java Feign 客户端 `backend/src/main/java/com/dataocean/module/knowledge/feign/PythonKnowledgeClient.java`，调用 Python 的 /internal/knowledge/generate-draft 接口
- [X] T022 [US1] 在 KnowledgeDocService 中添加 generateDraft 方法：读取指定 snapshot_id 的元数据 → 通过 Feign 调用 Python 生成草稿 → 创建 knowledge_doc_version（generation_source=AI_GENERATED）→ 返回草稿内容
- [X] T023 [US1] 在 Controller 中添加接口 POST /api/knowledge/docs/{id}/generate-draft，接收 snapshot_id 参数

## Phase 5: User Story 3 (P2) — 版本管理与回滚

**Goal**: 管理员查看 skills.md 的历史版本，对比差异，必要时回滚到旧版本
**Independent Test**: 回滚后，RAG 使用的是回滚后的版本内容

- [X] T024 [US3] 实现版本对比接口 GET /api/knowledge/docs/{id}/versions/diff?v1={versionNo1}&v2={versionNo2}，返回两个版本内容的行级差异
- [X] T025 [US3] 实现回滚接口 POST /api/knowledge/docs/{id}/rollback，接收 target_version_no 参数，创建新版本（内容复制自目标版本），自动触发重新向量化（创建新的 vector_index_task）

## Phase 6: 向量化触发

- [X] T026 实现实体类 `backend/src/main/java/com/dataocean/module/knowledge/entity/VectorIndexTask.java` 和对应 Mapper，状态枚举 PENDING/PROCESSING/COMPLETED/FAILED
- [X] T027 实现 `backend/src/main/java/com/dataocean/module/knowledge/service/VectorIndexTaskService.java`，包含创建任务、更新状态、查询待处理任务方法
- [X] T028 实现定时任务（@Scheduled 每 5 分钟）扫描 status=PENDING 的 vector_index_task，通过 Feign 调用 007 模块的 /internal/rag/vectorize 接口，更新任务状态为 PROCESSING/COMPLETED/FAILED
- [X] T028a 补充 vector_index_task 的版本上下文字段：metadata_snapshot_id、knowledge_version_no、previous_version_no，发布/回滚时记录新旧版本关系
- [X] T028b 调度器调用 Python RAG 后，仅在返回 COMPLETED 且写入数量等于 chunk 数时标记任务完成；成功后将新版本 chunk 置为 INDEXED，旧版本 chunk 置为 SUPERSEDED
- [X] T028c 回滚创建新版本后同步重新生成该版本 knowledge_chunk，再创建向量化任务，避免任务找不到切片
- [X] T028d 人工编辑 skills.md 内容时创建新的 MANUAL 版本，发布时以新版本替换上一版 RAG 向量
- [X] T028e 在 knowledge_doc_version 记录 dependency_snapshot，追踪 skills.md 生成依赖的数据源连接摘要、元数据快照、schema_hash 和字段可信度来源

## Phase 7: Polish & Cross-Cutting

- [X] T029 添加角色权限校验：编辑和审核操作仅允许 DATA_ANALYST 和 ADMIN 角色（通过 Spring Security 注解 @PreAuthorize）
- [X] T030 实现 DTO 类 `backend/src/main/java/com/dataocean/module/knowledge/dto/` 下所有 VO 和 Request 类，确保 Controller 不直接暴露 Entity
- [X] T031 添加接口参数校验（@Valid + Spring Validation 注解），包括 content 非空、snapshot_id 存在性校验

## Phase 9: Preview & Dashboard

- [X] T036 在 Java 后端添加 POST /api/admin/knowledge-docs/{id}/preview-chunks 接口：模拟切片逻辑，返回该版本会被切成哪些 chunks（chunk_text + related_table），供作者预览 RAG 效果
- [X] T037 [Frontend] 在 Skills 编辑器中添加"RAG 预览"侧边栏：展示当前内容切片后的 chunks 列表
- [X] T038 [Frontend] 创建 Skills 状态总览页面 `frontend/src/views/admin/knowledge/KnowledgeDashboard.vue`：按数据源展示 skills.md 状态（草稿/待审核/已发布/需更新）

## Dependencies

```
T001 → T004~T007 → T008~T009 → T010
T003 → T017~T020
T010 + T020 → T021~T023
T008 → T011~T016
T015 → T026~T028
T009 → T024~T025
T025 → T028 (回滚触发向量化)
```

## Implementation Strategy

MVP-first approach:
1. 先完成 Java CRUD 和状态流转（Phase 2），确保文档管理基础可用
2. 再做审核流程（Phase 3），打通发布链路
3. 然后接入 Python AI 生成（Phase 4），提升效率
4. 向量化触发（Phase 6）依赖 007 模块接口，可先 mock 调用
5. 版本管理（Phase 5）和权限（Phase 7）最后完善

## Phase 8: Frontend Pages

- [X] T032 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/knowledge.ts`：获取/更新文档、生成草稿、提交审核、发布、回滚、版本列表
- [X] T033 [Frontend] 创建 Skills 编辑器页面 `frontend/src/views/admin/knowledge/SkillsEditor.vue`：集成 md-editor-v3 Markdown 编辑器、实时预览、保存草稿
- [X] T034 [Frontend] 创建版本列表页面 `frontend/src/views/admin/knowledge/VersionList.vue`：版本号/状态/审核人/创建时间、对比按钮、回滚按钮
- [X] T035 [Frontend] 创建审核页面 `frontend/src/views/admin/knowledge/ReviewPage.vue`：待审核列表、内容预览、通过/驳回操作
