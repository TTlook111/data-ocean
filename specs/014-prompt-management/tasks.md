# Tasks: Prompt 管理模块

**Input**: Design documents from `specs/014-prompt-management/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V14__create_prompt_tables.sql`，建表 prompt_template（id, template_code, template_name, scenario, content, current_version, enabled, version, created_at, updated_at）和 prompt_template_version（id, template_id, version_no, content, change_summary, is_active, created_by, created_at）
- [ ] T002 创建 Java 包结构目录 `backend/src/main/java/com/dataocean/module/prompt/`，包含 controller/, service/, mapper/, entity/, dto/ 子包

## Phase 2: Foundational — Java 模板 CRUD

- [ ] T003 [P] 创建实体类 `backend/src/main/java/com/dataocean/module/prompt/entity/PromptTemplate.java`，MyBatis-Plus 注解映射 prompt_template 表，包含 @Version 乐观锁字段
- [ ] T004 [P] 创建实体类 `backend/src/main/java/com/dataocean/module/prompt/entity/PromptTemplateVersion.java`，MyBatis-Plus 注解映射 prompt_template_version 表
- [ ] T005 [P] 创建 Mapper 接口 `backend/src/main/java/com/dataocean/module/prompt/mapper/PromptTemplateMapper.java`，继承 BaseMapper<PromptTemplate>
- [ ] T006 [P] 创建 Mapper 接口 `backend/src/main/java/com/dataocean/module/prompt/mapper/PromptTemplateVersionMapper.java`，继承 BaseMapper<PromptTemplateVersion>
- [ ] T007 [P] 创建 DTO `backend/src/main/java/com/dataocean/module/prompt/dto/PromptTemplateVO.java`，包含模板基本信息 + 当前活跃版本内容
- [ ] T008 [P] 创建 DTO `backend/src/main/java/com/dataocean/module/prompt/dto/PromptTemplateUpdateRequest.java`，包含 content, changeSummary 字段
- [ ] T009 [P] 创建 DTO `backend/src/main/java/com/dataocean/module/prompt/dto/PromptVersionVO.java`，包含版本号、内容、修改人、修改时间、是否活跃
- [ ] T010 [P] 创建 DTO `backend/src/main/java/com/dataocean/module/prompt/dto/PromptRollbackRequest.java`，包含 targetVersionNo 字段

## Phase 3: User Story 1 (P1) — 管理员编辑和发布 Prompt 模板

**Goal**: 修改 sql_generation 模板后，下次查询使用新版本 Prompt
**Independent Test**: 修改模板保存后版本号递增，新版本自动设为 active

- [ ] T011 [US1] 创建 Service 接口 `backend/src/main/java/com/dataocean/module/prompt/service/PromptTemplateService.java`，定义 listTemplates(), getTemplate(code), updateTemplate(code, request), getVersionHistory(code), rollback(code, request), getActiveContent(code) 方法签名
- [ ] T012 [US1] 创建 Service 实现 `backend/src/main/java/com/dataocean/module/prompt/service/PromptTemplateServiceImpl.java`，实现 listTemplates（分页查询所有模板）和 getTemplate（按 template_code 查询含活跃版本内容）
- [ ] T013 [US1] 在 PromptTemplateServiceImpl 中实现 updateTemplate 方法：创建新版本记录（version_no +1, is_active=true）、将旧活跃版本设为 is_active=false、更新 prompt_template.current_version，使用 @Transactional 保证原子性，乐观锁处理并发冲突
- [ ] T014 [US1] 创建 Controller `backend/src/main/java/com/dataocean/module/prompt/controller/PromptTemplateController.java`，实现 GET /api/admin/prompt-templates（列表）、GET /api/admin/prompt-templates/{code}（详情）、PUT /api/admin/prompt-templates/{code}（更新）、GET /api/admin/prompt-templates/{code}/versions（版本历史）、POST /api/admin/prompt-templates/{code}/rollback（回滚）

## Phase 4: User Story 2 (P2) — 管理员回滚 Prompt 版本

**Goal**: 回滚后，查询使用旧版本 Prompt 生成 SQL
**Independent Test**: 回滚到 v2 后，v2 变为 is_active，v3 变为非活跃

