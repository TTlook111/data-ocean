import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface KnowledgeDocItem {
  id: number
  datasourceId: number
  title: string
  content?: string
  currentVersion: number
  status: string
  reviewStatus?: string
  /** 该文档覆盖的表名列表（后端以 JSON 数组字符串存储） */
  tableNames?: string
  /** 乐观锁版本号，编辑保存时必须回传 */
  version: number
  createdAt: string
  updatedAt: string
}

export interface KnowledgeVersionItem {
  id: number
  docId: number
  versionNo: number
  content: string
  generationSource: string
  reviewStatus: string
  metadataSnapshotId?: number
  changeSummary?: string
  createdBy?: number
  createdAt: string
}

export interface KnowledgeDocQuery {
  datasourceId?: number
  status?: string
  page?: number
  pageSize?: number
}

export async function listKnowledgeDocs(params: KnowledgeDocQuery) {
  const { data } = await http.get<ApiResult<PageResult<KnowledgeDocItem>>>('/api/admin/knowledge-docs', { params })
  return data
}

export async function getKnowledgeDoc(id: number) {
  const { data } = await http.get<ApiResult<KnowledgeDocItem>>(`/api/admin/knowledge-docs/${id}`)
  return data
}

export async function createKnowledgeDoc(payload: { datasourceId: number; title: string; content?: string }) {
  const { data } = await http.post<ApiResult<{ id: number }>>('/api/admin/knowledge-docs', payload)
  return data
}

export async function updateKnowledgeDoc(id: number, payload: { title: string; content?: string; version: number; changeSummary?: string }) {
  const { data } = await http.put<ApiResult<null>>(`/api/admin/knowledge-docs/${id}`, payload)
  return data
}

export async function submitReview(id: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/knowledge-docs/${id}/submit-review`)
  return data
}

export async function approveDoc(id: number, comment?: string) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/knowledge-docs/${id}/approve`, { comment })
  return data
}

export async function rejectDoc(id: number, comment?: string) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/knowledge-docs/${id}/reject`, { comment })
  return data
}

export async function publishDoc(id: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/knowledge-docs/${id}/publish`)
  return data
}

export async function generateDraft(id: number, snapshotId: number) {
  const { data } = await http.post<ApiResult<{ content: string }>>(`/api/admin/knowledge-docs/${id}/generate-draft`, { snapshotId }, { timeout: 120000 })
  return data
}

export async function listVersions(docId: number) {
  const { data } = await http.get<ApiResult<KnowledgeVersionItem[]>>(`/api/admin/knowledge-docs/${docId}/versions`)
  return data
}

export async function getVersion(docId: number, versionNo: number) {
  const { data } = await http.get<ApiResult<KnowledgeVersionItem>>(`/api/admin/knowledge-docs/${docId}/versions/${versionNo}`)
  return data
}

export async function rollbackVersion(docId: number, targetVersionNo: number) {
  const { data } = await http.post<ApiResult<{ newVersionNo: number }>>(`/api/admin/knowledge-docs/${docId}/rollback`, { targetVersionNo })
  return data
}

/** 版本差异的单行结果，type 为 EQUAL / ADD / DELETE */
export interface KnowledgeDiffLine {
  type: 'EQUAL' | 'ADD' | 'DELETE'
  content: string
}

/**
 * 版本差异比较（后端基于 LCS 的行级差异）。
 *
 * 后端为 `GET /{id}/versions/diff?v1=&v2=`，注意与 `/{id}/versions/{versionNo}` 共用前缀，
 * 因此必须走带 `v1`/`v2` 查询参数的独立封装，不能拼成路径。
 */
export async function diffVersions(docId: number, v1: number, v2: number) {
  const { data } = await http.get<ApiResult<KnowledgeDiffLine[]>>(`/api/admin/knowledge-docs/${docId}/versions/diff`, {
    params: { v1, v2 },
  })
  return data
}

/**
 * 切分预览：模拟发布时的切片逻辑，返回当前内容会被切成哪些 chunk。
 *
 * 后端返回 `List<Map<String, String>>`，字段随 Python 切分实现变化，
 * 因此这里保留为字符串字典，不臆造固定结构。
 */
export type KnowledgeChunkPreview = Record<string, string>

export async function previewChunks(docId: number) {
  const { data } = await http.post<ApiResult<KnowledgeChunkPreview[]>>(
    `/api/admin/knowledge-docs/${docId}/preview-chunks`,
    undefined,
    { timeout: 60000 },
  )
  return data
}

/** AI 一键生成：自动分析业务域，批量创建文档 */
export async function generateFromSnapshot(datasourceId: number, snapshotId: number) {
  const { data } = await http.post<ApiResult<Array<{ id: number; title: string; tableNames: string[] }>>>(
    '/api/admin/knowledge-docs/generate-from-snapshot',
    { snapshotId },
    { params: { datasourceId }, timeout: 180000 },
  )
  return data
}
