import { http } from '../http'
import type { ApiResult } from './user'

export interface FieldSemanticRevision {
  id: number
  definitionId: number
  bindingId: number
  revisionNo: number
  status: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string
  businessName: string
  meaning: string
  unit?: string
  currency?: string
  enumSemantics?: string
  metricDefinition?: string
  applicability?: string
  evidence: string
  unresolvedItemsJson?: string
  glossaryRefsJson?: string
  contentHash: string
  dependencyHash: string
  createdBy: number
  createdAt: string
  submittedAt?: string
}

export interface FieldSemanticCandidate {
  id: number
  status: 'RUNNING' | 'READY' | 'FAILED' | 'ADOPTED' | 'STALE' | string
  businessName?: string
  meaning?: string
  unit?: string
  currency?: string
  enumSemantics?: string
  metricDefinition?: string
  applicability?: string
  evidence?: string
  unresolvedItemsJson?: string
  failureReason?: string
  createdAt: string
}

export interface FieldSemanticWorkspace {
  generatedCandidateId?:number
  datasourceId: number
  snapshotId: number
  tableMetaId: number
  columnMetaId: number
  databaseName?: string
  tableName: string
  columnName: string
  dataType?: string
  rawColumnComment?: string
  governanceStatus?: string
  definitionId?: number
  rowVersion: number
  currentBindingId?: number
  currentApprovedRevisionId?: number
  workingRevisionId?: number
  workingStatus?: string
  definitionStatus?: string
  bindingStatus?: string
  bindingReason?: string
  effectiveStatus: 'APPROVED' | 'NO_APPROVED_EXPLANATION' | 'NEEDS_REVIEW' | string
  currentApproved?: FieldSemanticRevision
  draft?: FieldSemanticRevision
  revisions?: FieldSemanticRevision[]
  reviews?: Array<{ id: number; action: string; reason?: string; operatorId: number; createdAt: string }>
  bindings?: Array<{ id: number; snapshotId: number; status: string; statusReason?: string; schemaFingerprint: string }>
  candidates?: FieldSemanticCandidate[]
}

export interface FieldSemanticDraftPayload {
  snapshotId: number
  expectedVersion: number
  businessName: string
  meaning: string
  unit?: string
  currency?: string
  enumSemantics?: string
  metricDefinition?: string
  applicability?: string
  evidence: string
  unresolvedItems: string[]
  glossaryReviewIds?: number[]
}

export interface FieldSemanticActionPayload {
  expectedVersion: number
  revisionId?: number
  action?: 'APPROVE' | 'REJECT'
  reason?: string
  evidenceReference?: string
  evidenceConfirmed?: boolean
}

export interface SemanticCheckBatch {
  id: number
  datasourceId: number
  snapshotId: number
  status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED' | string
  applicableCount: number
  validCount: number
  missingCount: number
  staleCount: number
  notApplicableCount: number
  errorSummary?: string
  ruleVersion: number
  startedAt: string
  finishedAt?: string
}

export interface FieldSemanticUsageStatus {
  status: 'INCLUDED' | 'PENDING_UPDATE' | 'PENDING_REBUILD' | 'NOT_BUILT' | 'UNAVAILABLE' | string
  message: string
  buildId?: string
  sourceSnapshotId?: number
  chunkId?: number
  docId?: number
  versionNo?: number
}

export interface FieldSemanticGlossaryOption {
  termId: number
  reviewId: number
  name: string
  description: string
  synonyms: string
  dependencies: string[]
}

export interface FieldSemanticPendingBinding {
  columnMetaId: number
  expectedVersion: number
  tableName: string
  columnName: string
  businessName?: string
  eligible: boolean
  reason?: string
}

export interface FieldSemanticBindingBatchResult {
  snapshotId: number
  totalCount: number
  successCount: number
  failedCount: number
  items: Array<{ columnMetaId: number; success: boolean; message: string }>
}

async function unwrap<T>(promise: Promise<{ data: ApiResult<T> }>) {
  const { data } = await promise
  return data
}

