/**
 * 数据血缘管理 API
 *
 * 封装 Phase 0 的 ETL/MANUAL 血缘创建、删除、批量导入和增强查询 API。
 * 与文档 data-lineage-research.md §4.1.1 接口定义完全一致。
 */
import { http } from '../http'
import type { ApiResult } from './user'
import type { MetadataEntityItem, MetadataRelationshipItem } from './catalog'

// ========== 类型定义 ==========

/** 列映射条目 */
export interface ColumnMappingItem {
  fromColumns: number[]
  toColumn: number
  expression?: string
  transformationType?: 'IDENTITY' | 'TRANSFORMATION' | 'AGGREGATION'
}

/** 血缘创建请求体 */
export interface LineageCreateRequest {
  sourceEntityId: number
  targetEntityId: number
  lineageType: 'ETL' | 'MANUAL'
  description?: string
  columnMappings?: ColumnMappingItem[]
}

/** 血缘边响应 */
export interface LineageEdgeVO {
  relationshipId: number
  sourceEntityId: number
  targetEntityId: number
  relationType: string
  lineageType: string
  description?: string
  derivedCount: number
  relationMetadata?: Record<string, any>
  createdAt: string
  createdBy?: string
}

/** 增强血缘图谱响应 */
export interface LineageGraphVO {
  nodes: MetadataEntityItem[]
  edges: MetadataRelationshipItem[]
  columnMappingsByEdge: Record<number, Array<Record<string, any>>>
  depth: number
}

/** 列血缘节点（递归嵌套） */
export interface ColumnLineageNode {
  entity: MetadataEntityItem
  relationship: MetadataRelationshipItem
  children?: ColumnLineageNode[]
}

/** 列血缘响应 */
export interface ColumnLineageVO {
  columnId: number
  columnName: string
  fqn: string
  upstream?: ColumnLineageNode[]
  downstream?: ColumnLineageNode[]
}

// ========== API 方法 ==========

/**
 * 创建单条 LINEAGE 关系
 * POST /api/admin/catalog/lineage
 */
export async function createLineage(request: LineageCreateRequest) {
  const { data } = await http.post<ApiResult<LineageEdgeVO>>('/api/admin/catalog/lineage', request)
  return data
}

/**
 * 删除 LINEAGE 关系
 * DELETE /api/admin/catalog/lineage/{relationshipId}?cascadeDerived=true
 */
export async function deleteLineage(relationshipId: number, cascadeDerived = false) {
  const { data } = await http.delete<ApiResult<{ relationshipId: number; derivedCount: number; deleted?: boolean; message?: string }>>(
    `/api/admin/catalog/lineage/${relationshipId}`,
    { params: { cascadeDerived } },
  )
  return data
}

/**
 * 批量创建血缘（CSV/JSON 文件上传）
 * POST /api/admin/catalog/lineage/batch
 */
export async function batchCreateLineage(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  const { data } = await http.post<ApiResult<LineageEdgeVO[]>>('/api/admin/catalog/lineage/batch', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

/**
 * 获取增强血缘图谱（含节点和列映射摘要）
 * GET /api/admin/catalog/entities/{entityId}/lineage?depth=3&lineageType=ETL,MANUAL,QUERY
 */
/**
 * 该 URL 的**唯一构造点**（§10.3「同一接口只保留一个前端封装」）。
 *
 * `api/admin/catalog.ts` 曾另行封装过一次同一 URL，已收敛到此处。
 *
 * 后端同一端点有两种返回形态：带 `lineageType` 时返回增强图谱（含 nodes/edges），
 * 不带时走向后兼容路径返回关系数组，且此时 `depth` 不生效。
 */
async function requestEntityLineage(
  entityId: number,
  depth: number,
  lineageTypes?: string[],
): Promise<ApiResult<LineageGraphVO | MetadataRelationshipItem[]>> {
  const params: Record<string, any> = { depth }
  if (lineageTypes && lineageTypes.length > 0) {
    params.lineageType = lineageTypes.join(',')
  }
  const { data } = await http.get<ApiResult<LineageGraphVO | MetadataRelationshipItem[]>>(
    `/api/admin/catalog/entities/${entityId}/lineage`,
    { params },
  )
  return data
}

/** 按深度与血缘类型取增强图谱；不传 `lineageTypes` 时后端返回关系数组 */
export async function getEnrichedLineage(entityId: number, depth = 3, lineageTypes?: string[]) {
  return requestEntityLineage(entityId, depth, lineageTypes)
}

/** 只取关系数组（后端向后兼容路径的形态）。返回类型固定，便于调用方直接消费 */
export async function listEntityLineage(entityId: number): Promise<ApiResult<MetadataRelationshipItem[]>> {
  const result = await requestEntityLineage(entityId, 1)
  return { ...result, data: Array.isArray(result.data) ? result.data : [] }
}

/**
 * 获取列级血缘（DERIVED_FROM 上下游链）
 * GET /api/admin/catalog/entities/{columnId}/column-lineage?depth=3&direction=both
 */
export async function getColumnLineage(columnId: number, depth = 3, direction: 'upstream' | 'downstream' | 'both' = 'both') {
  const { data } = await http.get<ApiResult<ColumnLineageVO>>(
    `/api/admin/catalog/entities/${columnId}/column-lineage`,
    { params: { depth, direction } },
  )
  return data
}
