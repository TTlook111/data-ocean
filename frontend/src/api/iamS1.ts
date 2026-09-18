/**
 * IAM-SIMPLE-1（S1）前端 API 模块
 *
 * 约定：
 * - 本模块只调用 `/api/iam-s1/**` 新链路，不读取、不映射旧角色权限或旧数据授权。
 * - 同一接口只保留一个封装（与仓库既有约定一致）。
 * - 前端不是安全边界：这里只负责调用，权限结论由 Java 返回。
 */
import { http } from './http'
import type { ApiResult, PageResult } from './types'

const BASE = '/api/iam-s1'

// ---------------------------------------------------------------------------
// 类型：能力摘要与模板
// ---------------------------------------------------------------------------

export interface IamS1DatasourceCapability {
  datasourceId: number
  datasourceName: string
  functionCodes: string[]
  functionNames: string[]
}

export interface IamS1CapabilitySnapshot {
  protocolVersion: string
  userId: number
  systemAdmin: boolean
  globalFunctions: string[]
  datasourceCapabilities: IamS1DatasourceCapability[]
  queryUse: boolean
  viewSql: boolean
  export: boolean
  permissionRevision?: number
}

export interface IamS1SubjectOption {
  id: number
  name: string
  subjectType: 'USER' | 'ROLE' | 'DEPARTMENT'
  subjectTypeName: string
}

export interface IamS1DatasourceRef {
  id: number
  name: string
  enabled: boolean
}

export interface IamS1RoleTemplate {
  code: string
  name: string
  description: string
  functionCodes: string[]
  functionNames: string[]
  capabilitySummary: string
  dataHint: string
  systemAdminOnly: boolean
}

export interface IamS1GrantTemplate {
  code: string
  name: string
  description: string
  subjectType: 'USER' | 'ROLE' | 'DEPARTMENT'
  departmentScope?: 'SELF' | 'INCLUDE_DESCENDANTS' | null
  validDays?: number | null
}

export interface IamS1FunctionCatalogItem {
  code: string
  name: string
  description: string
  domain: string
  workspace: string
  dependencies: string[]
  systemAdminOnly: boolean
  active: boolean
}

// ---------------------------------------------------------------------------
// 类型：角色与用户角色
// ---------------------------------------------------------------------------

export interface IamS1Role {
  id: number
  roleCode: string
  roleName: string
  description?: string
  enabled: boolean
  protectedRole: boolean
  builtIn: boolean
  functionCodes: string[]
  functionNames: string[]
  capabilitySummary: string
  memberCount: number
  createdAt?: string
}

export interface IamS1UserRoleBinding {
  userRoleId: number
  userId: number
  roleId: number
  roleCode: string
  roleName: string
  roleEnabled: boolean
  bindingEnabled: boolean
  protectedRole: boolean
  functionNames: string[]
  capabilitySummary: string
  responsibleDatasources: IamS1DatasourceRef[]
  responsibleDatasourceSummary: string
}

export interface IamS1RoleSavePayload {
  roleCode: string
  roleName: string
  description?: string
  status?: number
  functionCodes: string[]
  reason?: string
}

// ---------------------------------------------------------------------------
// 类型：资源选项与数据授权
// ---------------------------------------------------------------------------

export interface IamS1SnapshotOption {
  id: number
  datasourceId: number
  snapshotVersion?: number
  status: string
}

export interface IamS1TableOption {
  datasourceId: number
  metadataSnapshotId: number
  tableName: string
  tableComment?: string
  governanceStatus?: string
  selectable: boolean
  columnCount: number
}

export interface IamS1ColumnOption {
  columnMetaId: number
  columnName: string
  columnComment?: string
  dataType?: string
  governanceStatus?: string
  protectionLevel?: string
  selectable: boolean
}

export interface IamS1RowConditionSummary {
  columnName: string
  operatorCode: string
  operatorName: string
  valueSummary: string
}

