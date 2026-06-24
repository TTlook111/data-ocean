import { http } from './http'
import type { ApiResult } from './admin/user'

export interface UserDatasourceItem {
  id: number
  name: string
  databaseName: string
  description?: string
}

export interface DatasourceReadinessReason {
  code: string
  message: string
  ownerRole: string
  actionText: string
  actionPath?: string
}

export interface DatasourceReadiness {
  datasourceId: number
  datasourceName: string
  askable: boolean
  stage: string
  stageLabel: string
  progress: number
  publishedSnapshotId?: number
  snapshotVersion?: number
  publishedKnowledgeDocId?: number
  knowledgeVersion?: number
  connectionReady: boolean
  metadataReady: boolean
  governanceReady: boolean
  knowledgeReady: boolean
  permissionReady: boolean
  blockReasons: DatasourceReadinessReason[]
}

export async function listMyDatasources() {
  const { data } = await http.get<ApiResult<UserDatasourceItem[]>>('/api/datasources')
  return data
}

export async function getMyDatasourceReadiness(id: number) {
  const { data } = await http.get<ApiResult<DatasourceReadiness>>(`/api/datasources/${id}/readiness`)
  return data
}
