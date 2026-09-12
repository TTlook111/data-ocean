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

/**
 * 审核记录（对应 `knowledge_review_task`）。
 *
 * 该表此前只写不读——全项目没有任何 Controller 暴露它，因此作者被驳回后看不到原因。
 * 2026-09-12 后端补上查询接口后，前端才能展示审核人、审核时间与审核意见。
 */
export interface KnowledgeReviewRecord {
  id: number
  docVersionId?: number
  versionNo?: number
  reviewStatus: string
  reviewComment?: string
  reviewerId?: number
  reviewerName?: string
  submittedAt?: string
  reviewedAt?: string
}

/** 查询文档的审核记录（最新在前） */
export async function listReviewTasks(docId: number) {
  const { data } = await http.get<ApiResult<KnowledgeReviewRecord[]>>(
    `/api/admin/knowledge-docs/${docId}/review-tasks`,
  )
  return data
}

/**
 * 向量化任务（对应 `vector_index_task`）。
 *
 * 该表此前同样无查询入口，文档处于 `INDEXING` 时前端只能显示状态，
 * 无法显示进度与失败原因（开发指导 §7.11 要求「显示进度和失败信息」）。
 */
export interface VectorIndexTaskItem {
  id: number
  datasourceId?: number
  targetType: string
  targetId: number
  metadataSnapshotId?: number
  knowledgeVersionNo?: number
  previousVersionNo?: number
  status: string
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
  createdAt: string
}

/** 查询文档的向量化任务（最新在前） */
export async function listVectorTasks(docId: number) {
  const { data } = await http.get<ApiResult<VectorIndexTaskItem[]>>(
    `/api/admin/knowledge-docs/${docId}/vector-tasks`,
  )
  return data
}

/**
 * 文档版本的来源快照（按版本号降序，一个版本一条）。
 *
 * 来源快照记录在 `knowledge_doc_version.metadata_snapshot_id` 上。2026-09-12 之前前端只能
 * 显示裸 ID，或「取版本列表 + 取数据源快照列表」两次请求再自行关联——而引用的快照一旦
 * 不在已加载的分页范围内就关联不上（跨数据源的版本尤其如此）。
 *
 * `snapshotVersion` / `status` / `tableCount` / `columnCount` 为 null 表示该快照已不存在。
 */
export interface KnowledgeSourceSnapshot {
  versionNo: number
  snapshotId: number
  snapshotVersion?: number
  status?: string
  tableCount?: number
  columnCount?: number
  createdAt?: string
}

/** 查询文档各版本的来源快照 */
export async function listSourceSnapshots(docId: number) {
  const { data } = await http.get<ApiResult<KnowledgeSourceSnapshot[]>>(
    `/api/admin/knowledge-docs/${docId}/source-snapshots`,
  )
  return data
}
