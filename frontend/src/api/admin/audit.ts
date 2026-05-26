import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface AuditLogVO {
  id: number
  queryTaskId: number
  userId: number
  username?: string
  datasourceId: number
  datasourceName?: string
  question: string
  sqlText?: string
  usedTables?: string
  usedFields?: string
  executionTimeMs?: number
  rowCount?: number
  isSuccess: boolean
  errorMessage?: string
  isSlow: boolean
  userFeedback?: string
  createdAt: string
}

export interface AuditStatsVO {
  totalQueries: number
  successCount: number
  successRate: number
  avgExecutionTimeMs: number
  slowQueryCount: number
  slowQueryRate: number
}

export interface LineageTableVO {
  queryTaskId: number
  sourceTable: string
  targetName?: string
  relationType: string
  question?: string
  createdAt: string
}

export interface LineageColumnVO {
  queryTaskId: number
  sourceTable: string
  sourceColumn: string
  expression?: string
  aliasName?: string
  question?: string
  createdAt: string
}

export interface ImpactAnalysisVO {
  dependentQueryCount: number
  recentQueryTaskIds: number[]
  tableName: string
  columnName: string
}

export interface AlertRule {
  id: number
  metric: string
  threshold: number
  operator: string
  notificationType: string
  enabled: boolean
  createdAt: string
}

export interface LlmUsageStatsVO {
  totalCalls: number
  totalTokens: number
  totalCost: number
  avgDailyCalls: number
  avgDailyCost: number
}

// 审计日志
export async function listAuditLogs(params: Record<string, any>) {
  const { data } = await http.get<ApiResult<PageResult<AuditLogVO>>>('/api/admin/audit-logs', { params })
  return data
}

export async function getAuditLogDetail(id: number) {
  const { data } = await http.get<ApiResult<AuditLogVO>>(`/api/admin/audit-logs/${id}`)
  return data
}

export async function listSlowQueries(params: { page?: number; pageSize?: number }) {
  const { data } = await http.get<ApiResult<PageResult<AuditLogVO>>>('/api/admin/audit-logs/slow-queries', { params })
  return data
}

export async function getAuditStats(params: { datasourceId?: number; days?: number }) {
  const { data } = await http.get<ApiResult<AuditStatsVO>>('/api/admin/audit-logs/stats', { params })
  return data
}

export async function promoteTemplate(id: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/audit-logs/${id}/promote-template`)
  return data
}

// 血缘
export async function queryTableLineage(tableName: string) {
  const { data } = await http.get<ApiResult<LineageTableVO[]>>(`/api/lineage/table/${tableName}`)
  return data
}

export async function queryColumnLineage(tableName: string, columnName: string) {
  const { data } = await http.get<ApiResult<LineageColumnVO[]>>(`/api/lineage/column/${tableName}/${columnName}`)
  return data
}

export async function analyzeImpact(tableName: string, columnName: string) {
  const { data } = await http.get<ApiResult<ImpactAnalysisVO>>(`/api/lineage/impact/${tableName}/${columnName}`)
  return data
}

// 告警规则
export async function listAlertRules(params: { page?: number; pageSize?: number }) {
  const { data } = await http.get<ApiResult<PageResult<AlertRule>>>('/api/admin/alert-rules', { params })
  return data
}

export async function createAlertRule(payload: Partial<AlertRule>) {
  const { data } = await http.post<ApiResult<AlertRule>>('/api/admin/alert-rules', payload)
  return data
}

export async function updateAlertRule(id: number, payload: Partial<AlertRule>) {
  const { data } = await http.put<ApiResult<AlertRule>>(`/api/admin/alert-rules/${id}`, payload)
  return data
}

export async function toggleAlertRule(id: number) {
  const { data } = await http.patch<ApiResult<null>>(`/api/admin/alert-rules/${id}/toggle`)
  return data
}

// LLM 使用统计
export async function getLlmUsageStats(days = 30) {
  const { data } = await http.get<ApiResult<LlmUsageStatsVO>>('/api/quotas/llm-usage', { params: { days } })
  return data
}
