import { http } from '../http'
import type { ApiResult, PageResult } from './user'

/** Prompt 模板状态 */
export type PromptStatus = 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'

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
  activeVersion?: number
  activeContent?: string
  integrationStatus?: 'MANAGED' | 'RETIRED'
}

export interface PromptSceneVO {
  code: string
  name: string
  group: string
  consumer: string
  contractVersion: string
  variables: string[]
  variableDescriptions?: Record<string, string>
  outputContract: string
  fixedConstraints: string
  metric: string
}
export interface PromptInvocationVO {
  invocationId: string
  taskId?: string
  sceneCode: string
  versionNo: number
  source: 'MANAGED' | 'BUILTIN'
  contractVersion: string
  contentHash: string
  fallbackReason: string
  outcome: 'MODEL_RETURNED' | 'MODEL_FAILED'
  durationMs: number
  createdAt: string
}
export async function listPromptScenes() {
  return (await http.get<ApiResult<PromptSceneVO[]>>('/api/admin/prompt-templates/scenes')).data
}
export async function listPromptInvocations(sceneCode: string, page = 1) {
  return (await http.get<ApiResult<PageResult<PromptInvocationVO>>>('/api/admin/prompt-templates/invocations', { params: { sceneCode, page, pageSize: 10 } })).data
}
export async function validatePromptPolicy(code: string, content: string) {
  return (await http.post<ApiResult<{ systemPreview: string; userPreview: string; contractVersion: string }>>(`/api/admin/prompt-templates/${code}/validate`, { content })).data
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

export async function updatePromptTemplate(code: string, payload: { content: string; changeSummary?: string }) {
  const { data } = await http.put<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}`, payload)
  return data
}

/** 提交审核 */
export async function submitPromptForReview(code: string, expectedVersionNo: number) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/submit`, { expectedVersionNo })
  return data
}

/** 审核通过 */
export async function approvePrompt(code: string, expectedVersionNo: number, changeSummary?: string) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/approve`, { expectedVersionNo, changeSummary })
  return data
}

/** 审核拒绝 */
export async function rejectPrompt(code: string, expectedVersionNo: number, rejectReason: string) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/reject`, { expectedVersionNo, rejectReason })
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

/**
 * 切换是否使用已批准的后台策略；不受另一个草稿状态影响。
 *
 * `enabled` 决定 `getActiveContent` 能否取到该模板，是 Prompt 策略的生效开关。
 * 此前该字段只能在审核通过时被置为 true，前端只能展示、无法停用。
 */
export async function setPromptEnabled(code: string, enabled: boolean) {
  const { data } = await http.patch<ApiResult<PromptTemplateVO>>(
    `/api/admin/prompt-templates/${code}/enabled`,
    { enabled },
  )
  return data
}
