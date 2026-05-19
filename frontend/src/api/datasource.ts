import { http } from './http'
import type { ApiResult } from './admin/user'

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
