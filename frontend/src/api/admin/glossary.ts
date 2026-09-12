import { http } from '../http'
import type { ApiResult } from './user'
import type { MetadataEntityItem } from './catalog'

/** 术语表 */
export interface GlossaryItem {
  id: number
  name: string
  displayName?: string
  description?: string
  ownerId?: number
  status: string
  createdAt?: string
  updatedAt?: string
}

/** 术语条目 */
export interface GlossaryTermItem {
  id: number
  glossaryId: number
  parentId?: number
  name: string
  displayName?: string
  description?: string
  synonyms?: string
  relatedTerms?: string
  fqn: string
  status: string
  reviewerId?: number
  reviewedAt?: string
  createdAt?: string
  updatedAt?: string
}

/** 术语表 CRUD */
export async function listGlossaries() {
  const { data } = await http.get<ApiResult<GlossaryItem[]>>('/api/admin/glossary')
  return data
}

export async function createGlossary(payload: Partial<GlossaryItem>) {
  const { data } = await http.post<ApiResult<{ id: number }>>('/api/admin/glossary', payload)
  return data
}

export async function updateGlossary(id: number, payload: Partial<GlossaryItem>) {
  const { data } = await http.put<ApiResult<null>>(`/api/admin/glossary/${id}`, payload)
  return data
}

export async function deleteGlossary(id: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/glossary/${id}`)
  return data
}

/** 术语条目 CRUD */
export async function listTerms(glossaryId: number, status?: string) {
  const { data } = await http.get<ApiResult<GlossaryTermItem[]>>(`/api/admin/glossary/${glossaryId}/terms`, {
    params: { status },
  })
  return data
}

export async function createTerm(glossaryId: number, payload: Partial<GlossaryTermItem>) {
  const { data } = await http.post<ApiResult<{ id: number }>>(`/api/admin/glossary/${glossaryId}/terms`, payload)
  return data
}

export async function updateTerm(termId: number, payload: Partial<GlossaryTermItem>) {
  const { data } = await http.put<ApiResult<null>>(`/api/admin/glossary/terms/${termId}`, payload)
  return data
}

export async function deleteTerm(termId: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/glossary/terms/${termId}`)
  return data
}

/** 审核流程 */
export async function submitTermForReview(termId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/glossary/terms/${termId}/submit`)
  return data
}

export async function reviewTerm(termId: number, approved: boolean, reason?: string) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/glossary/terms/${termId}/review`, {
    approved,
    reason,
  })
  return data
}

/** 术语关联的物理列（后端返回元数据实体列表） */
export type LinkedColumnItem = MetadataEntityItem

/** 查询术语当前关联的物理列 */
export async function getLinkedColumns(termId: number) {
  const { data } = await http.get<ApiResult<LinkedColumnItem[]>>(`/api/admin/glossary/terms/${termId}/linked-columns`)
  return data
}

/** 关联术语与物理列（后端创建 GLOSSARY_OF 关系） */
export async function linkTermToColumn(termId: number, entityId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/glossary/terms/${termId}/link-column`, { entityId })
  return data
}

/** 取消术语与物理列的关联 */
export async function unlinkTermFromColumn(termId: number, entityId: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/admin/glossary/terms/${termId}/unlink-column/${entityId}`)
  return data
}

/**
 * 把已通过的术语退回草稿（APPROVED → DRAFT）。
 *
 * 状态机此前从 `APPROVED` 没有出边，已通过的术语没有合法修改路径，
 * 而 `updateTerm` 又完全不校验状态，形成「合规流程被限制、绕过路径不受限」的倒挂。
 * 2026-09-12 后端补上本接口并给 `updateTerm` 加了状态校验，前端据此改为：
 * 已通过术语不可直接编辑，需先退回草稿，修改后重新提交审核。
 * 退回会清空审核人/审核时间，因为原审核结论不再代表修改后的内容。
 */
export async function revertTermToDraft(termId: number) {
  const { data } = await http.post<ApiResult<null>>(`/api/admin/glossary/terms/${termId}/revert`)
  return data
}
