import { http } from '../http'
import type { ApiResult } from './user'

export interface DatasourcePermissionItem {
  id: number
  datasourceId: number
  datasourceName?: string
  subjectType: string
  subjectId: number
  subjectName: string
  canQuery: boolean
  canExport: boolean
  canViewSql: boolean
  grantedAt: string
  expiresAt?: string
}

export interface DatasourcePermissionPayload {
  datasourceId: number
  subjectType: string
  subjectId: number
  canQuery?: boolean
  canExport?: boolean
  canViewSql?: boolean
}

export interface AccessPolicyItem {
  id: number
  datasourceId: number
  subjectType: string
  subjectId: number
  subjectName: string
  tableName: string
  columnName?: string
  accessType: string
  maskStrategy?: string
  rowFilterExpression?: string
  createdAt: string
}

export interface AccessPolicyPayload {
  datasourceId: number
  subjectType: string
  subjectId: number
  tableName: string
  columnName?: string
  accessType: string
  maskStrategy?: string
  rowFilterExpression?: string
}

export interface AccessPolicyBatchPayload {
  datasourceId: number
  subjectType: string
  subjectId: number
  tableName: string
  policies: Array<{
    columnName?: string
    accessType: string
    maskStrategy?: string
    rowFilterExpression?: string
  }>
}

// 数据源访问授权 API
export async function listDatasourcePermissions(datasourceId: number, subjectType?: string) {
  const params: Record<string, any> = { datasourceId }
  if (subjectType) params.subjectType = subjectType
  const { data } = await http.get<ApiResult<DatasourcePermissionItem[]>>('/api/admin/datasource-access', { params })
  return data
}

export async function grantDatasourcePermission(payload: DatasourcePermissionPayload) {
  const { data } = await http.post<ApiResult<{ id: number }>>('/api/admin/datasource-access', payload)
  return data
}

export async function updateDatasourcePermission(id: number, payload: { canQuery?: boolean; canExport?: boolean; canViewSql?: boolean }) {
  const { data } = await http.put<ApiResult<void>>(`/api/admin/datasource-access/${id}`, payload)
  return data
}

export async function revokeDatasourcePermission(id: number) {
  const { data } = await http.delete<ApiResult<void>>(`/api/admin/datasource-access/${id}`)
  return data
}

// 行列级策略 API
export async function listAccessPolicies(datasourceId: number, subjectType?: string, subjectId?: number, tableName?: string) {
  const params: Record<string, any> = { datasourceId }
  if (subjectType) params.subjectType = subjectType
  if (subjectId) params.subjectId = subjectId
  if (tableName) params.tableName = tableName
  const { data } = await http.get<ApiResult<AccessPolicyItem[]>>('/api/admin/access-policies', { params })
  return data
}

export async function createAccessPolicy(payload: AccessPolicyPayload) {
  const { data } = await http.post<ApiResult<{ id: number }>>('/api/admin/access-policies', payload)
  return data
}

export async function batchCreateAccessPolicies(payload: AccessPolicyBatchPayload) {
  const { data } = await http.post<ApiResult<void>>('/api/admin/access-policies/batch', payload)
  return data
}

export async function updateAccessPolicy(id: number, payload: { accessType?: string; maskStrategy?: string; rowFilterExpression?: string }) {
  const { data } = await http.put<ApiResult<void>>(`/api/admin/access-policies/${id}`, payload)
  return data
}

export async function deleteAccessPolicy(id: number) {
  const { data } = await http.delete<ApiResult<void>>(`/api/admin/access-policies/${id}`)
  return data
}
