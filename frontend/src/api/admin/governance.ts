import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface QualityCheckResult {
  snapshotId: number
  snapshotStatus?: string
  requestedScope?: 'FULL' | 'DIMENSION' | 'TABLE'
  checkBatchId?: number
  status: 'SUCCESS' | 'PARTIAL' | 'FAILED' | string
  complete: boolean
  startedAt?: string
  finishedAt?: string
  executedRuleCount: number
  failedRuleCount: number
  notApplicableRuleCount: number
  errorSummary?: string
  qualityScore?: number | null
  dimensionScores: Record<string, number>
  totalIssues: number
  issueCount: Record<string, number>
}

export interface QualityCheckBatchRule {
  batchId?: number
  batchStartedAt?: string
  batchStatus?: string
  ruleId?: number
  ruleCode?: string
  templateCode?: string
  ruleVersion?: number
  ruleName: string
  description?: string
  dimension: string
  severity: string
  deductionPoints: number
  scopeType: 'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'
  datasourceId?: number
  targetTableName?: string
  targetColumnName?: string
  parameters: Record<string, unknown>
  status: 'SUCCESS' | 'FAILURE' | 'NOT_APPLICABLE' | 'NOT_EXECUTED'
  reason?: string
  targetCount: number
  checkedTargetCount: number
  issueCount: number
}

export interface QualityCheckBatch {
  id: number
  datasourceId?: number
  snapshotId: number
  requestedScope?: 'FULL' | 'DIMENSION' | 'TABLE'
  requestedDimensions?: string[]
  requestedTableNames?: string[]
  status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'LEGACY'
  complete: boolean
  totalScore?: number | null
  dimensionScores?: Record<string, number>
  ruleCount?: number | null
  executedRuleCount?: number | null
  failedRuleCount?: number | null
  notApplicableRuleCount?: number | null
  errorSummary?: string
  startedAt: string
  finishedAt?: string
  legacyLimited?: boolean
  rules: QualityCheckBatchRule[]
}

export interface QualityRuleParameterDefinition {
  key: string
  label: string
  type: 'INTEGER' | 'NUMBER' | 'BOOLEAN'
  minimum?: number
  maximum?: number
  defaultValue: number | boolean
}

export interface QualityRuleTemplate {
  code: string
  name: string
  dimension: string
  targetType: 'TABLE' | 'COLUMN' | 'RELATION'
  checkType: 'SCHEMA' | 'DATA'
  description: string
  allowedScopes: Array<'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'>
  parameters: QualityRuleParameterDefinition[]
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
  templateCode?: string
  ruleName: string
  dimension: string
  severity: string
  description: string
  enabled: number
  deductionPoints: number
  builtin: number
  checkTarget: string
  checkType: string
  scopeType: 'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'
  datasourceId?: number
  targetTableName?: string
  targetColumnName?: string
  parameters: Record<string, unknown>
  configVersion: number
  supported: boolean
  unsupportedReason?: string
  overridden?: boolean
  overriddenBy?: string
  deletedAt?: string
  updatedAt?: string
}

export interface QualityRuleSummary {
  enabledCount: number
  applicableCount: number
  unsupportedCount: number
  overriddenCount: number
  executedCount?: number | null
  executionBatchStatus?: string | null
}

export interface QualityIssueSummary {
  totalUnresolved: number
  statusCounts: Record<'OPEN' | 'CONFIRMED' | 'REOPENED', number>
  recentIssues: QualityIssueItem[]
}

export interface QualityRulePayload {
  ruleName: string
  templateCode: string
  description?: string
  scopeType: 'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'
  datasourceId?: number
  targetTableName?: string
  targetColumnName?: string
  parameters?: Record<string, unknown>
  severity: string
  deductionPoints: number
  enabled?: boolean
}