export interface IamS1DataGrant {
  id: number
  datasourceId: number
  datasourceName?: string
  subjectType: 'USER' | 'ROLE' | 'DEPARTMENT'
  subjectTypeName: string
  subjectId: number
  subjectName?: string
  departmentScope?: string | null
  departmentScopeName?: string | null
  resourceScope: string
  resourceScopeName?: string
  metadataSnapshotId?: number
  tableName?: string
  effect: string
  effectName?: string
  grantSource?: string
  sourceReferenceId?: number
  validFrom?: string
  validUntil?: string
  status: string
  revisionNo?: number
  columns: string[]
  rowMatchType?: string | null
  rowConditions: IamS1RowConditionSummary[]
  summary: string
}

export interface IamS1DataGrantColumnPayload {
  columnMetaId: number
  columnName: string
}

export interface IamS1RowConditionPayload {
  columnMetaId: number
  columnName: string
  operatorCode: string
  valueType: string
  structuredValueJson?: string | null
  parameterReference?: string | null
}

export interface IamS1DataGrantPayload {
  protocolVersion: string
  subjectType: 'USER' | 'ROLE' | 'DEPARTMENT'
  subjectId: number
  departmentScope?: string | null
  datasourceId: number
  resourceScope: 'DATASOURCE' | 'TABLE'
  metadataSnapshotId?: number | null
  tableName?: string | null
  effect: 'ALLOW' | 'DENY'
  validFrom?: string
  validUntil?: string | null
  rowMatchType?: string | null
  columns?: IamS1DataGrantColumnPayload[]
  rowConditions?: IamS1RowConditionPayload[]
  reason?: string
}

export interface IamS1FieldProtectionItem {
  id: number
  datasourceId: number
  datasourceName?: string
  metadataSnapshotId: number
  tableName: string
  columnMetaId: number
  columnName: string
  protectionLevel: string
  protectionLevelName?: string
  maskPolicy?: string | null
  maskPolicyName?: string | null
  status: string
  revisionNo?: number
  updatedAt?: string
}

export interface IamS1FieldProtectionPayload {
  protocolVersion: string
  datasourceId: number
  metadataSnapshotId: number
  tableName: string
  columnMetaId: number
  columnName: string
  protectionLevel: 'NORMAL' | 'HIDDEN' | 'MASKED'
  maskPolicy?: string | null
  reason?: string
}

// ---------------------------------------------------------------------------
// 类型：实际权限预览
// ---------------------------------------------------------------------------

export interface IamS1RowPredicate {
  columnMetaId?: number
  columnName: string
  operatorCode: string
  valueType: string
  parameterReference?: string | null
  bindingReference?: string | null
}

export interface IamS1RowConditionView {
  matchType: string
  predicates: IamS1RowPredicate[]
}

export interface IamS1GrantSourceView {
  grantId: number
  subjectType: string
  subjectId: number
  sourceSummary?: string
  departmentScope?: string | null
  grantSource?: string
  sourceReferenceId?: number
  validFrom?: string
  validUntil?: string
  explicitColumns: string[]
  rowCondition?: IamS1RowConditionView | null
}

export interface IamS1FieldProtectionView {
  columnMetaId?: number
  tableName: string
  columnName: string
  protectionLevel: string
  maskPolicy?: string | null
  reason?: string
}

export interface IamS1TablePermission {
  allowed: boolean
  reasonCode?: string
  tableName: string
  allowedColumns: string[]
  grantSources: IamS1GrantSourceView[]
  fieldProtections: IamS1FieldProtectionView[]
  reasons: string[]
}

export interface IamS1AuthorizationSnapshot {
  allowed: boolean
  reasonCode?: string
  protocolVersion?: string
  userId?: number
  datasourceId?: number
  datasourceName?: string
  activeMetadataSnapshotId?: number
  permissionRevision?: number
  calculatedAt?: string
  nextEffectiveAt?: string | null
  tables: IamS1TablePermission[]
}

export interface IamS1PreviewTablePayload {
  tableName: string
  referencedColumns: string[]
}

// ---------------------------------------------------------------------------
// 类型：访问申请与审批
// ---------------------------------------------------------------------------

export interface IamS1AccessApprovalView {
  decision: string
  decisionName?: string
  reviewerId: number
  reviewerName?: string | null
  approvedColumns: string[]
  approvedValidFrom?: string
  approvedValidUntil?: string
  generatedGrantId?: number
  reason?: string
  createdAt?: string
}