- [ ] T015 [US2] 在 PromptTemplateServiceImpl 中实现 rollback 方法：将目标版本设为 is_active=true，当前活跃版本设为 is_active=false，更新 prompt_template.current_version，@Transactional 保证原子性
- [ ] T016 [US2] 在 PromptTemplateServiceImpl 中实现 getVersionHistory 方法：按 template_id 查询所有版本，按 version_no 降序排列

## Phase 5: 初始化数据

- [ ] T017 创建 Flyway 迁移脚本 `backend/src/main/resources/db/migration/V10_1__init_prompt_templates.sql`，插入 5 个核心模板的默认内容：sql_generation、chart_generation、intent_recognition、schema_retrieval_query、memory_extraction，每个模板创建 v1 版本并设为 active

## Phase 6: User Story 3 (P2) — Python Token 预算控制

**Goal**: 当 Schema 内容过多时，低优先级的 Few-shot 模板被裁剪
**Independent Test**: Prompt 总 Token 超过 4000 时按优先级裁剪

- [ ] T018 [P] [US3] 创建 Python Prompt 路由 `python-service/dataocean/prompt/router.py`，实现 GET /internal/prompts/{template_code} 端点，通过 httpx 调用 Java API 获取活跃版本内容
- [ ] T019 [P] [US3] 创建 Python Token 计算模块 `python-service/dataocean/prompt/token_budget.py`，使用 tiktoken cl100k_base 编码计算 Token 数，实现优先级裁剪逻辑：schema(1500) > skills(1000) > few-shot(800) > context(500) > confidence(200)，超出总预算 4000 时从最低优先级开始截断
- [ ] T020 [US3] 创建 Python 模板渲染器 `python-service/dataocean/prompt/renderer.py`，使用 Jinja2 渲染 {{变量}} 占位符，变量不存在时跳过并记录 warning 日志
- [ ] T021 [US3] 创建 Python Prompt 服务 `python-service/dataocean/prompt/service.py`，组合 router（获取模板）+ renderer（渲染变量）+ token_budget（裁剪），对外提供 render_prompt(template_code, variables) 方法

## Phase 7: Polish & Cross-Cutting

- [ ] T022 在 Java Controller 中实现模板预览功能：接收示例变量 JSON，调用 Jinja2 风格的简单替换逻辑返回渲染结果（或调用 Python 预览接口）
- [ ] T023 在 PromptTemplateServiceImpl 中添加 getActiveContent(code) 方法供 Java 内部 API 调用（Python 通过此接口获取模板），路径 GET /internal/prompts/{code}/active

## Phase 9: Prompt Effectiveness Tracking

- [ ] T028 在 Python Agent 每次调用 LLM 时，将使用的 prompt_template_code 和 version_no 写入 AgentState，最终返回给 Java
- [ ] T029 在 Java 审计日志（query_audit_log）中新增 prompt_versions 字段（JSON），记录本次查询使用的各 Prompt 版本
- [ ] T030 [Frontend] 在 Prompt 管理页面添加"效果分析"标签页：按版本展示查询成功率、平均耗时、用户反馈正向率

## Dependencies

```
T001 → T003-T006 → T011-T014
T007-T010 → T014
T011 → T012 → T013 → T014
T013 → T015
T012 → T016
T017 依赖 T001（表结构存在）
T014 → T018（Python 调用 Java API）
T019 → T021
T020 → T021
T018 → T021
```

## Implementation Strategy

MVP-first: Phase 1-3 完成 Java 侧模板 CRUD 和版本管理（管理员可用），Phase 4 补充回滚能力，Phase 5 初始化默认数据，Phase 6 实现 Python 消费端（查询流程可用），Phase 7 完善预览和内部 API。Java 和 Python 可并行开发（Phase 2-4 与 Phase 6 并行）。

## Phase 8: Frontend Pages

- [ ] T024 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/prompt.ts`：模板列表、获取/更新模板、版本历史、回滚、预览
- [ ] T025 [Frontend] 创建 Prompt 模板列表页面 `frontend/src/views/admin/prompt/PromptList.vue`：模板名称/场景/当前版本/状态
- [ ] T026 [Frontend] 创建 Prompt 编辑器页面 `frontend/src/views/admin/prompt/PromptEditor.vue`：代码编辑器（支持 {{变量}} 高亮）、变量面板、预览渲染结果
- [ ] T027 [Frontend] 创建版本历史页面 `frontend/src/views/admin/prompt/PromptVersions.vue`：版本列表、内容对比、回滚按钮