export interface QualityRuleRevision {
  id: number
  ruleId: number
  ruleCode: string
  templateCode?: string
  configVersion: number
  action: string
  ruleName: string
  dimension: string
  severity: string
  description?: string
  scopeType: 'GLOBAL' | 'DATASOURCE' | 'TABLE' | 'COLUMN'
  datasourceId?: number
  targetTableName?: string
  targetColumnName?: string
  parameters: Record<string, unknown>
  enabled: number
  deductionPoints: number
  deleted: boolean
  changedBy?: number
  changedAt: string
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

export async function getLatestQualityCheckBatch(snapshotId: number) {
  const { data } = await http.get<ApiResult<QualityCheckBatch | null>>(`/api/admin/snapshots/${snapshotId}/quality-check-batches/latest`)
  return data
}

export async function getQualityRuleSummary(snapshotId: number) {
  const { data } = await http.get<ApiResult<QualityRuleSummary>>(`/api/admin/snapshots/${snapshotId}/quality-rules/summary`)
  return data
}

export async function getSnapshotQualityIssueSummary(snapshotId: number, recentLimit = 5) {
  const { data } = await http.get<ApiResult<QualityIssueSummary>>(`/api/admin/snapshots/${snapshotId}/quality-issues/summary`, { params: { recentLimit } })
  return data
}

export async function listQualityIssues(snapshotId: number | undefined, params: {
  dimension?: string; severity?: string; status?: string; tableName?: string
  /** 按责任人过滤（2026-09-12 后端新增该参数） */
  assigneeId?: number
  page?: number; size?: number
}) {
  const url = snapshotId ? `/api/admin/snapshots/${snapshotId}/quality-issues` : '/api/admin/quality-issues'
  const { data } = await http.get<ApiResult<PageResult<QualityIssueItem>>>(url, { params })
  return data
}

export async function handleIssue(issueId: number, payload: { status: string; resolutionNote?: string }) {
  const { data } = await http.patch<ApiResult<null>>(`/api/admin/quality-issues/${issueId}/status`, payload)
  return data
}

/**
 * 批量处理结果。
 *
 * 后端对状态不允许流转的条目会跳过，2026-09-12 起返回跳过明细而不是仅成功计数——
 * 此前前端只能显示「已处理 N 条」，无法告知用户有多少条被跳过、原因是什么。
 */
export interface BatchHandleResult {
  updated: number
  skipped: number
  skippedIssues: Array<{ issueId: number; reason: string }>
}

export async function batchHandleIssues(payload: { issueIds: number[]; status: string }) {
  const { data } = await http.patch<ApiResult<BatchHandleResult>>('/api/admin/quality-issues/batch-status', payload)
  return data
}

export async function assignIssue(issueId: number, assigneeId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/quality-issues/${issueId}/assign`, { assigneeId })
  return data
}

export async function listQualityRules(params?: {
  datasourceId?: number; scopeType?: string; dimension?: string; templateCode?: string; ruleName?: string; enabled?: boolean; deleted?: boolean
}) {
  const { data } = await http.get<ApiResult<QualityRule[]>>('/api/admin/quality-rules', { params })
  return data
}

export async function listQualityRuleTemplates() {
  const { data } = await http.get<ApiResult<QualityRuleTemplate[]>>('/api/admin/quality-rules/templates')
  return data
}

export async function createQualityRule(payload: QualityRulePayload) {
  const { data } = await http.post<ApiResult<QualityRule>>('/api/admin/quality-rules', payload)
  return data
}

export async function getQualityRule(ruleId: number, includeDeleted = false) {
  const { data } = await http.get<ApiResult<QualityRule>>(`/api/admin/quality-rules/${ruleId}`, { params: { includeDeleted } })
  return data
}

export async function getQualityRuleHistory(ruleId: number) {
  const { data } = await http.get<ApiResult<QualityCheckBatchRule[]>>(`/api/admin/quality-rules/${ruleId}/history`)
  return data
}

export async function getQualityRuleRevisions(ruleId: number) {
  const { data } = await http.get<ApiResult<QualityRuleRevision[]>>(`/api/admin/quality-rules/${ruleId}/revisions`)
  return data
}

export async function editQualityRule(ruleId: number, payload: QualityRulePayload) {
  const { data } = await http.put<ApiResult<QualityRule>>(`/api/admin/quality-rules/${ruleId}`, payload)
  return data
}

export async function copyQualityRule(ruleId: number) {
  const { data } = await http.post<ApiResult<QualityRule>>(`/api/admin/quality-rules/${ruleId}/copy`)
  return data
}

export async function updateRuleEnabled(ruleId: number, enabled: boolean) {
  const { data } = await http.patch<ApiResult<null>>(`/api/admin/quality-rules/${ruleId}/enabled`, { enabled })
  return data
}

export async function deleteQualityRule(ruleId: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/quality-rules/${ruleId}`)
  return data
}

export async function restoreQualityRule(ruleId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/quality-rules/${ruleId}/restore`)
  return data
}

export async function updateTableGovernanceStatus(snapshotId: number, tableName: string, payload: { governanceStatus: string; remark?: string }) {
  const { data } = await http.patch<ApiResult<Record<string, string>>>(`/api/admin/snapshots/${snapshotId}/tables/${encodeURIComponent(tableName)}/governance-status`, payload)
  return data
}

export async function updateColumnGovernanceStatus(snapshotId: number, columnId: number, payload: { governanceStatus: string; remark?: string }) {
  const { data } = await http.patch<ApiResult<Record<string, string>>>(`/api/admin/snapshots/${snapshotId}/columns/${columnId}/governance-status`, payload)
  return data
}

export async function batchUpdateGovernanceStatus(snapshotId: number, tableName: string, payload: { governanceStatus: string; remark?: string; excludeColumns?: string[] }) {
  const { data } = await http.patch<ApiResult<{ updated: number; excluded: number }>>(`/api/admin/snapshots/${snapshotId}/tables/${encodeURIComponent(tableName)}/batch-governance-status`, payload)
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
  const { data } = await http.get<ApiResult<ColumnMetaItem[]>>(`/api/admin/metadata/snapshots/${snapshotId}/tables/${encodeURIComponent(tableName)}/columns`)
  return data
}
