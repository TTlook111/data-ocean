# Tasks: 前端问答端模块

**Input**: Design documents from `specs/012-frontend-query/`
**Prerequisites**: plan.md, spec.md

## Phase 1: Setup

- [ ] T001 创建问答端路由配置文件 `frontend/src/router/query.ts`，定义 /query/*, /query/history 路由，配置路由守卫（未登录跳转 /login）
- [ ] T002 创建问答端布局容器 `frontend/src/views/query/QueryLayout.vue`，包含顶部导航栏（用户信息、退出）和内容区 router-view
- [ ] T003 创建 auth store `frontend/src/stores/auth.ts`，实现 state(token, user), actions(login, logout, refreshUser), getters(isAuthenticated, hasPermission)
- [ ] T004 创建 Axios 请求封装 `frontend/src/api/request.ts`，配置 baseURL、token 拦截器、401 自动跳转登录、统一错误处理

## Phase 2: Foundational — 数据源选择

- [ ] T005 [P] 创建 datasource store `frontend/src/stores/datasource.ts`，实现 state(datasources[], currentDatasourceId, loading), actions(fetchDatasources, selectDatasource), getters(currentDatasource, enabledDatasources)
- [ ] T006 [P] 创建数据源 API 文件 `frontend/src/api/datasource.ts`，封装 GET /api/datasources 接口调用
- [ ] T007 创建数据源选择器组件 `frontend/src/components/query/DataSourceSelector.vue`，下拉框展示有权限的已启用数据源，选中后更新 store

## Phase 3: User Story 1 (P1) — 用户选择数据源并提问

**Goal**: 用户能选择数据源、输入问题、看到查询进度、最终看到结果
**Independent Test**: 用户能选择数据源、输入问题、看到查询进度、最终看到结果

- [ ] T008 [US1] 创建查询 API 文件 `frontend/src/api/query.ts`，封装 POST /api/query/ask（返回 taskId, HTTP 202）和 GET /api/query/tasks/{taskId}（轮询降级）
- [ ] T009 [US1] 创建 queryTask store `frontend/src/stores/queryTask.ts`，实现 state(currentTaskId, status, progress, result), actions(submitQuery, connectSSE, cancelQuery), getters(isQuerying, progressSteps, queryResult)
- [ ] T010 [US1] 创建 SSE composable `frontend/src/composables/useSSE.ts`，封装 EventSource 连接（URL query param 传 token）、事件解析、5s 自动重连、3 次失败后降级为轮询
- [ ] T011 [US1] 创建聊天输入组件 `frontend/src/components/query/ChatInput.vue`，包含 textarea + 发送按钮，查询中禁用发送，Enter 发送 / Shift+Enter 换行
- [ ] T012 [US1] 创建查询进度组件 `frontend/src/components/query/QueryProgress.vue`，展示 SSE 推送的步骤状态（schema_retrieving → sql_generating → sql_validating → executing → visualizing → done）
- [ ] T013 [US1] 创建主查询页面 `frontend/src/views/query/QueryPage.vue`，组合 DataSourceSelector + ChatInput + QueryProgress + ResultPanel，编排查询流程

## Phase 4: User Story 2 (P1) — 查看结果和图表交互

**Goal**: 用户能看到表格、图表，能切换图表类型，能导出 PNG
**Independent Test**: 用户能看到表格、图表，能切换图表类型，能导出 PNG

- [ ] T014 [P] [US2] 创建结果表格组件 `frontend/src/components/query/ResultTable.vue`，使用 Element Plus Table 渲染查询结果数据，支持列排序和滚动
- [ ] T015 [P] [US2] 创建 ECharts composable `frontend/src/composables/useChart.ts`，封装 ECharts 实例初始化、setOption、resize 监听、销毁
- [ ] T016 [US2] 创建图表面板组件 `frontend/src/components/query/ChartPanel.vue`，接收 chartOption 渲染 ECharts 图表，try-catch 包裹 setOption 实现降级（无效配置时隐藏图表区域）
- [ ] T017 [US2] 创建图表类型切换组件 `frontend/src/components/query/ChartTypeSwitcher.vue`，按钮组（bar/line/pie），切换时修改本地 chartOption.series[0].type 不重新请求
- [ ] T018 [US2] 创建导出 composable `frontend/src/composables/useExport.ts`，实现 CSV 导出（前端生成 Blob 下载）和 PNG 导出（ECharts getDataURL）
- [ ] T019 [US2] 创建导出按钮组件 `frontend/src/components/query/ExportButtons.vue`，CSV 和 PNG 两个按钮，调用 useExport
- [ ] T020 [US2] 创建口径说明面板 `frontend/src/components/query/ExplanationPanel.vue`，展示使用的表、字段、可信度分数，低可信字段标注警告
- [ ] T021 [US2] 创建结果面板容器 `frontend/src/components/query/ResultPanel.vue`，组合 ResultTable + ChartPanel + ChartTypeSwitcher + ExportButtons + ExplanationPanel

## Phase 5: User Story 3 (P2) — 多轮对话

**Goal**: 第一轮问"上月订单总额"，第二轮问"按部门拆分"，系统能理解关联
**Independent Test**: 连续提问时系统结合上文生成新 SQL

- [ ] T022 [US3] 创建 conversation store `frontend/src/stores/conversation.ts`，实现 state(currentSessionId, messages[], sessions[]), actions(createSession, addMessage, clearSession), getters(recentContext — 最近 5 条消息)
- [ ] T023 [US3] 创建消息项组件 `frontend/src/components/query/MessageItem.vue`，区分 user/assistant 角色样式，assistant 消息包含结果面板
- [ ] T024 [US3] 创建消息列表组件 `frontend/src/components/query/MessageList.vue`，渲染多轮对话消息，自动滚动到底部
- [ ] T025 [US3] 修改 queryTask store 的 submitQuery action，每次请求携带 conversation store 的 recentContext
- [ ] T026 [US3] 在 DataSourceSelector 切换数据源时调用 conversation.createSession(datasourceId) 创建新会话并清空上下文

## Phase 6: User Story 4 (P2) — 用户反馈

**Goal**: 点赞后审计日志中 feedback 字段更新
**Independent Test**: 点赞/点踩操作成功提交

- [ ] T027 [US4] 创建反馈 API 文件 `frontend/src/api/feedback.ts`，封装 POST /api/feedback（赞/踩 + 原因）
- [ ] T028 [US4] 创建反馈按钮组件 `frontend/src/components/query/FeedbackButtons.vue`，赞按钮直接提交，踩按钮弹出 Element Plus Dialog 选择原因后提交

## Phase 7: User Story 5 — 历史会话

- [ ] T029 创建历史会话页面 `frontend/src/views/query/HistoryPage.vue`，展示用户历史会话列表，点击可查看历史消息（只读）

## Phase 8: Polish & Cross-Cutting

- [ ] T030 实现 SSE 断线边界处理：网络断开时展示"连接中断，正在重连..."提示条
- [ ] T031 实现空结果处理：查询结果为空时展示"未查询到数据，建议换个问法"
- [ ] T032 实现快速连续发送防护：前一个查询未完成时禁用发送按钮并展示 loading 状态

## Phase 9: Conversation Enhancement

- [ ] T033 [Frontend] 在 conversation store 中实现会话标题自动生成：首次提问后取问题前 20 字作为标题，调用 PUT /api/conversations/{id}/title 更新
- [ ] T034 [Frontend] 在 HistoryPanel 中添加搜索框，调用 GET /api/conversations?keyword=xxx 模糊搜索会话
- [ ] T035 在 Java 后端 ConversationController 中添加 PUT /api/conversations/{id}/title 和 GET /api/conversations?keyword= 接口

## Dependencies

```
T001 → T002 → T013
T003 → T005 → T007 → T013
T004 → T006, T008, T027
T008 → T009 → T010 → T012 → T013
T011 → T013
T015 → T016 → T017 → T021
T014, T016, T019, T020 → T021 → T013
T022 → T023 → T024 → T025
T009 → T025
```

## Implementation Strategy

MVP-first: Phase 1-4 构成最小可用产品（选数据源 → 提问 → 看结果），Phase 5-6 增强体验，Phase 7-8 完善边界。每个 Phase 完成后可独立验证。
