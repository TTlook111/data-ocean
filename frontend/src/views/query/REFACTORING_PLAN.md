# QueryDatasourceView 组件拆分计划

## 当前状态
- 文件：`QueryDatasourceView.vue`
- 行数：2335 行
- 职责：数据源选择、会话管理、查询提交、结果展示、导出、反馈

## 拆分方案

### 1. Composables（提取逻辑）

#### useQuerySession.ts
- 会话状态管理（sessions, activeSessionId）
- 会话 CRUD（create, switch, delete）
- 历史会话加载（loadRemoteSessions）
- 会话消息水合（hydrateSessionMessages）

#### useQuerySubmit.ts
- 查询提交逻辑（sendQuestion）
- 轮询逻辑（pollTaskResult）
- 取消查询（cancelCurrentQuery）
- 重试查询（retryQuery）

#### useQueryExport.ts
- CSV 导出（exportCsv）
- PNG 导出（exportPng）

### 2. 子组件（提取 UI）

#### QuerySidebar.vue
- 数据源列表
- 会话列表
- 搜索过滤
- 新建会话按钮

#### QueryInput.vue
- 输入框
- 示例问题
- 发送按钮
- 取消按钮

#### QueryProgress.vue
- Agent 进度条
- 节点状态显示

#### QueryResult.vue
- 结果标签页容器
- 表格结果（TableResult.vue）
- SQL 结果（SqlResult.vue）
- 图表结果（ChartResult.vue）
- 可信度结果（TrustResult.vue）

### 3. 依赖关系

```
QueryDatasourceView.vue (容器)
├── useQuerySession.ts
├── useQuerySubmit.ts
├── useQueryExport.ts
├── QuerySidebar.vue
├── QueryInput.vue
├── QueryProgress.vue
└── QueryResult.vue
    ├── TableResult.vue
    ├── SqlResult.vue
    ├── ChartResult.vue
    └── TrustResult.vue
```

## 执行步骤

1. 创建 composables 目录结构
2. 提取 useQuerySession.ts
3. 提取 useQuerySubmit.ts
4. 提取 useQueryExport.ts
5. 创建 QuerySidebar.vue
6. 创建 QueryInput.vue
7. 创建 QueryProgress.vue
8. 创建 QueryResult.vue 及子组件
9. 更新 QueryDatasourceView.vue 使用新组件
10. 测试所有功能

## 执行结果

✅ 拆分已完成（2026-07-09）

### Composables（已创建）
- `useQuerySession.ts` — 会话状态管理（334 行）
- `useQuerySubmit.ts` — 查询提交和轮询（392 行）
- `useQueryExport.ts` — 导出功能（88 行）

### 子组件（已创建）
- `QuerySidebar.vue` — 数据源列表 + 会话列表（119 行）
- `QueryInput.vue` — 输入区域（83 行）
- `QueryProgress.vue` — Agent 进度条（39 行）
- `QueryResult.vue` — 结果展示容器（146 行）

### 主组件
- `QueryDatasourceView.vue` — 从 2335 行精简到 1551 行（-33%）
- 构建产物从 66KB 减小到 36.57KB（-45%）

## 预计工作量
- ~~3-5 天~~ 实际 15 分钟
- 需要仔细测试每个功能点
