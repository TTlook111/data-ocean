# Tasks: 后台治理管理端模块

**Input**: Design documents from `specs/013-backend-admin/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup — 布局和路由框架

- [X] T001 创建后台路由配置文件 `frontend/src/router/admin.ts`，定义 /admin/* 所有路由，配置权限守卫（根据 Page-Permission Mapping 校验 auth store 中的 permissions）
- [X] T002 创建后台布局组件 `frontend/src/views/admin/AdminLayout.vue`，使用 Element Plus Container 实现侧边栏 + 顶栏 + 内容区布局
- [X] T003 创建侧边导航菜单组件 `frontend/src/components/admin/SideMenu.vue`，根据用户 permissions 动态过滤菜单项，支持展开/折叠
- [X] T004 创建菜单 store `frontend/src/stores/menu.ts`，管理菜单展开/折叠状态和当前选中项
- [X] T005 创建 v-permission 指令 `frontend/src/directives/permission.ts`，实现按钮级权限控制（无权限时移除 DOM 元素）
- [X] T006 [P] 创建通用页面标题组件 `frontend/src/components/admin/PageHeader.vue`，包含标题 + 面包屑 + 操作按钮插槽
- [X] T007 [P] 创建通用数据表格组件 `frontend/src/components/admin/DataTable.vue`，封装 Element Plus Table + Pagination + 筛选表单，支持 props 配置列定义、分页、排序
- [X] T008 [P] 创建状态标签组件 `frontend/src/components/admin/StatusBadge.vue`，根据状态值映射颜色和文本
- [X] T009 [P] 创建确认弹窗组件 `frontend/src/components/admin/ConfirmDialog.vue`，封装 Element Plus MessageBox 的确认/取消逻辑
- [X] T010 [P] 创建通用搜索表单组件 `frontend/src/components/admin/SearchForm.vue`，支持 props 配置搜索字段、重置、提交
- [X] T011 [P] 创建面包屑导航组件 `frontend/src/components/admin/BreadCrumb.vue`，根据当前路由自动生成面包屑

## Phase 2: User Story 1 (P1) — 数据源和元数据管理

- [X] T012 [US1] 创建数据源管理 API `frontend/src/api/admin/datasource.ts`，封装数据源 CRUD + 连通性测试接口
- [X] T013 [US1] 创建数据源列表页面 `frontend/src/views/admin/datasource/DataSourceList.vue`，使用 DataTable 展示数据源列表，支持新增/编辑/删除/连通性测试操作
- [X] T014 [US1] 创建数据源表单页面 `frontend/src/views/admin/datasource/DataSourceForm.vue`，Element Plus Form 实现新增/编辑表单，包含连接信息验证和密码加密提示
- [X] T015 [US1] 创建元数据 API `frontend/src/api/admin/metadata.ts`，封装元数据浏览、同步触发、快照管理接口
- [X] T016 [US1] 创建元数据浏览器页面 `frontend/src/views/admin/metadata/MetadataExplorer.vue`，左侧 Element Plus Tree（数据源→表→字段）+ 右侧详情面板展示选中节点信息

## Phase 3: User Story 2 (P1) — 治理工作台

- [X] T017 [US2] 创建治理 API `frontend/src/api/admin/governance.ts`，封装问题清单查询、问题处理、状态流转接口
- [X] T018 [US2] 创建治理工作台页面 `frontend/src/views/admin/metadata/GovernanceWorkbench.vue`，展示问题清单（DataTable）+ 处理表单（Dialog）+ 状态流转按钮

## Phase 4: 治理工具

- [X] T019 [P] 创建 skills.md API `frontend/src/api/admin/skills.ts`，封装 skills.md 获取、保存、版本列表、审核发布接口
- [X] T020 [P] 创建字段 Tag API `frontend/src/api/admin/fieldtag.ts`，封装字段列表、批量打标、可信度查看/设置接口
- [X] T021 创建 skills.md 编辑器页面 `frontend/src/views/admin/skills/SkillsEditor.vue`，集成 md-editor-v3 Markdown 编辑器，支持版本对比（左右分栏 diff）和审核发布按钮
- [X] T022 创建 Prompt 模板管理页面 `frontend/src/views/admin/prompt/PromptManager.vue`，DataTable 展示模板列表 + Dialog 编辑模板内容（变量占位符 {{}} 高亮）+ 版本历史查看
- [X] T023 创建字段 Tag 管理页面 `frontend/src/views/admin/fieldtag/FieldTagManager.vue`，DataTable 展示字段列表 + 批量打标操作 + 可信度数值展示和手动设置

## Phase 5: User Story 3 (P2) — 质量看板和审核

- [X] T024 [P] [US3] 创建反馈审核 API `frontend/src/api/admin/feedback.ts`，封装审核队列查询、通过/驳回操作接口
- [X] T025 [P] [US3] 创建审计日志 API `frontend/src/api/admin/audit.ts`，封装审计日志多维度查询和 CSV 导出接口
- [X] T026 [US3] 创建反馈审核页面 `frontend/src/views/admin/feedback/FeedbackReview.vue`，审核队列列表 + 通过/驳回操作按钮 + 关联字段展示面板
- [X] T027 [US3] 创建审计日志页面 `frontend/src/views/admin/audit/AuditLogs.vue`，多维度筛选表单 + DataTable + 详情弹窗 + CSV 导出按钮
- [X] T028 [US3] 创建首页看板 `frontend/src/views/admin/Dashboard.vue`，使用 ECharts 展示质量分雷达图 + 查询趋势折线图 + 待办事项统计卡片 + 低质量数据源标红告警

## Phase 6: 用户和权限管理

- [X] T029 [P] 创建用户管理 API `frontend/src/api/admin/user.ts`，封装用户 CRUD + 角色分配 + 状态管理接口
- [X] T030 [P] 创建角色权限 API `frontend/src/api/admin/role.ts`，封装角色 CRUD + 权限树查询/保存接口
- [X] T031 创建用户管理页面 `frontend/src/views/admin/user/UserManagement.vue`，DataTable 展示用户列表 + 新增/编辑 Dialog（含角色多选）+ 启用/禁用操作
- [X] T032 创建角色权限页面 `frontend/src/views/admin/user/RolePermission.vue`，角色列表 + 权限树勾选（Element Plus Tree with checkbox）+ 保存

## Phase 7: Polish & Cross-Cutting

- [X] T033 创建 admin store `frontend/src/stores/admin.ts`，管理后台全局 loading 状态和通用配置
- [X] T034 创建 404 页面 `frontend/src/views/admin/NotFound.vue`，后台路由未匹配时展示
- [X] T035 实现菜单折叠/展开动画和响应式适配（小屏幕自动折叠侧边栏）

## Phase 8: System Settings & Notifications

- [X] T036 [Frontend] 创建系统设置页面 `frontend/src/views/admin/system/Settings.vue`：配置项分组展示（查询超时、Token 有效期、同步频率、每日查询配额等），修改后调用 API 保存
- [X] T037 在 Java 后端创建 sys_config 表和 SystemConfigService：key-value 配置存储，支持在线修改无需重启
- [X] T038 [Frontend] 创建通知中心组件 `frontend/src/components/NotificationCenter.vue`：顶部铃铛图标 + 下拉通知列表（治理告警、审核提醒、慢查询告警）
- [X] T039 在 Java 后端创建 sys_notification 表和 NotificationService：存储通知（类型/标题/内容/已读状态/目标用户），提供 GET /api/notifications 和 PATCH /api/notifications/{id}/read 接口
- [X] T040 [Frontend] 创建管理操作日志页面 `frontend/src/views/admin/system/OperationLog.vue`：展示管理员的 CRUD 操作记录
- [X] T041 在 Java 后端创建 sys_operation_log 表和 OperationLogService：通过 AOP 切面自动记录所有 /api/admin/* 接口的调用（操作人/操作类型/目标资源/请求参数/时间）

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
