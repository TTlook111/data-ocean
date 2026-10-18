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

/** 已发布快照对应的数据对象目录。syncStatus 为空表示历史版本未记录同步状态。 */
export interface PublishedCatalog {
  datasourceId: number
  publishedSnapshotId?: number
  snapshotVersion?: number
  syncStatus?: string
  syncError?: string
  syncedAt?: string
  tableCount: number
  columnCount: number
  entities: MetadataEntityItem[]
}

export type OverviewSectionStatus = 'OK' | 'FORBIDDEN' | 'ERROR' | 'UNAVAILABLE'

export interface OverviewSection<T> {
  status: OverviewSectionStatus
  message?: string
  items: T[]
}

export interface OverviewDatasource {
  id: number
  name: string
  databaseName?: string
}

export interface OverviewSnapshot {
  id: number
  version: number
  status: string
  entitySyncStatus?: string
  entitySyncError?: string
  entitySyncedAt?: string
}

export interface OverviewTable {
  entityId?: number
  tableName: string
  displayName?: string
  comment?: string
  tableType?: string
  governanceStatus?: string
}

export interface OverviewColumn {
  entityId?: number
  columnMetaId?: number
  name: string
  displayName?: string
  dataType?: string
  nullable?: boolean
  primaryKey?: boolean
  comment?: string
  governanceStatus?: string
  tableName?: string
  ordinalPosition?: number
  usageLimits?: string[]
}

export interface OverviewCounts {
  columns: number
  structureRelations: number
  terms: number
  tags: number
  fieldTags: number
  lineage: number
  downstream: number
}

export interface OverviewRelationItem {
  id: number
  relationType: string
  direction: string
  sourceType: string
  sourceId: number
  sourceName?: string
  targetType: string
  targetId: number
  targetName?: string
  bindingStatus?: string
}

export interface OverviewTermItem {
  termId: number
  glossaryId?: number
  name: string
  displayName?: string
  description?: string
  status: string
  currentlyValid: boolean
  validityMessage?: string
  verificationCode: string
  linkMode: 'DIRECT_COLUMN' | 'DIRECT_TABLE' | 'VIA_COLUMNS' | 'BROKEN'
  viaColumns: string[]
  revision?: number
}

export interface OverviewTagItem {
  id?: number
  name: string
  displayName?: string
  description?: string
  kind: 'CONFIRMED' | 'CANDIDATE' | 'UNBOUND' | 'BROKEN'
  source: string
  confirmed: boolean
  bindingStatus?: string
}

export interface OverviewFieldTagItem {
  id: number
  columnMetaId: number
  tagCode: string
  tagName: string
  source: string
}

export interface OverviewLineageItem {
  id: number
  relationType: string
  direction: 'UPSTREAM' | 'DOWNSTREAM'
  sourceType: string
  sourceId: number
  sourceName?: string
  targetType: string
  targetId: number
  targetName?: string
  lineageType?: string
  bindingStatus?: string
  description?: string
}

/** 表 / 字段对象详情聚合结果。 */
export interface CatalogEntityOverview {
  entity: MetadataEntityItem
  entityType: string
  datasource?: OverviewDatasource
  /** 本次详情实际使用的来源快照。 */
  sourceSnapshot?: OverviewSnapshot
  /** 当前数据源的发布目标，可能因同步未完成而不同于来源快照。 */
  publishedSnapshot?: OverviewSnapshot
  sourceVerified: boolean
  sourceMatchesPublished: boolean
  sourceMessage?: string
  table?: OverviewTable
  column?: OverviewColumn
  columns: OverviewSection<OverviewColumn>
  counts: OverviewCounts
  structureRelations: OverviewSection<OverviewRelationItem>
  terms: OverviewSection<OverviewTermItem>
  tags: OverviewSection<OverviewTagItem>
  fieldTags: OverviewSection<OverviewFieldTagItem>
  lineage: OverviewSection<OverviewLineageItem>
  downstream: OverviewSection<OverviewLineageItem>
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

export async function getEntityTags(entityId: number) {
  const { data } = await http.get<ApiResult<MetadataEntityItem[]>>(`/api/admin/catalog/entities/${entityId}/tags`)
  return data
}

export async function confirmEntityTag(entityId: number, tagFqn: string) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/catalog/entities/${entityId}/confirm-tag`, { tagFqn })
  return data
}

export async function unconfirmEntityTag(entityId: number, tagFqn: string) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/catalog/entities/${entityId}/unconfirm-tag/${encodeURIComponent(tagFqn)}`)
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

/** 读取当前已发布快照对应的表 / 字段目录，并返回目录同步状态。 */
export async function getPublishedCatalog(datasourceId: number) {
  const { data } = await http.get<ApiResult<PublishedCatalog>>('/api/admin/catalog/published-entities', {
    params: { datasourceId },
  })
  return data
}

/** 读取表 / 字段对象详情；关联区块各自返回 OK/FORBIDDEN/ERROR。 */
export async function getEntityOverview(entityId: number) {
  const { data } = await http.get<ApiResult<CatalogEntityOverview>>(`/api/admin/catalog/entities/${entityId}/overview`)
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
