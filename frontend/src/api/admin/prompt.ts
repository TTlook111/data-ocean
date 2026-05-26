import { http } from '../http'
import type { ApiResult, PageResult } from './user'

export interface PromptTemplateVO {
  id: number
  templateCode: string
  templateName: string
  scenario: string
  content: string
  currentVersion: number
  enabled: boolean
  updatedAt: string
}

export interface PromptVersionVO {
  id: number
  versionNo: number
  content: string
  changeSummary?: string
  isActive: boolean
  createdBy?: number
  createdAt: string
}

export async function listPromptTemplates(params: { page?: number; pageSize?: number }) {
  const { data } = await http.get<ApiResult<PageResult<PromptTemplateVO>>>('/api/admin/prompt-templates', { params })
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

export async function getPromptVersions(code: string) {
  const { data } = await http.get<ApiResult<PromptVersionVO[]>>(`/api/admin/prompt-templates/${code}/versions`)
  return data
}

export async function rollbackPromptVersion(code: string, targetVersionNo: number) {
  const { data } = await http.post<ApiResult<PromptTemplateVO>>(`/api/admin/prompt-templates/${code}/rollback`, { targetVersionNo })
  return data
}
