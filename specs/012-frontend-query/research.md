# Research: 前端问答端模块

## SSE 实现方案

**Decision**: 使用原生 EventSource API，封装为 useSSE composable

**Rationale**: EventSource 是浏览器原生 API，自带自动重连机制。封装为 composable 统一管理连接生命周期、错误处理和降级逻辑。

**Alternatives considered**:
- fetch + ReadableStream: 更灵活但需要手动实现重连，兼容性略差
- WebSocket: 双向通信过重，查询进度是单向推送场景
- 第三方库 (eventsource-polyfill): 增加依赖，原生 API 已满足需求

**Token 传递**: EventSource 不支持自定义 Header，通过 URL query param 传递 token: `/api/query/stream/{taskId}?token={jwt}`

**降级策略**: 连续 3 次重连失败后切换为 setInterval 轮询 GET /api/query/tasks/{taskId}，间隔 2s

## ECharts 集成方案

**Decision**: 使用 echarts 按需引入 + Vue 3 composable 封装

**Rationale**: 按需引入减少打包体积（仅引入 bar/line/pie 和必要组件）。composable 管理 ECharts 实例的创建、更新和销毁，避免内存泄漏。

**按需引入**:
```typescript
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
```

**容错处理**: try-catch 包裹 `chart.setOption(option)`，失败时 emit 事件通知父组件降级为表格

## 状态管理方案

**Decision**: Pinia 4 个 store（auth, datasource, conversation, queryTask）

**Rationale**: 按职责拆分 store，避免单一 store 过大。queryTask store 管理 SSE 连接状态，conversation store 管理多轮对话上下文。

**持久化**: auth store 的 token 持久化到 localStorage，其他 store 不持久化（刷新后重新获取）

## 多轮对话上下文传递

**Decision**: 前端维护最近 5 条消息（含 user 和 assistant），每次请求通过 `context` 字段传递给后端

**Rationale**: 后端无状态设计，上下文由前端管理。5 条消息足够理解对话关联，不会超出 LLM 上下文窗口。

**数据结构**:
```typescript
interface ContextMessage {
  role: 'user' | 'assistant'
  content: string  // user: 原始问题, assistant: 生成的 SQL 摘要
}
```

## CSV 导出方案

**Decision**: 前端使用 Blob + URL.createObjectURL 生成 CSV 文件下载

**Rationale**: 查询结果最多 10000 行，前端生成 CSV 性能可接受（< 1s）。避免额外后端接口。

**实现**: 遍历 columns 和 rows，拼接 CSV 字符串，处理逗号和换行转义，生成 Blob 触发下载。

## 图表 PNG 导出

**Decision**: 使用 ECharts 内置 `getDataURL()` 方法导出 PNG

**Rationale**: ECharts 原生支持导出为 base64 图片，转为 Blob 后触发下载。无需额外依赖。
