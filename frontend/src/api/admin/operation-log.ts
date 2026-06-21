import { http } from '../http'
import type { ApiResult, PageResult } from '../types'

export interface OperationLogItem {
  id: number
  operatorId?: number
  operatorName?: string
  operationType?: string
  targetResource?: string
  targetId?: string
  requestMethod?: string
  requestPath?: string
  requestParams?: string
  executionMs?: number
  isSuccess?: boolean
  errorMessage?: string
  ipAddress?: string
  createdAt?: string
}

export interface OperationLogQuery {
  page?: number
  pageSize?: number
  targetResource?: string
}

export async function listOperationLogs(params: OperationLogQuery) {
  const { data } = await http.get<ApiResult<PageResult<OperationLogItem>>>('/api/admin/operation-logs', { params })
  return data
}
