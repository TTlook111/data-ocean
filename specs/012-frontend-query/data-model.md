# Data Model: 前端问答端模块

前端模块不定义数据库表，但需要定义前端状态模型和 API 数据结构。

## Frontend State Models (TypeScript)

### Auth State

```typescript
interface AuthState {
  token: string | null
  user: UserInfo | null
}

interface UserInfo {
  id: number
  username: string
  realName: string
  roles: string[]
  permissions: string[]
}
```

### Datasource State

```typescript
interface DatasourceState {
  datasources: Datasource[]
  currentDatasourceId: number | null
  loading: boolean
}

interface Datasource {
  id: number
  name: string
  dbType: string
  host: string
  dbName: string
  status: 'ENABLED' | 'DISABLED'
  tableCount: number
}
```

### Conversation State

```typescript
interface ConversationState {
  currentDatasourceId: number | null
  currentSessionId: string | null
  messagesBySession: Record<string, Message[]>
  sessionsByDatasource: Record<number, SessionSummary[]>
  searchKeyword: string
}

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  queryTaskId?: number
  result?: QueryResult
  timestamp: number
}

interface SessionSummary {
  sessionId: string
  datasourceId: number
  datasourceName: string
  title: string
  messageCount: number
  lastQuestion?: string
  lastMessageAt: string
  createdAt: string
}
```

会话状态以 `datasourceId` 为第一层分组。左侧历史栏只读取 `sessionsByDatasource[currentDatasourceId]`，切换数据源时必须同步切换 `currentDatasourceId` 并清空当前输入态，不能继续使用上一数据源的 `currentSessionId` 或上下文。

### QueryTask State

```typescript
interface QueryTaskState {
  currentTaskId: number | null
  status: 'idle' | 'pending' | 'streaming' | 'done' | 'error'
  progress: QueryProgress
  result: QueryResult | null
  error: string | null
}

interface QueryProgress {
  currentStep: string
  steps: ProgressStep[]
  startedAt: number
}

interface ProgressStep {
  name: string  // schema_retrieving | sql_generating | sql_validating | executing | visualizing
  status: 'pending' | 'running' | 'done' | 'error'
  message?: string
  startedAt?: number
  completedAt?: number
}

interface QueryResult {
  sql: string
  columns: ColumnDef[]
  rows: Record<string, any>[]
  rowCount: number
  executionTimeMs: number
  chartOption: EChartsOption | null
  explanation: QueryExplanation
}

interface ColumnDef {
  name: string
  type: string
  label: string
}

interface QueryExplanation {
  usedTables: TableRef[]
  usedColumns: ColumnRef[]
  confidenceSummary: string
  sqlExplanation: string
}

interface TableRef {
  tableName: string
  tableComment: string
}

interface ColumnRef {
  tableName: string
  columnName: string
  columnComment: string
  confidence: number
  confidenceLevel: 'HIGH' | 'MEDIUM' | 'LOW'
}
```

## SSE Event Schema

SSE 事件流格式（服务端推送）:

```
event: progress
data: {"step":"schema_retrieving","status":"running","message":"正在检索相关表结构..."}

event: progress
data: {"step":"schema_retrieving","status":"done","message":"已召回 5 张相关表"}

event: progress
data: {"step":"sql_generating","status":"running","message":"正在生成 SQL..."}

event: progress
data: {"step":"sql_generating","status":"done","message":"SQL 生成完成"}

event: progress
data: {"step":"sql_validating","status":"running","message":"正在校验 SQL 安全性..."}

event: progress
data: {"step":"executing","status":"running","message":"正在执行查询..."}

event: result
data: {"sql":"SELECT ...","columns":[...],"rows":[...],"chartOption":{...},"explanation":{...}}

event: error
data: {"code":"SQL_VALIDATION_FAILED","message":"SQL 包含不允许的操作"}

event: done
data: {}
```

## API Request/Response Models

### POST /api/query/ask

```typescript
// Request
interface QueryAskRequest {
  datasourceId: number
  question: string
  sessionId?: string
  context?: ContextMessage[]
}

interface ContextMessage {
  role: 'user' | 'assistant'
  content: string
}

// Response 202
interface QueryAskResponse {
  taskId: number
  sessionId: string
}
```

### GET /api/query/tasks/{taskId}

```typescript
// Response (polling fallback)
interface QueryTaskResponse {
  taskId: number
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  progress: ProgressStep[]
  result?: QueryResult
  error?: string
}
```

### GET /api/conversations

```typescript
// Request query
interface ConversationListQuery {
  datasourceId: number
  keyword?: string
  page?: number
  pageSize?: number
}

// Response
interface ConversationListResponse {
  records: SessionSummary[]
  total: number
  page: number
  pageSize: number
}
```
