import { http } from './http'
import type { ApiResult } from './admin/user'
import type { DatasourceReadiness } from './admin/datasource'

export type { DatasourceReadiness, DatasourceReadinessReason } from './admin/datasource'

export interface UserDatasourceItem {
  id: number
  name: string
  databaseName: string
  description?: string
}

export async function listMyDatasources() {
  const { data } = await http.get<ApiResult<UserDatasourceItem[]>>('/api/datasources')
  return data
}

export async function getMyDatasourceReadiness(id: number) {
  const { data } = await http.get<ApiResult<DatasourceReadiness>>(`/api/datasources/${id}/readiness`)
  return data
}
