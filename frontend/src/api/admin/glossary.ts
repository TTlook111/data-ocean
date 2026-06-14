import { http } from '../http'
import type { ApiResult } from './user'

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