const root = (columnMetaId: number) => `/api/admin/field-semantics/columns/${columnMetaId}`

export interface FieldGovernanceProgress {
  columnMetaId: number
  snapshotId: number
  stage: string
  nextAction: string
  reason: string
  revisionId?: number
  checkBatchId?: number
}

export const getFieldGovernanceProgress = (columnMetaId: number, snapshotId: number) =>
  unwrap(http.get<ApiResult<FieldGovernanceProgress>>(`${root(columnMetaId)}/governance-progress`, { params: { snapshotId } }))

export const getFieldSemanticWorkspace = (columnMetaId: number, snapshotId: number) =>
  unwrap(http.get<ApiResult<FieldSemanticWorkspace>>(root(columnMetaId), { params: { snapshotId } }))

export const getFieldSemanticWorkspaceDetails = (columnMetaId: number, snapshotId: number) =>
  unwrap(http.get<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/details`, { params: { snapshotId } }))

export const getFieldSemanticUsageStatus = (columnMetaId: number, snapshotId: number) =>
  unwrap(http.get<ApiResult<FieldSemanticUsageStatus>>(`${root(columnMetaId)}/usage`, { params: { snapshotId } }))

export const getFieldSemanticGlossaryOptions = (columnMetaId: number, snapshotId: number) =>
  unwrap(http.get<ApiResult<FieldSemanticGlossaryOption[]>>(`${root(columnMetaId)}/glossary-options`, { params: { snapshotId } }))

export const saveFieldSemanticDraft = (columnMetaId: number, payload: FieldSemanticDraftPayload) =>
  unwrap(http.put<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/draft`, payload))

export const submitFieldSemanticDraft = (columnMetaId: number, payload: FieldSemanticActionPayload) =>
  unwrap(http.post<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/submit`, payload))

export const withdrawFieldSemanticDraft = (columnMetaId: number, payload: FieldSemanticActionPayload) =>
  unwrap(http.post<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/withdraw`, payload))

export const reviewFieldSemantic = (columnMetaId: number, payload: FieldSemanticActionPayload) =>
  unwrap(http.post<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/review`, payload))

export const revokeFieldSemantic = (columnMetaId: number, payload: FieldSemanticActionPayload) =>
  unwrap(http.post<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/revoke`, payload))

export const confirmFieldSemanticBinding = (columnMetaId: number, snapshotId: number, expectedVersion: number, evidence: string) =>
  unwrap(http.post<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/bindings/${snapshotId}/confirm`, { expectedVersion, evidence }))

export const getPendingFieldSemanticBindings = (snapshotId: number) =>
  unwrap(http.get<ApiResult<FieldSemanticPendingBinding[]>>(`/api/admin/field-semantics/snapshots/${snapshotId}/bindings/pending-confirmations`))

export const confirmFieldSemanticBindings = (snapshotId: number, items: Array<{ columnMetaId: number; expectedVersion: number; evidence: string }>) =>
  unwrap(http.post<ApiResult<FieldSemanticBindingBatchResult>>(`/api/admin/field-semantics/snapshots/${snapshotId}/bindings/confirmations`, { items }))

export const generateFieldSemanticCandidate = (columnMetaId: number, snapshotId: number, expectedVersion: number) =>
  unwrap(http.post<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/candidates`, null, { params: { snapshotId, expectedVersion } }))

export const adoptFieldSemanticCandidate = (columnMetaId: number, candidateId: number, expectedVersion: number) =>
  unwrap(http.post<ApiResult<FieldSemanticWorkspace>>(`${root(columnMetaId)}/candidates/${candidateId}/adopt`, { expectedVersion }))

export const runSemanticCheck = (snapshotId: number) =>
  unwrap(http.post<ApiResult<SemanticCheckBatch>>(`/api/admin/semantic-checks/snapshots/${snapshotId}`))

export const getLatestSemanticCheck = (snapshotId: number) =>
  unwrap(http.get<ApiResult<SemanticCheckBatch | null>>(`/api/admin/semantic-checks/snapshots/${snapshotId}/latest`))
