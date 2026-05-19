import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface DatasourceItem {
  id: number
  name: string
  description?: string
  dbType: string
  host: string
  port: number
  databaseName: string
  charset: string
  status: number
  healthStatus: string
  username?: string
  creatorName?: string
  lastCheckSuccess?: boolean
  lastCheckTime?: string
  createdAt?: string
}

export interface DatasourceQuery {
  name?: string
  status?: number
  healthStatus?: string
  page?: number
  pageSize?: number
}

export interface DatasourcePayload {
  name: string
  description?: string
  host: string
  port: number
  databaseName: string
  charset: string
  username: string
  password?: string
}

export interface DatasourceTestPayload {
  host: string
  port: number
  databaseName: string
  charset: string
  username: string
  password: string
}

export interface DatasourceTestResult {
  success: boolean
  responseTimeMs: number
  serverVersion?: string
  message: string
}

export interface DatasourceAccessItem {
  id: number
  datasourceId: number
  userId: number
  username?: string
  realName?: string
  grantedBy?: number
  grantedAt?: string
  expiresAt?: string
}

export async function listDatasources(params: DatasourceQuery) {
  const { data } = await http.get<ApiResult<PageResult<DatasourceItem>>>('/api/admin/datasources', { params })
  return data
}

export async function createDatasource(payload: DatasourcePayload) {
  const { data } = await http.post<ApiResult<DatasourceItem>>('/api/admin/datasources', payload)
  return data
}

export async function updateDatasource(id: number, payload: DatasourcePayload) {
  const { data } = await http.put<ApiResult<DatasourceItem>>(`/api/admin/datasources/${id}`, payload)
  return data
}

export async function deleteDatasource(id: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/datasources/${id}`)
  return data
}

export async function updateDatasourceStatus(id: number, status: number) {
  const { data } = await http.patch<ApiResult<DatasourceItem>>(`/api/admin/datasources/${id}/status`, { status })
  return data
}

export async function testDatasourceConnection(payload: DatasourceTestPayload) {
  const { data } = await http.post<ApiResult<DatasourceTestResult>>('/api/admin/datasources/test-connection', payload)
  return data
}

export async function testSavedDatasourceConnection(id: number) {
  const { data } = await http.post<ApiResult<DatasourceTestResult>>(`/api/admin/datasources/${id}/test-connection`)
  return data
}

export async function listDatasourceAccess(id: number) {
  const { data } = await http.get<ApiResult<DatasourceAccessItem[]>>(`/api/admin/datasources/${id}/access`)
  return data
}

export async function grantDatasourceAccess(id: number, userIds: number[]) {
  const { data } = await http.post<ApiResult<{ granted: number }>>(`/api/admin/datasources/${id}/access`, { userIds })
  return data
}

export async function revokeDatasourceAccess(id: number, userId: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/datasources/${id}/access/${userId}`)
  return data
}
