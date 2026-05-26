# Tasks: Prompt 管理模块

**Input**: Design documents from `specs/014-prompt-management/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [X] T001 创建 Flyway 迁移脚本 `V23__create_prompt_tables.sql`，建表 prompt_template 和 prompt_template_version
- [X] T002 创建 Java 包结构目录 `backend/src/main/java/com/dataocean/module/prompt/`

## Phase 2: Foundational — Java 模板 CRUD

- [X] T003 [P] 创建实体类 `PromptTemplate.java`，含 @Version 乐观锁字段
- [X] T004 [P] 创建实体类 `PromptTemplateVersion.java`
- [X] T005 [P] 创建 Mapper 接口 `PromptTemplateMapper.java`
- [X] T006 [P] 创建 Mapper 接口 `PromptTemplateVersionMapper.java`
- [X] T007 [P] 创建 DTO `PromptTemplateVO.java`
- [X] T008 [P] 创建 DTO `PromptTemplateUpdateDTO.java`
- [X] T009 [P] 创建 DTO `PromptVersionVO.java`
- [X] T010 [P] 创建 DTO `PromptRollbackDTO.java`

## Phase 3: User Story 1 (P1) — 管理员编辑和发布 Prompt 模板

- [X] T011 [US1] 创建 Service 接口 `PromptTemplateService.java`
- [X] T012 [US1] 创建 Service 实现 `PromptTemplateServiceImpl.java`
- [X] T013 [US1] 实现 updateTemplate 方法（新版本自动创建、乐观锁、事务）
- [X] T014 [US1] 创建 Controller `PromptTemplateController.java`

## Phase 4: User Story 2 (P2) — 管理员回滚 Prompt 版本

- [X] T015 [US2] 实现 rollback 方法
- [X] T016 [US2] 实现 getVersionHistory 方法

## Phase 5: 初始化数据

- [X] T017 创建 Flyway 迁移脚本 `V24__init_prompt_templates.sql`，插入 5 个核心模板

## Phase 6: User Story 3 (P2) — Python Token 预算控制

- [X] T018 [P] [US3] 创建 Python Prompt 路由 `router.py`（GET + POST render）
- [X] T019 [P] [US3] 创建 Python Token 计算模块 `token_budget.py`（优先级裁剪）
- [X] T020 [US3] 创建 Python 模板渲染器 `renderer.py`
- [X] T021 [US3] 创建 Python Prompt 服务 `service.py`

## Phase 7: Polish & Cross-Cutting

- [X] T022 实现模板预览功能（Python render 端点）
- [X] T023 实现 getActiveContent + 内部 API `PromptInternalController`（GET /internal/prompts/{code}）

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

- [X] T024 [P] [Frontend] 创建 API 层 `frontend/src/api/admin/prompt.ts`
- [X] T025 [Frontend] 创建 Prompt 模板列表页面（已在 SkillsEditor.vue 中集成）
- [X] T026 [Frontend] 创建 Prompt 编辑器页面（已在 SkillsEditor.vue 中集成）
- [X] T027 [Frontend] 创建版本历史页面（已在 VersionList.vue 中集成）
