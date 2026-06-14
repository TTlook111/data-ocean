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

/** 全文搜索实体 */
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
export async function getEntityLineage(entityId: number) {
  const { data } = await http.get<ApiResult<MetadataRelationshipItem[]>>(`/api/admin/catalog/entities/${entityId}/lineage`)
  return data
}

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
