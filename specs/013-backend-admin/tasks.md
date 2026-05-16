# Tasks: 后台治理管理端模块

**Input**: Design documents from `specs/013-backend-admin/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup — 布局和路由框架

- [ ] T001 创建后台路由配置文件 `frontend/src/router/admin.ts`，定义 /admin/* 所有路由，配置权限守卫（根据 Page-Permission Mapping 校验 auth store 中的 permissions）
- [ ] T002 创建后台布局组件 `frontend/src/views/admin/AdminLayout.vue`，使用 Element Plus Container 实现侧边栏 + 顶栏 + 内容区布局
- [ ] T003 创建侧边导航菜单组件 `frontend/src/components/admin/SideMenu.vue`，根据用户 permissions 动态过滤菜单项，支持展开/折叠
- [ ] T004 创建菜单 store `frontend/src/stores/menu.ts`，管理菜单展开/折叠状态和当前选中项
- [ ] T005 创建 v-permission 指令 `frontend/src/directives/permission.ts`，实现按钮级权限控制（无权限时移除 DOM 元素）
- [ ] T006 [P] 创建通用页面标题组件 `frontend/src/components/admin/PageHeader.vue`，包含标题 + 面包屑 + 操作按钮插槽
- [ ] T007 [P] 创建通用数据表格组件 `frontend/src/components/admin/DataTable.vue`，封装 Element Plus Table + Pagination + 筛选表单，支持 props 配置列定义、分页、排序
- [ ] T008 [P] 创建状态标签组件 `frontend/src/components/admin/StatusBadge.vue`，根据状态值映射颜色和文本
- [ ] T009 [P] 创建确认弹窗组件 `frontend/src/components/admin/ConfirmDialog.vue`，封装 Element Plus MessageBox 的确认/取消逻辑
- [ ] T010 [P] 创建通用搜索表单组件 `frontend/src/components/admin/SearchForm.vue`，支持 props 配置搜索字段、重置、提交
- [ ] T011 [P] 创建面包屑导航组件 `frontend/src/components/admin/BreadCrumb.vue`，根据当前路由自动生成面包屑

## Phase 2: User Story 1 (P1) — 数据源和元数据管理

**Goal**: 管理员能从后台完成数据源配置 → 元数据同步 → 治理 → skills.md 发布的完整流程
**Independent Test**: 管理员能完成数据源 CRUD 和连通性测试

- [ ] T012 [US1] 创建数据源管理 API `frontend/src/api/admin/datasource.ts`，封装数据源 CRUD + 连通性测试接口
- [ ] T013 [US1] 创建数据源列表页面 `frontend/src/views/admin/datasource/DataSourceList.vue`，使用 DataTable 展示数据源列表，支持新增/编辑/删除/连通性测试操作
- [ ] T014 [US1] 创建数据源表单页面 `frontend/src/views/admin/datasource/DataSourceForm.vue`，Element Plus Form 实现新增/编辑表单，包含连接信息验证和密码加密提示
- [ ] T015 [US1] 创建元数据 API `frontend/src/api/admin/metadata.ts`，封装元数据浏览、同步触发、快照管理接口
- [ ] T016 [US1] 创建元数据浏览器页面 `frontend/src/views/admin/metadata/MetadataExplorer.vue`，左侧 Element Plus Tree（数据源→表→字段）+ 右侧详情面板展示选中节点信息

## Phase 3: User Story 2 (P1) — 治理工作台

**Goal**: 分析师能看到自己的待办问题，处理后问题状态更新
**Independent Test**: 分析师能处理分派给自己的元数据问题

- [ ] T017 [US2] 创建治理 API `frontend/src/api/admin/governance.ts`，封装问题清单查询、问题处理、状态流转接口
- [ ] T018 [US2] 创建治理工作台页面 `frontend/src/views/admin/metadata/GovernanceWorkbench.vue`，展示问题清单（DataTable）+ 处理表单（Dialog）+ 状态流转按钮

## Phase 4: 治理工具

- [ ] T019 [P] 创建 skills.md API `frontend/src/api/admin/skills.ts`，封装 skills.md 获取、保存、版本列表、审核发布接口
- [ ] T020 [P] 创建字段 Tag API `frontend/src/api/admin/fieldtag.ts`，封装字段列表、批量打标、可信度查看/设置接口
- [ ] T021 创建 skills.md 编辑器页面 `frontend/src/views/admin/skills/SkillsEditor.vue`，集成 md-editor-v3 Markdown 编辑器，支持版本对比（左右分栏 diff）和审核发布按钮
- [ ] T022 创建 Prompt 模板管理页面 `frontend/src/views/admin/prompt/PromptManager.vue`，DataTable 展示模板列表 + Dialog 编辑模板内容（变量占位符 {{}} 高亮）+ 版本历史查看
- [ ] T023 创建字段 Tag 管理页面 `frontend/src/views/admin/fieldtag/FieldTagManager.vue`，DataTable 展示字段列表 + 批量打标操作 + 可信度数值展示和手动设置

## Phase 5: User Story 3 (P2) — 质量看板和审核

**Goal**: 看板能展示各数据源的质量分、问题数、治理完成率
**Independent Test**: 看板数据正确展示，反馈审核流程可操作

- [ ] T024 [P] [US3] 创建反馈审核 API `frontend/src/api/admin/feedback.ts`，封装审核队列查询、通过/驳回操作接口
- [ ] T025 [P] [US3] 创建审计日志 API `frontend/src/api/admin/audit.ts`，封装审计日志多维度查询和 CSV 导出接口
- [ ] T026 [US3] 创建反馈审核页面 `frontend/src/views/admin/feedback/FeedbackReview.vue`，审核队列列表 + 通过/驳回操作按钮 + 关联字段展示面板
- [ ] T027 [US3] 创建审计日志页面 `frontend/src/views/admin/audit/AuditLogs.vue`，多维度筛选表单 + DataTable + 详情弹窗 + CSV 导出按钮
- [ ] T028 [US3] 创建首页看板 `frontend/src/views/admin/Dashboard.vue`，使用 ECharts 展示质量分雷达图 + 查询趋势折线图 + 待办事项统计卡片 + 低质量数据源标红告警

## Phase 6: 用户和权限管理

- [ ] T029 [P] 创建用户管理 API `frontend/src/api/admin/user.ts`，封装用户 CRUD + 角色分配 + 状态管理接口
- [ ] T030 [P] 创建角色权限 API `frontend/src/api/admin/role.ts`，封装角色 CRUD + 权限树查询/保存接口
- [ ] T031 创建用户管理页面 `frontend/src/views/admin/user/UserManagement.vue`，DataTable 展示用户列表 + 新增/编辑 Dialog（含角色多选）+ 启用/禁用操作
- [ ] T032 创建角色权限页面 `frontend/src/views/admin/user/RolePermission.vue`，角色列表 + 权限树勾选（Element Plus Tree with checkbox）+ 保存

## Phase 7: Polish & Cross-Cutting

- [ ] T033 创建 admin store `frontend/src/stores/admin.ts`，管理后台全局 loading 状态和通用配置
- [ ] T034 创建 404 页面 `frontend/src/views/admin/NotFound.vue`，后台路由未匹配时展示
- [ ] T035 实现菜单折叠/展开动画和响应式适配（小屏幕自动折叠侧边栏）

## Dependencies

```
T001 → T002 → T003 → T013, T016, T018, T021-T032
T004 → T003
T005 → T013, T018, T022 (按钮级权限控制)
T006-T011 → T013, T016, T018 (通用组件被各页面复用)
T007 → T013, T018, T023, T026, T027, T031
T012 → T013, T014
T015 → T016
T017 → T018
```

## Implementation Strategy

MVP-first: Phase 1 建立框架和通用组件（所有页面复用），Phase 2-3 完成核心治理流程（P1 用户故事），Phase 4 补充治理工具，Phase 5-6 完善审核和权限管理。通用 DataTable 组件是关键复用点，优先完成。
