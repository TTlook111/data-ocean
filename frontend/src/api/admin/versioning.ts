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
  targetStatus: string
  reason?: string
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

export async function listDatasourceAuditLogs(datasourceId: number, params: { action?: string; page?: number; size?: number }) {
  const { data } = await http.get<ApiResult<PageResult<AuditLogItem>>>(
    `/api/admin/datasources/${datasourceId}/audit-logs`, { params }
  )
  return data
}

export async function changeSnapshotStatus(snapshotId: number, payload: StatusChangePayload) {
  const { data } = await http.patch<ApiResult<null>>(
    `/api/admin/snapshots/${snapshotId}/status`, payload
  )
  return data
}

export async function publishSnapshot(snapshotId: number) {
  const { data } = await http.post<ApiResult<null>>(
    `/api/admin/snapshots/${snapshotId}/publish`
  )
  return data
}

export async function revokeSnapshot(snapshotId: number, reason: string) {
  const { data } = await http.post<ApiResult<null>>(
    `/api/admin/snapshots/${snapshotId}/revoke`, { targetStatus: 'APPROVED', reason }
  )
  return data
}

export async function compareSnapshots(snapshotId: number, compareSnapshotId: number) {
  const { data } = await http.get<ApiResult<any>>(
    `/api/admin/snapshots/${snapshotId}/diff/${compareSnapshotId}`
  )
  return data
}
