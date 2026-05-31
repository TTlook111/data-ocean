import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface QualityCheckResult {
  snapshotId: number
  qualityScore: number
  dimensionScores: Record<string, number>
  totalIssues: number
  issueCount: Record<string, number>
}

export interface QualityIssueItem {
  id: number
  snapshotId: number
  datasourceId?: number
  datasourceName?: string
  dimension: string
  severity: string
  tableName: string
  columnName?: string
  issueDescription: string
  suggestion?: string
  status: string
  assigneeId?: number
  assigneeName?: string
  createdAt: string
  resolvedAt?: string
}

export interface QualityRule {
  id: number
  ruleCode: string
  ruleName: string
  dimension: string
  severity: string
  description: string
  enabled: number
  deductionPoints: number
  builtin: number
}

export interface ReviewRecord {
  id: number
  targetType: string
  tableName: string
  columnName?: string
  action: string
  oldStatus?: string
  newStatus?: string
  operatorName: string
  remark?: string
  createdAt: string
}

export async function triggerQualityCheck(snapshotId: number, payload?: { dimensions?: string[]; tableNames?: string[] }) {
  const { data } = await http.post<ApiResult<QualityCheckResult>>(`/api/admin/snapshots/${snapshotId}/quality-check`, payload || {})
  return data
}

export async function listQualityIssues(snapshotId: number | undefined, params: {
  dimension?: string; severity?: string; status?: string; tableName?: string; page?: number; size?: number
}) {
  const url = snapshotId ? `/api/admin/snapshots/${snapshotId}/quality-issues` : '/api/admin/quality-issues'
  const { data } = await http.get<ApiResult<PageResult<QualityIssueItem>>>(url, { params })
  return data
}

export async function handleIssue(issueId: number, payload: { status: string; resolutionNote?: string }) {
  const { data } = await http.patch<ApiResult<null>>(`/api/admin/quality-issues/${issueId}/status`, payload)
  return data
}

export async function batchHandleIssues(payload: { issueIds: number[]; status: string }) {
  const { data } = await http.patch<ApiResult<{ updated: number }>>('/api/admin/quality-issues/batch-status', payload)
  return data
}

export async function assignIssue(issueId: number, assigneeId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/quality-issues/${issueId}/assign`, { assigneeId })
  return data
}

export async function listQualityRules() {
  const { data } = await http.get<ApiResult<QualityRule[]>>('/api/admin/quality-rules')
  return data
}

export async function updateRuleEnabled(ruleId: number, enabled: boolean) {
  const { data } = await http.patch<ApiResult<null>>(`/api/admin/quality-rules/${ruleId}`, { enabled })
  return data
}

export async function updateTableGovernanceStatus(snapshotId: number, tableName: string, payload: { governanceStatus: string; remark?: string }) {
  const { data } = await http.patch<ApiResult<Record<string, string>>>(`/api/admin/snapshots/${snapshotId}/tables/${tableName}/governance-status`, payload)
  return data
}

export async function updateColumnGovernanceStatus(snapshotId: number, columnId: number, payload: { governanceStatus: string; remark?: string }) {
  const { data } = await http.patch<ApiResult<Record<string, string>>>(`/api/admin/snapshots/${snapshotId}/columns/${columnId}/governance-status`, payload)
  return data
}

export async function batchUpdateGovernanceStatus(snapshotId: number, tableName: string, payload: { governanceStatus: string; remark?: string; excludeColumns?: string[] }) {
  const { data } = await http.patch<ApiResult<{ updated: number; excluded: number }>>(`/api/admin/snapshots/${snapshotId}/tables/${tableName}/batch-governance-status`, payload)
  return data
}

export async function listReviewRecords(snapshotId: number, params: { tableName?: string; page?: number; size?: number }) {
  const { data } = await http.get<ApiResult<PageResult<ReviewRecord>>>(`/api/admin/snapshots/${snapshotId}/review-records`, { params })
  return data
}

export interface TableMetaItem {
  id: number
  tableName: string
  tableComment?: string
  governanceStatus: string
  rowCountEstimate?: number
}

export interface ColumnMetaItem {
  id: number
  columnName: string
  dataType: string
  columnComment?: string
  governanceStatus: string
  ordinalPosition: number
}

export async function listSnapshotTables(snapshotId: number) {
  const { data } = await http.get<ApiResult<TableMetaItem[]>>(`/api/admin/metadata/snapshots/${snapshotId}/tables`)
  return data
}

export async function listSnapshotTableColumns(snapshotId: number, tableName: string) {
  const { data } = await http.get<ApiResult<ColumnMetaItem[]>>(`/api/admin/metadata/snapshots/${snapshotId}/tables/${tableName}/columns`)
  return data
}