export interface IamS1AccessRequestView {
  id: number
  requesterId: number
  requesterName?: string | null
  datasourceId: number
  datasourceName?: string | null
  metadataSnapshotId: number
  tableName: string
  requestedColumns: string[]
  rowScope: string
  rowScopeName?: string
  requestedValidUntil?: string
  purpose: string
  status: string
  statusName?: string
  createdAt?: string
  approval?: IamS1AccessApprovalView | null
}

export interface IamS1AccessRequestSubmitPayload {
  datasourceId: number
  metadataSnapshotId: number
  tableName: string
  columns: string[]
  rowScope?: string
  requestedValidUntil?: string | null
  purpose: string
}

export interface IamS1AccessReviewPayload {
  decision: 'APPROVE' | 'REJECT'
  approvedColumns?: string[]
  approvedValidUntil?: string | null
  reason?: string
}

// ---------------------------------------------------------------------------
// 能力摘要 / 模板 / 选择对象
// ---------------------------------------------------------------------------

export async function getIamS1Capabilities() {
  const { data } = await http.get<ApiResult<IamS1CapabilitySnapshot>>(`${BASE}/capabilities`)
  return data
}

export async function listIamS1RoleTemplates() {
  const { data } = await http.get<ApiResult<IamS1RoleTemplate[]>>(`${BASE}/templates/role-templates`)
  return data
}

export async function listIamS1GrantTemplates() {
  const { data } = await http.get<ApiResult<IamS1GrantTemplate[]>>(`${BASE}/templates/grant-templates`)
  return data
}

export async function listIamS1Datasources() {
  const { data } = await http.get<ApiResult<IamS1DatasourceRef[]>>(`${BASE}/datasources`)
  return data
}

export async function listIamS1Subjects(subjectType?: string, keyword?: string) {
  const { data } = await http.get<ApiResult<IamS1SubjectOption[]>>(`${BASE}/subjects`, {
    params: { subjectType, keyword },
  })
  return data
}

export async function listIamS1Functions(domain?: string) {
  const { data } = await http.get<ApiResult<IamS1FunctionCatalogItem[]>>(`${BASE}/functions`, {
    params: { domain },
  })
  return data
}

// ---------------------------------------------------------------------------
// 角色 / 用户角色 / 负责源
// ---------------------------------------------------------------------------

export async function listIamS1Roles(keyword?: string) {
  const { data } = await http.get<ApiResult<IamS1Role[]>>(`${BASE}/roles`, { params: { keyword } })
  return data
}

export async function listIamS1RoleMembers(roleId: number) {
  const { data } = await http.get<ApiResult<IamS1UserRoleBinding[]>>(`${BASE}/roles/${roleId}/members`)
  return data
}

export async function createIamS1Role(payload: IamS1RoleSavePayload) {
  const { data } = await http.post<ApiResult<number>>(`${BASE}/roles`, payload)
  return data
}

export async function updateIamS1Role(roleId: number, payload: IamS1RoleSavePayload) {
  const { data } = await http.put<ApiResult<void>>(`${BASE}/roles/${roleId}`, payload)
  return data
}

export async function deleteIamS1Role(roleId: number, reason?: string) {
  const { data } = await http.delete<ApiResult<void>>(`${BASE}/roles/${roleId}`, { params: { reason } })
  return data
}

export async function listIamS1UserRoles(userId: number) {
  const { data } = await http.get<ApiResult<IamS1UserRoleBinding[]>>(`${BASE}/users/${userId}/roles`)
  return data
}

export async function assignIamS1UserRole(userId: number, roleId: number, reason?: string) {
  const { data } = await http.post<ApiResult<number>>(`${BASE}/users/${userId}/roles`, { roleId, reason })
  return data
}

export async function removeIamS1UserRole(userId: number, roleId: number, reason?: string) {
  const { data } = await http.delete<ApiResult<void>>(`${BASE}/users/${userId}/roles/${roleId}`, {
    params: { reason },
  })
  return data
}

export async function disableIamS1UserRole(userId: number, roleId: number, reason?: string) {
  const { data } = await http.patch<ApiResult<void>>(`${BASE}/users/${userId}/roles/${roleId}/disable`, null, {
    params: { reason },
  })
  return data
}

export async function listIamS1BindingDatasources(userRoleId: number) {
  const { data } = await http.get<ApiResult<IamS1DatasourceRef[]>>(`${BASE}/user-roles/${userRoleId}/datasources`)
  return data
}

