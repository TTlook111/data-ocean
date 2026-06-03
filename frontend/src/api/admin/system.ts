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
  providers: AiProvider[]
  activeChat: AiChatConfig
  activeEmbedding: AiEmbeddingConfig
  pendingEmbedding?: AiEmbeddingConfig | null
  vectorizeStatus?: AiVectorizeStatus
}

export interface AiConfigPayload {
  apiKey?: string
  baseUrl?: string
  model?: string
  temperature?: string
  timeout?: string
  embeddingModel?: string
  embeddingDimension?: string
  chat?: AiChatConfig
  embedding?: AiEmbeddingConfig
  pendingEmbedding?: AiEmbeddingConfig
}

export interface AiModelItem {
  name: string
  displayName?: string
  dimension?: number
  maxContext?: number
  type?: 'chat' | 'embedding' | string
  manualType?: boolean
}

export interface AiProvider {
  id: string
  name: string
  baseUrl: string
  apiKeyMasked?: string
  chatModels?: AiModelItem[]
  embeddingModels?: AiModelItem[]
  status?: string
  lastTestedAt?: string
}

export interface AiProviderPayload {
  id: string
  name?: string
  baseUrl?: string
  apiKey?: string
  chatModels?: AiModelItem[]
  embeddingModels?: AiModelItem[]
}

export interface AiChatConfig {
  providerId: string
  model: string
  temperature: string
  timeout: string
  maxRetries?: string
}

export interface AiEmbeddingConfig {
  providerId: string
  model: string
  dimension: number
  collection?: string
  indexVersion?: string
}

export interface AiVectorizeStatus {
  status: 'NORMAL' | 'REINDEX_REQUIRED' | 'REINDEXING' | 'REINDEX_FAILED' | string
  active?: AiEmbeddingConfig
  pending?: AiEmbeddingConfig | null
  totalChunks?: number
  completedChunks?: number
  failedChunks?: number
  errorMessage?: string
}

export async function getAiConfig() {
  const { data } = await http.get<ApiResult<AiConfig>>('/api/admin/system/ai-config')
  return data
}

export async function updateAiConfig(payload: AiConfigPayload) {
  const { data } = await http.put<ApiResult<AiConfig>>('/api/admin/system/ai-config', payload, { timeout: 15000 })
  return data
}

export async function listAiProviders() {
  const { data } = await http.get<ApiResult<AiProvider[]>>('/api/admin/system/ai-config/providers')
  return data
}

export async function createAiProvider(payload: AiProviderPayload) {
  const { data } = await http.post<ApiResult<AiProvider>>('/api/admin/system/ai-config/providers', payload)
  return data
}

export async function updateAiProvider(id: string, payload: AiProviderPayload) {
  const { data } = await http.put<ApiResult<AiProvider>>(`/api/admin/system/ai-config/providers/${id}`, payload)
  return data
}

export async function deleteAiProvider(id: string) {
  const { data } = await http.delete<ApiResult<void>>(`/api/admin/system/ai-config/providers/${id}`)
  return data
}

export async function testAiProvider(id: string) {
  const { data } = await http.post<ApiResult<AiProvider>>(`/api/admin/system/ai-config/providers/${id}/test`, {}, { timeout: 20000 })
  return data
}

export async function detectEmbeddingDimension(payload: {
  providerId?: string
  baseUrl?: string
  apiKey?: string
  model: string
}) {
  const { data } = await http.post<ApiResult<{ dimension?: number; model: string }>>(
    '/api/admin/system/ai-config/detect-dimension',
    payload,
    { timeout: 30000 },
  )
  return data
}
