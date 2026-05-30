import { http } from '../http'
import type { ApiResult, PageResult } from './user'

// ==================== 类型定义 ====================

export interface PredefinedTag {
  id: number
  tagCode: string
  tagName: string
  category: string
  description: string
  sortOrder: number
}

export interface FieldTagVO {
  id: number
  columnMetaId: number
  tagCode: string
  tagName: string
  source: string
  createdBy: number
  createdAt: string
}

export interface ConfidenceVO {
  columnMetaId: number
  columnName?: string
  tableName?: string
  score: number
  level: string
  reason: string
  lastUpdated: string
}

export interface ConfidenceEventVO {
  id: number
  deltaScore: number
  eventType: string
  sourceQueryId?: number
  operatorId?: number
  operatorName?: string
  createdAt: string
}

export interface ConfidenceTrendPoint {
  time: string
  deltaScore: number
  eventType: string
  cumulativeScore: number
}

export interface FeedbackVO {
  id: number
  queryTaskId: number
  columnMetaId: number
  columnName?: string
  tableName?: string
  userId: number
  username?: string
  feedbackType: string
  reasonCode?: string
  comment?: string
  reviewStatus?: string
  createdAt: string
}

// ==================== 字段标签 API ====================

export async function addFieldTag(payload: { columnMetaId: number; tagCode: string }) {
  const { data } = await http.post<ApiResult<FieldTagVO>>('/api/field-tags', payload)
  return data
}

export async function batchAddFieldTags(payload: { columnMetaIds: number[]; tagCode: string }) {
  const { data } = await http.post<ApiResult<{ tagged: number }>>('/api/field-tags/batch', payload)
  return data
}

export async function removeFieldTag(id: number) {
  const { data } = await http.delete<ApiResult<null>>(`/api/field-tags/${id}`)
  return data
}

export async function getFieldTags(columnMetaId: number) {
  const { data } = await http.get<ApiResult<FieldTagVO[]>>(`/api/field-tags/column/${columnMetaId}`)
  return data
}

export async function getColumnsByTag(tagCode: string) {
  const { data } = await http.get<ApiResult<number[]>>(`/api/field-tags/by-tag/${tagCode}`)
  return data
}

export async function listPredefinedTags() {
  const { data } = await http.get<ApiResult<PredefinedTag[]>>('/api/field-tags/predefined')
  return data
}

// ==================== 可信度 API ====================

export async function pageConfidence(params: {
  page?: number
  pageSize?: number
  level?: string
  datasourceId?: number
}) {
  const { data } = await http.get<ApiResult<PageResult<ConfidenceVO>>>('/api/field-confidence', { params })
  return data
}

export async function getFieldConfidence(columnMetaId: number) {
  const { data } = await http.get<ApiResult<ConfidenceVO>>(`/api/field-confidence/${columnMetaId}`)
  return data
}

export async function batchGetConfidence(columnMetaIds: number[]) {
  const { data } = await http.get<ApiResult<ConfidenceVO[]>>('/api/field-confidence/batch', {
    params: { columnMetaIds: columnMetaIds.join(',') }
  })
  return data
}

export async function adminSetConfidence(columnMetaId: number, payload: { score: number; reason?: string }) {
  const { data } = await http.put<ApiResult<ConfidenceVO>>(`/api/field-confidence/${columnMetaId}`, payload)
  return data
}

export async function getConfidenceEvents(columnMetaId: number) {
  const { data } = await http.get<ApiResult<ConfidenceEventVO[]>>(`/api/field-confidence/${columnMetaId}/events`)
  return data
}

export async function getConfidenceTrend(fieldId: number, days = 30) {
  const { data } = await http.get<ApiResult<ConfidenceTrendPoint[]>>(`/api/admin/fields/${fieldId}/confidence-trend`, {
    params: { days }
  })
  return data
}

// ==================== 用户反馈 API ====================

export async function submitFeedback(payload: {
  queryTaskId: number
  columnMetaId: number
  feedbackType: string
  reasonCode?: string
  comment?: string
}) {
  const { data } = await http.post<ApiResult<FeedbackVO>>('/api/feedback', payload)
  return data
}

// ==================== 反馈审核 API ====================

export async function listPendingReviews(params: { page?: number; pageSize?: number }) {
  const { data } = await http.get<ApiResult<PageResult<FeedbackVO>>>('/api/feedback-reviews', { params })
  return data
}

export async function approveFeedback(feedbackId: number, reviewComment?: string) {
  const { data } = await http.post<ApiResult<null>>(`/api/feedback-reviews/${feedbackId}/approve`, { reviewComment })
  return data
}

export async function rejectFeedback(feedbackId: number, reviewComment?: string) {
  const { data } = await http.post<ApiResult<null>>(`/api/feedback-reviews/${feedbackId}/reject`, { reviewComment })
  return data
}

// ==================== 批量操作 API ====================

export async function importFieldTags(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  const { data } = await http.post<ApiResult<{ success: number; failed: number }>>('/api/admin/fields/import-tags', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
  return data
}

export async function autoTagByPattern(datasourceId: number) {
  const { data } = await http.post<ApiResult<{ tagged: number; skipped: number }>>('/api/admin/fields/auto-tag', null, {
    params: { datasourceId }
  })
  return data
}