export async function bindIamS1Datasources(userRoleId: number, datasourceIds: number[], reason?: string) {
  const { data } = await http.put<ApiResult<void>>(`${BASE}/user-roles/${userRoleId}/datasources`, {
    datasourceIds,
    reason,
  })
  return data
}

export async function removeIamS1Datasource(userRoleId: number, datasourceId: number, reason?: string) {
  const { data } = await http.delete<ApiResult<void>>(
    `${BASE}/user-roles/${userRoleId}/datasources/${datasourceId}`,
    { params: { reason } },
  )
  return data
}

// ---------------------------------------------------------------------------
// 资源选项
// ---------------------------------------------------------------------------

export async function listIamS1Snapshots(datasourceId: number) {
  const { data } = await http.get<ApiResult<IamS1SnapshotOption[]>>(
    `${BASE}/datasources/${datasourceId}/snapshots`,
  )
  return data
}

export async function listIamS1Tables(datasourceId: number, snapshotId: number) {
  const { data } = await http.get<ApiResult<IamS1TableOption[]>>(
    `${BASE}/datasources/${datasourceId}/snapshots/${snapshotId}/tables`,
  )
  return data
}

export async function listIamS1Columns(datasourceId: number, snapshotId: number, tableName: string) {
  const { data } = await http.get<ApiResult<IamS1ColumnOption[]>>(
    `${BASE}/datasources/${datasourceId}/snapshots/${snapshotId}/tables/${encodeURIComponent(tableName)}/columns`,
  )
  return data
}

// ---------------------------------------------------------------------------
// 数据授权 / 字段保护
// ---------------------------------------------------------------------------

export async function listIamS1DataGrants(params: {
  datasourceId: number
  subjectType?: string
  subjectId?: number
  tableName?: string
  status?: string
}) {
  const { data } = await http.get<ApiResult<IamS1DataGrant[]>>(`${BASE}/data-grants`, { params })
  return data
}

export async function createIamS1DataGrant(payload: IamS1DataGrantPayload) {
  const { data } = await http.post<ApiResult<number>>(`${BASE}/data-grants`, payload)
  return data
}

export async function updateIamS1DataGrant(grantId: number, payload: IamS1DataGrantPayload) {
  const { data } = await http.put<ApiResult<void>>(`${BASE}/data-grants/${grantId}`, payload)
  return data
}

export async function revokeIamS1DataGrant(grantId: number, reason?: string) {
  const { data } = await http.delete<ApiResult<void>>(`${BASE}/data-grants/${grantId}`, {
    params: { reason },
  })
  return data
}

export async function listIamS1FieldProtections(params: {
  datasourceId: number
  snapshotId?: number
  tableName?: string
}) {
  const { data } = await http.get<ApiResult<IamS1FieldProtectionItem[]>>(`${BASE}/field-protections`, {
    params,
  })
  return data
}

export async function saveIamS1FieldProtection(payload: IamS1FieldProtectionPayload) {
  const { data } = await http.post<ApiResult<number>>(`${BASE}/field-protections`, payload)
  return data
}

export async function revokeIamS1FieldProtection(protectionId: number, reason?: string) {
  const { data } = await http.delete<ApiResult<void>>(`${BASE}/field-protections/${protectionId}`, {
    params: { reason },
  })
  return data
}

// ---------------------------------------------------------------------------
// 实际权限预览（与真实查询同一个 Resolver）
// ---------------------------------------------------------------------------

export async function previewIamS1EffectivePermission(payload: {
  userId?: number
  datasourceId: number
  activeMetadataSnapshotId?: number
  tables: IamS1PreviewTablePayload[]
}) {
  const { data } = await http.post<ApiResult<IamS1AuthorizationSnapshot>>(
    `${BASE}/effective-permissions/preview`,
    { protocolVersion: 'IAM-SIMPLE-1', ...payload },
  )
  return data
}

// ---------------------------------------------------------------------------
// 访问申请与审批
// ---------------------------------------------------------------------------

export async function submitIamS1AccessRequest(payload: IamS1AccessRequestSubmitPayload) {
  const { data } = await http.post<ApiResult<number>>(`${BASE}/access-requests`, payload)
  return data
}

