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
  const { data } = await http.post<ApiResult<{ content: string }>>(`/api/admin/knowledge-docs/${id}/generate-draft`, { snapshotId })
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
