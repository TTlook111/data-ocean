# Implementation Plan: 前端问答端模块

**Branch**: `001-full-module-specs` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

## Summary

前端问答端是面向业务用户的自然语言查询界面，提供数据源选择、自然语言输入、SSE 实时进度、结果表格/图表展示、多轮对话和用户反馈。与后台治理端共用同一 Vue 项目，通过路由 `/query/*` 区分。

## Technical Context

**Language/Version**: TypeScript (Vue 3 + Vite)

**Primary Dependencies**: Vue 3, Vue Router, Pinia, Axios, Element Plus, ECharts, EventSource

**Testing**: Vitest + Vue Test Utils

**Target Platform**: Modern browsers (Chrome/Edge/Firefox latest 2 versions)

**Performance Goals**: 首屏加载 < 3s, 图表渲染 < 500ms, SSE 断线重连 < 5s

## Component Tree

```text
src/
├── views/query/
│   ├── QueryLayout.vue              # 问答端布局容器
│   ├── QueryPage.vue                # 主查询页面
│   └── HistoryPage.vue              # 历史会话列表
├── components/query/
│   ├── DataSourceSelector.vue       # 数据源下拉选择器
│   ├── ChatInput.vue                # 自然语言输入框 + 发送按钮
│   ├── MessageList.vue              # 消息列表（多轮对话）
│   ├── MessageItem.vue              # 单条消息（用户/AI）
│   ├── QueryProgress.vue            # SSE 实时进度展示
│   ├── ResultPanel.vue              # 结果面板容器
│   ├── ResultTable.vue              # 数据表格（Element Plus Table）
│   ├── ChartPanel.vue               # ECharts 图表面板
│   ├── ChartTypeSwitcher.vue        # 图表类型切换按钮组
│   ├── ExplanationPanel.vue         # 口径说明和溯源面板
│   ├── FeedbackButtons.vue          # 赞/踩按钮 + 原因弹窗
│   └── ExportButtons.vue            # 导出按钮（CSV/PNG）
├── stores/
│   ├── auth.ts                      # 登录态、用户信息、权限
│   ├── datasource.ts                # 数据源列表、当前选中
│   ├── conversation.ts              # 会话管理、消息历史
│   └── queryTask.ts                 # 查询任务状态、SSE 连接
├── composables/
│   ├── useSSE.ts                    # SSE 连接封装（重连、降级）
│   ├── useChart.ts                  # ECharts 实例管理
│   └── useExport.ts                 # 导出工具函数
├── api/
│   ├── query.ts                     # 查询相关 API
│   ├── datasource.ts                # 数据源 API
│   └── feedback.ts                  # 反馈 API
└── router/
    └── query.ts                     # /query/* 路由配置
```

## Store Design

### auth store
- state: token, user (id, username, realName, roles, permissions)
- actions: login(), logout(), refreshUser()
- getters: isAuthenticated, hasPermission(code)

### datasource store
- state: datasources[], currentDatasourceId, loading
- actions: fetchDatasources(), selectDatasource(id)
- getters: currentDatasource, enabledDatasources

### conversation store
- state: currentSessionId, messages[] (max 5 recent), sessions[]
- actions: createSession(datasourceId), addMessage(msg), clearSession()
- getters: recentContext (last 5 messages for API request)

### queryTask store
- state: currentTaskId, status (idle/pending/streaming/done/error), progress{}, result{}
- actions: submitQuery(question), connectSSE(taskId), cancelQuery()
- getters: isQuerying, progressSteps, queryResult

## Implementation Phases

### Phase 1: 基础布局和数据源选择

- 创建 QueryLayout 和路由配置
- 实现 DataSourceSelector（调用 GET /api/datasources）
- 实现 auth store 和 datasource store
- 路由守卫：未登录跳转 /login

### Phase 2: 查询提交和 SSE 进度

- 实现 ChatInput 组件
- 实现 queryTask store：POST /api/query/ask → taskId
- 实现 useSSE composable：EventSource 连接 /api/query/stream/{taskId}
- SSE 事件处理：schema_retrieving → sql_generating → sql_validating → executing → visualizing → done
- 断线重连：5s 内自动重连，3 次失败后降级为轮询（GET /api/query/tasks/{taskId}）

### Phase 3: 结果展示

- 实现 ResultTable：Element Plus Table 渲染数据
- 实现 ChartPanel：ECharts 渲染 chartOption
- 实现 ChartTypeSwitcher：bar/line/pie 切换（修改 chartOption.series[0].type）
- ECharts 配置无效时降级为纯表格（try-catch 包裹 setOption）
- 实现 ExplanationPanel：展示使用的表、字段、可信度

### Phase 4: 多轮对话和反馈

- 实现 MessageList 和 MessageItem
- conversation store 维护最近 5 条消息
- 每次查询请求携带 recentContext
- 切换数据源时自动创建新会话
- 实现 FeedbackButtons：赞直接提交，踩弹出原因选择
- 实现 ExportButtons：CSV（前端生成）、PNG（ECharts getDataURL）

## Key Design Decisions

1. **SSE 优先，轮询降级**: EventSource 原生支持自动重连，但需要处理跨域和 Token 传递（通过 URL query param 传 token）
2. **ECharts 容错**: chartOption 渲染失败不报错，降级为纯表格展示
3. **会话 24h 过期**: 前端不做过期判断，由后端返回 SESSION_EXPIRED 错误时自动创建新会话
4. **CSV 导出前端生成**: 数据量不大（LIMIT 10000），前端直接生成 CSV 避免额外后端接口
5. **图表类型切换不重新请求**: 仅修改本地 chartOption 的 series type，数据不变
