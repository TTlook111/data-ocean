import { http } from '../http'
import type { ApiResult } from './user'

export interface SyncScheduleInfo {
  cron: string
  enabled: boolean
  running: boolean
}

export interface SyncSchedulePayload {
  cron: string
  enabled?: boolean
}

export interface PoolStatusItem {
  datasourceId: number
  poolSize: number
  lastUsedAt: number
  createdAt: number
}

export interface PoolDashboardInfo {
  activePools: number
  pools: PoolStatusItem[]
}

export async function getSyncSchedule() {
  const { data } = await http.get<ApiResult<SyncScheduleInfo>>('/api/admin/system/sync-schedule')
  return data
}

export async function updateSyncSchedule(payload: SyncSchedulePayload) {
  const { data } = await http.put<ApiResult<SyncScheduleInfo>>('/api/admin/system/sync-schedule', payload)
  return data
}

export async function getPoolDashboard() {
  const { data } = await http.get<ApiResult<PoolDashboardInfo>>('/api/admin/system/sql-pools')
  return data
}

export async function resetDatasourcePool(datasourceId: number) {
  const { data } = await http.post<ApiResult<void>>(`/api/admin/system/sql-pools/${datasourceId}/reset`)
  return data
}

// ---- AI 配置管理 ----

export interface AiConfig {
  apiKeyMasked: string
  baseUrl: string
  model: string
  temperature: string
  timeout: string
  embeddingModel: string
  embeddingDimension: string
}

export interface AiConfigPayload {
  apiKey?: string
  baseUrl?: string
  model?: string
  temperature?: string
  timeout?: string
  embeddingModel?: string
  embeddingDimension?: string
}

export async function getAiConfig() {
  const { data } = await http.get<ApiResult<AiConfig>>('/api/admin/system/ai-config')
  return data
}

export async function updateAiConfig(payload: AiConfigPayload) {
  const { data } = await http.put<ApiResult<AiConfig>>('/api/admin/system/ai-config', payload, { timeout: 15000 })
  return data
}
