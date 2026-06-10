import { http } from '../http'
import type { ApiResult, PageResult } from './user'

/** Prompt 模板状态 */
export type PromptStatus = 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'

/** Prompt 模板状态描述 */
export const PROMPT_STATUS_MAP: Record<PromptStatus, { label: string; type: 'info' | 'warning' | 'success' | 'danger' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PENDING_REVIEW: { label: '待审核', type: 'warning' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已拒绝', type: 'danger' },
}

export interface PromptTemplateVO {
  id: number
  templateCode: string
  templateName: string
  scenario: string
  content: string
  currentVersion: number
  status: PromptStatus
  enabled: boolean
  updatedAt: string
}

export interface PromptVersionVO {
  id: number
  versionNo: number
  content: string
  changeSummary?: string
  isActive: boolean
  status: PromptStatus
  createdBy?: number
  createdAt: string
}

export interface PromptEffectivenessVO {
  templateCode: string
  versionNo: number
  totalQueries: number
  successCount: number
  successRate: number
  avgExecutionTimeMs: number
  feedbackCount: number
  positiveFeedbackCount: number
  positiveFeedbackRate: number
}

export async function listPromptTemplates(params: { page?: number; pageSize?: number }) {
  const { data } = await http.get<ApiResult<PageResult<PromptTemplateVO>>>('/api/admin/prompt-templates', { params })
  return data
}

export async function getPromptEffectiveness(days = 30) {
  const { data } = await http.get<ApiResult<PromptEffectivenessVO[]>>('/api/admin/prompt-templates/effectiveness', {
    params: { days },
  })
  return data
}

export async function getPromptTemplate(code: string) {
  const { data } = await http.get<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}`)
  return data
}

export async function updatePromptTemplate(code: string, payload: { content: string; changeSummary?: string }) {
  const { data } = await http.put<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}`, payload)
  return data
}

/** 提交审核 */
export async function submitPromptForReview(code: string) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/submit`)
  return data
}

/** 审核通过 */
export async function approvePrompt(code: string, changeSummary?: string) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/approve`, { changeSummary })
  return data
}

/** 审核拒绝 */
export async function rejectPrompt(code: string, rejectReason: string) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/reject`, { rejectReason })
  return data
}

export async function getPromptVersions(code: string) {
  const { data } = await http.get<ApiResult<PromptVersionVO[]>>(`/api/admin/prompt-templates/${code}/versions`)
  return data
}

export async function rollbackPromptVersion(code: string, targetVersionNo: number) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/rollback`, { targetVersionNo })
  return data
}
