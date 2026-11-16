import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface VersionHistoryItem {
  snapshotId: number
  datasourceId?: number
  datasourceName?: string
  snapshotVersion: number
  status: string
  qualityScore?: number
  tableCount: number
  columnCount: number
  schemaHash?: string
  createdAt: string
  publishedAt?: string
  expiredAt?: string
  reviewedBy?: string
}

export interface AuditLogItem {
  id: number
  action: string
  oldStatus?: string
  newStatus?: string
  operatorName: string
  reason?: string
  createdAt: string
}

export interface StatusChangePayload {
  expectedStatus: string
  targetStatus: string
  reason?: string
}

export type ReleaseGateItemStatus = 'PASS' | 'FAIL' | 'WARN'
export type ReleaseGateCategory = 'SOURCE' | 'STRUCTURE' | 'SEMANTIC'

export interface ReleaseGateItem {
  code: string
  category: ReleaseGateCategory
  severity: string
  status: ReleaseGateItemStatus
  blocksApproval: boolean
  blocksPublish: boolean
  count: number
  message: string
}

export interface ReleaseGate {
  snapshotId: number
  datasourceId?: number
  snapshotStatus: string
  approvable: boolean
  publishable: boolean
  blockingCount: number
  warningCount: number
  latestCheckBatchId?: number | null
  unresolvedSourceHighByStatus?: Record<'OPEN' | 'CONFIRMED' | 'REOPENED', number>
  unresolvedSourceHigh: number
  unresolvedSemanticHigh: number
  validTableCount: number
  evaluatedAt?: string
  items: ReleaseGateItem[]
}

export async function listVersionHistory(datasourceId: number | undefined, params: { page?: number; size?: number }) {
  const url = datasourceId ? `/api/admin/datasources/${datasourceId}/version-history` : '/api/admin/version-history'
  const { data } = await http.get<ApiResult<PageResult<VersionHistoryItem>>>(url, { params })
  return data
}

export async function getPublishedSnapshot(datasourceId: number) {
  const { data } = await http.get<ApiResult<VersionHistoryItem | null>>(
    `/api/admin/datasources/${datasourceId}/published-snapshot`
  )
  return data
}

export async function listSnapshotAuditLogs(snapshotId: number, params: { page?: number; size?: number }) {
  const { data } = await http.get<ApiResult<PageResult<AuditLogItem>>>(
    `/api/admin/snapshots/${snapshotId}/audit-logs`, { params }
  )
  return data
}

export async function changeSnapshotStatus(snapshotId: number, payload: StatusChangePayload) {
  const { data } = await http.patch<ApiResult<null>>(
    `/api/admin/snapshots/${snapshotId}/status`, payload
  )
  return data
}

export async function getReleaseGate(snapshotId: number) {
  const { data } = await http.get<ApiResult<ReleaseGate>>(
    `/api/admin/snapshots/${snapshotId}/release-gate`
  )
  return data
}

export interface PublicationCondition { expectedStatus: string; expectedPublishedSnapshotId: number | null }

/** 发布和撤回冻结用户确认时的正式指针，显式 null 表示当时没有正式版本。 */
export async function publishSnapshot(snapshotId: number, condition: PublicationCondition) {
  const { data } = await http.post<ApiResult<null>>(
    `/api/admin/snapshots/${snapshotId}/publish`, condition
  )
  return data
}

export async function revokeSnapshot(snapshotId: number, condition: PublicationCondition & { reason: string }) {
  const { data } = await http.post<ApiResult<null>>(
    `/api/admin/snapshots/${snapshotId}/revoke`, { targetStatus: 'APPROVED', ...condition }
  )
  return data
}

export interface DiffColumn { tableName: string; columnName: string; oldType?: string; newType?: string; oldComment?: string; newComment?: string }
export interface VersionDiff {
  datasourceId: number
  oldSnapshotId: number
  newSnapshotId: number
  oldSnapshotVersion: number
  newSnapshotVersion: number
  addedTables: string[]
  removedTables: string[]
  addedColumns: DiffColumn[]
  removedColumns: DiffColumn[]
  modifiedColumns: DiffColumn[]
}

/** 路径参数固定为旧快照 -> 新快照，返回新增/删除方向也按此协议解释。 */
export async function compareSnapshots(oldSnapshotId: number, newSnapshotId: number) {
  const { data } = await http.get<ApiResult<VersionDiff>>(
    `/api/admin/snapshots/${oldSnapshotId}/diff/${newSnapshotId}`
  )
  return data
}