export async function listIamS1MyAccessRequests() {
  const { data } = await http.get<ApiResult<IamS1AccessRequestView[]>>(`${BASE}/access-requests/mine`)
  return data
}

export async function listIamS1AccessRequestQueue() {
  const { data } = await http.get<ApiResult<IamS1AccessRequestView[]>>(`${BASE}/access-requests/queue`)
  return data
}

export async function withdrawIamS1AccessRequest(requestId: number, reason?: string) {
  const { data } = await http.post<ApiResult<void>>(
    `${BASE}/access-requests/${requestId}/withdraw`,
    null,
    { params: { reason } },
  )
  return data
}

export async function reviewIamS1AccessRequest(requestId: number, payload: IamS1AccessReviewPayload) {
  const { data } = await http.post<ApiResult<Record<string, unknown>>>(
    `${BASE}/access-requests/${requestId}/review`,
    payload,
  )
  return data
}

// ---------------------------------------------------------------------------
// S1 独立查询链路（与旧 /api/query 完全分离，单次请求不混用两套体系）
// ---------------------------------------------------------------------------

/**
 * S1 资源声明的默认使用位置（usage）。
 *
 * 必须与后端 `IamS1UsageDefaults.DEFAULT_QUERY_USAGES` 保持一致：
 * Java `IamS1QueryServiceImpl.requireExplicitUsages` 会强制要求每个字段都声明 usage，
 * 缺失时直接以“字段 usage 缺失”拒绝提交。两侧口径必须同步修改。
 */
export const IAM_S1_DEFAULT_QUERY_USAGES = ['PROJECTION', 'FILTER', 'JOIN'] as const
/** 排序、分组、聚合与子查询位置；B3 暂缓在 AST 层强制，默认不声明，由调用方显式选择。 */
export const IAM_S1_EXTENDED_QUERY_USAGES = ['ORDER', 'GROUP', 'HAVING', 'FUNCTION', 'SUBQUERY'] as const

export type IamS1ColumnUsage =
  | 'PROJECTION'
  | 'FILTER'
  | 'JOIN'
  | 'ORDER'
  | 'GROUP'
  | 'HAVING'
  | 'FUNCTION'
  | 'SUBQUERY'

export interface IamS1TableDeclaration {
  tableName: string
  referencedColumns: string[]
  /** 每个字段允许出现的位置；缺省时提交前补默认集合。 */
  columnUsages?: Record<string, IamS1ColumnUsage[]>
}

export interface IamS1QueryAskPayload {
  datasourceId: number
  question: string
  conversationId?: number
  tables: IamS1TableDeclaration[]
}

export interface IamS1QueryAskResult {
  taskId: string
  protocolVersion: string
}

export async function iamS1Ask(payload: IamS1QueryAskPayload) {
  const { data } = await http.post<ApiResult<IamS1QueryAskResult>>(`${BASE}/query/ask`, {
    protocolVersion: 'IAM-SIMPLE-1',
    tables: payload.tables.map((table) => ({
      tableName: table.tableName,
      referencedColumns: table.referencedColumns,
      // 缺省补齐默认使用位置：后端对每个字段强制要求 usage，缺失即拒绝提交。
      columnUsages: Object.fromEntries(
        table.referencedColumns.map((column) => [
          column,
          table.columnUsages?.[column]?.length
            ? table.columnUsages[column]
            : [...IAM_S1_DEFAULT_QUERY_USAGES],
        ]),
      ),
    })),
    datasourceId: payload.datasourceId,
    question: payload.question,
    conversationId: payload.conversationId,
  })
  return data
}

export async function iamS1GetTask(taskId: string) {
  const { data } = await http.get<ApiResult<Record<string, unknown>>>(
    `${BASE}/query/tasks/${taskId}`,
    { timeout: 30000 },
  )
  return data
}

export async function iamS1ViewSql(taskId: string) {
  const { data } = await http.get<ApiResult<{ sql: string; permissionRevision?: number }>>(
    `${BASE}/query/tasks/${taskId}/sql`,
  )
  return data
}

export async function iamS1CancelTask(taskId: string) {
  const { data } = await http.post<ApiResult<void>>(`${BASE}/query/tasks/${taskId}/cancel`)
  return data
}

