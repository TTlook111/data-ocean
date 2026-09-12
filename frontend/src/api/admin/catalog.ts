import { http } from '../http'
import type { ApiResult } from './user'

/** 元数据实体 */
export interface MetadataEntityItem {
  id: number
  entityType: string
  entityUuid: string
  fqn: string
  name: string
  displayName?: string
  description?: string
  entityMetadata?: string
  ownerId?: number
  version: number
  createdAt?: string
  updatedAt?: string
}

/** 元数据关系 */
export interface MetadataRelationshipItem {
  id: number
  sourceId: number
  sourceType: string
  targetId: number
  targetType: string
  relationType: string
  relationMetadata?: string
  createdAt?: string
}

/** 实体详情（含关系） */
export interface EntityDetail {
  entity: MetadataEntityItem
  outgoingRelations: MetadataRelationshipItem[]
  incomingRelations: MetadataRelationshipItem[]
}

export interface MaskCandidate {
  entityId: number
  fqn: string
  name: string
  displayName?: string
  pendingMask?: Record<string, unknown>
}

/**
 * 全文搜索实体。
 *
 * `datasourceId` 自 2026-09-12 起真实生效（此前后端声明该参数却未下传给 Service，
 * 数据源内搜索实际是全局搜索，可能返回其他数据源的资产）。过滤在 SQL 内完成，
 * 因此分页条数与实际匹配数一致，调用方可以放心分页。
 *
 * 注意：现有页面（资产目录、术语关联字段）仍沿用「按数据源拉全量 + 页面内过滤」的
 * 旧规避方式，尚未切换到本接口。切换是阶段 8 收敛项，不属缺陷修复范围。
 */
export async function searchCatalog(params: {
  q: string
  type?: string
  datasourceId?: number
  page?: number
  size?: number
}) {
  const { data } = await http.get<ApiResult<MetadataEntityItem[]>>('/api/admin/catalog/search', { params })
  return data
}

/** 获取实体详情 */
export async function getEntityDetail(entityId: number) {
  const { data } = await http.get<ApiResult<EntityDetail>>(`/api/admin/catalog/entities/${entityId}`)
  return data
}

/** 获取实体血缘关系 */
/**
 * 实体的血缘关系。
 *
 * 已收敛到 `api/admin/lineageApi.ts` 的 `listEntityLineage`（§10.3 同一接口只保留一个封装）。
 * 此处保留一个转发，避免调用方再各自拼 URL。
 */
export { listEntityLineage as getEntityLineage } from './lineageApi'

/** 获取实体下游影响 */
export async function getEntityDownstream(entityId: number, maxDepth = 10) {
  const { data } = await http.get<ApiResult<MetadataRelationshipItem[]>>(`/api/admin/catalog/entities/${entityId}/downstream`, {
    params: { maxDepth },
  })
  return data
}

/** 按数据源获取所有实体 */
export async function getEntitiesByDatasource(datasourceId: number) {
  const { data } = await http.get<ApiResult<MetadataEntityItem[]>>('/api/admin/catalog/entities', {
    params: { datasourceId },
  })
  return data
}

export async function listMaskCandidates(datasourceId?: number) {
  const { data } = await http.get<ApiResult<MaskCandidate[]>>('/api/admin/catalog/mask-candidates', {
    params: { datasourceId },
  })
  return data
}

export async function confirmMaskCandidate(entityId: number, maskStrategy: string) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/catalog/mask-candidates/${entityId}/confirm`, { maskStrategy })
  return data
}

export async function rejectMaskCandidate(entityId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/catalog/mask-candidates/${entityId}/reject`)
  return data
}
