import { http } from './http'

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface QueryAskParams {
  datasourceId: number
  question: string
  conversationId?: number
}

export interface QueryAskResult {
  taskId: string
  conversationId: number
}

export interface QueryTaskResult {
  taskId: string
  status: string
  question: string
  rewrittenQuery?: string
  sql?: string
  sqlExplanation?: string
  data?: Record<string, unknown>[]
  columns?: { name: string; type: string; comment?: string }[]
  rowCount?: number
  chartConfig?: Record<string, unknown>
  usedTables?: string[]
  usedColumns?: string[]
  errorMessage?: string
  retryCount?: number
  totalTimeMs?: number
  suggestedQuestions?: string[]
  canExport?: boolean
}

export async function submitQuery(params: QueryAskParams) {
  const { data } = await http.post<ApiResult<QueryAskResult>>('/api/query/ask', params)
  return data
}

export async function getTaskResult(taskId: string) {
  const { data } = await http.get<ApiResult<QueryTaskResult>>(`/api/query/tasks/${taskId}`)
  return data
}

export async function cancelTask(taskId: string) {
  const { data } = await http.post<ApiResult<void>>(`/api/query/tasks/${taskId}/cancel`)
  return data
}

export async function submitQueryFeedback(taskId: string, feedbackType: 'LIKE' | 'DISLIKE') {
  const { data } = await http.post<ApiResult<void>>(`/api/query/tasks/${taskId}/feedback`, { feedbackType })
  return data
}