export async function iamS1QueryHistory(params: Record<string, unknown> = {}) {
  const { data } = await http.get<ApiResult<PageResult<Record<string, unknown>>>>(
    `${BASE}/query/history`,
    { params },
  )
  return data
}

export async function iamS1ExportRows(taskId: string) {
  const { data } = await http.get<ApiResult<Record<string, unknown>[]>>(
    `${BASE}/query/tasks/${taskId}/export`,
  )
  return data
}

export async function iamS1ExportCsv(taskId: string) {
  const { data } = await http.get<Blob>(`${BASE}/query/tasks/${taskId}/export.csv`, {
    responseType: 'blob',
  })
  return data
}

export async function iamS1SubmitFeedback(taskId: string, feedbackType: 'LIKE' | 'DISLIKE') {
  const { data } = await http.post<ApiResult<void>>(`${BASE}/query/tasks/${taskId}/feedback`, {
    feedbackType,
  })
  return data
}

/**
 * S1 任务 SSE 订阅。
 *
 * 使用 fetch + ReadableStream 以便携带 Authorization 头（EventSource 无法设置请求头）。
 * 事件流复用与 `GET /tasks/{taskId}` 相同的服务端读取路径（含 SQL 能力判定与当前权限再脱敏）。
 * 订阅失败时由上层的 S1 轮询兜底，不回退旧查询链路。
 */
export async function iamS1StreamTask(
  taskId: string,
  handlers: {
    onEvent: (event: { type: string; data: string }) => void
    signal?: AbortSignal
  },
): Promise<void> {
  const token = localStorage.getItem('dataocean_token')
  const base = import.meta.env.VITE_API_BASE_URL || ''
  const response = await fetch(`${base}${BASE}/query/tasks/${taskId}/stream`, {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    signal: handlers.signal,
  })
  if (!response.ok || !response.body) {
    throw new Error(`S1 任务事件流不可用（HTTP ${response.status}）`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      let eventType = 'message'
      const dataLines: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) eventType = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length) handlers.onEvent({ type: eventType, data: dataLines.join('\n') })
    }
  }
}

// ---------------------------------------------------------------------------
// 用户侧资源选择（问数资源声明 / 访问申请）
//
// 与后台配置资源接口（listIamS1Snapshots / listIamS1Tables / listIamS1Columns，
// 要求 security:permission:view + 后台负责源）严格分离：
// - QUERY：只列出该用户存在“主体命中且当前有效 ALLOW 授权”的数据源；
// - APPLY：列出存在已发布快照的启用数据源，只提供申请所需的安全名称。
// 两者都要求“使用问数”，普通问数用户不需要后台负责源。
// ---------------------------------------------------------------------------

export type IamS1ResourceScope = 'QUERY' | 'APPLY'

export async function listIamS1QueryResourceDatasources(scope: IamS1ResourceScope = 'QUERY') {
  const { data } = await http.get<ApiResult<IamS1DatasourceRef[]>>(
    `${BASE}/query-resources/datasources`,
    { params: { scope } },
  )
  return data
}

export async function listIamS1QueryResourceSnapshots(
  datasourceId: number,
  scope: IamS1ResourceScope = 'QUERY',
) {
  const { data } = await http.get<ApiResult<IamS1SnapshotOption[]>>(
    `${BASE}/query-resources/datasources/${datasourceId}/snapshots`,
    { params: { scope } },
  )
  return data
}

export async function listIamS1QueryResourceTables(
  datasourceId: number,
  snapshotId: number,
  scope: IamS1ResourceScope = 'QUERY',
) {
  const { data } = await http.get<ApiResult<IamS1TableOption[]>>(
    `${BASE}/query-resources/datasources/${datasourceId}/snapshots/${snapshotId}/tables`,
    { params: { scope } },
  )
  return data
}

export async function listIamS1QueryResourceColumns(
  datasourceId: number,
  snapshotId: number,
  tableName: string,
  scope: IamS1ResourceScope = 'QUERY',
) {
  const { data } = await http.get<ApiResult<IamS1ColumnOption[]>>(
    `${BASE}/query-resources/datasources/${datasourceId}/snapshots/${snapshotId}/tables/${encodeURIComponent(tableName)}/columns`,
    { params: { scope } },
  )
  return data
}
