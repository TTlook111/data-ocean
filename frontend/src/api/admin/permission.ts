import { http } from '../http'
import type { ApiResult } from './user'

export interface AccessApprovalRequestItem {
  id: number
  requesterId: number
  datasourceId: number
  tableName: string
  columnName?: string
  requestReason: string
  requestedDuration: number
  status: string
  approverId?: number
  approvedAt?: string
  expiresAt?: string
  rejectReason?: string
  createdAt: string
}

export async function listAccessApprovalRequests(params: { datasourceId?: number; status?: string; page?: number; size?: number }) {
  const { data } = await http.get<ApiResult<{ records: AccessApprovalRequestItem[]; total: number; current: number; size: number }>>('/api/admin/access-approvals', { params })
  return data
}

export async function reviewAccessApprovalRequest(id: number, payload: { approved: boolean; reason?: string }) {
  const { data } = await http.post<ApiResult<null>>('/api/admin/access-approvals/' + id + '/review', payload)
  return data
}

export interface AccessApprovalSubmitPayload {
  datasourceId: number
  tableName: string
  columnName?: string
  requestReason: string
  requestedDuration: number
}

/**
 * 提交数据访问申请。
 *
 * 后端接口已存在（`AccessApprovalController.submitRequest`），此前前端零封装。
 * **本轮只补封装、不开放入口**：`开发指导` §7.14 要求普通用户「我的申请」
 * 待产品确认后再正式开放。后端自 2026-09-12 起已按 `security:manage` 收窄列表范围，
 * 数据隔离已成立，但入口的开放与否仍需产品决定。
 */
export async function submitAccessApprovalRequest(payload: AccessApprovalSubmitPayload) {
  const { data } = await http.post<ApiResult<{ id: number }>>('/api/admin/access-approvals', payload)
  return data
}

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
  accessEffect: 'ALLOW' | 'DENY'
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
  accessEffect?: 'ALLOW' | 'DENY'
  /** 授权有效期；留空表示长期有效。后端 DTO 已有该字段，此前前端类型未定义导致无法设置 */
  expiresAt?: string
}

export interface DatasourcePermissionDecision {
  datasourceId: number
  userId: number
  canQuery: boolean
  canExport: boolean
  canViewSql: boolean
  decisionSource: string
  departmentId?: number
  effectiveDepartmentId?: number
  roleIds: number[]
  userGrantId?: number
  accessEffect: 'ALLOW' | 'DENY' | 'NONE'
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
  priority?: number
  validFrom?: string
  validUntil?: string
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
  priority?: number
  validFrom?: string
  validUntil?: string
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

export async function updateDatasourcePermission(id: number, payload: { canQuery?: boolean; canExport?: boolean; canViewSql?: boolean; accessEffect?: 'ALLOW' | 'DENY' }) {
  const { data } = await http.put<ApiResult<void>>(`/api/admin/datasource-access/${id}`, payload)
  return data
}

export async function revokeDatasourcePermission(id: number) {
  const { data } = await http.delete<ApiResult<void>>(`/api/admin/datasource-access/${id}`)
  return data
}

export async function getDatasourcePermissionDecision(datasourceId: number, userId: number) {
  const { data } = await http.get<ApiResult<DatasourcePermissionDecision>>('/api/admin/datasource-access/decision', {
    params: { datasourceId, userId },
  })
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
