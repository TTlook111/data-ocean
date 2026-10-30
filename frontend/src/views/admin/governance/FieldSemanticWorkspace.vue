<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useGovernanceScope } from '../../../composables/useGovernanceScope'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import FieldGovernanceProgress from '../../../components/admin/FieldGovernanceProgress.vue'
import {
  adoptFieldSemanticCandidate, confirmFieldSemanticBindings, confirmFieldSemanticBinding,
  generateFieldSemanticCandidate, getFieldSemanticWorkspace, getFieldSemanticWorkspaceDetails,
  getFieldSemanticUsageStatus, getFieldSemanticGlossaryOptions, getPendingFieldSemanticBindings,
  getLatestSemanticCheck, reviewFieldSemantic, revokeFieldSemantic, runSemanticCheck,
  saveFieldSemanticDraft, submitFieldSemanticDraft, withdrawFieldSemanticDraft,
  type FieldSemanticActionPayload, type FieldSemanticCandidate, type FieldSemanticRevision,
  type FieldSemanticWorkspace, type FieldSemanticUsageStatus, type FieldSemanticGlossaryOption,
  type FieldSemanticBindingBatchResult, type FieldSemanticPendingBinding, type SemanticCheckBatch,
} from '../../../api/admin/fieldSemantic'
import { governanceStatusLabel } from '../../../utils/enumLabels'
import { listSnapshotTableColumns, listSnapshotTables, type ColumnMetaItem, type TableMetaItem } from '../../../api/admin/governance'

const { context, iam: iamS1, auth, route, can, ready, scopeLoading, scopeIssue, capture, isCurrent, generation } = useGovernanceScope()
const router = useRouter()
const tables = ref<TableMetaItem[]>([]), columns = ref<ColumnMetaItem[]>([])
const selectedTable = ref(''), selectedColumnId = ref<number>()
const workspace = ref<FieldSemanticWorkspace>(), usageStatus = ref<FieldSemanticUsageStatus>()
const latestCheck = ref<SemanticCheckBatch | null>(null)
const usageError = ref(''), detailsError = ref(''), usageLoading = ref(false)
const glossaryOptions = ref<FieldSemanticGlossaryOption[]>([]), selectedGlossaryReviewIds = ref<number[]>([])
const glossaryOptionsLoading = ref(false), glossaryOptionsError = ref('')
const listLoading = ref(false), columnsLoading = ref(false), workspaceLoading = ref(false)
const actionLoading = ref(false), candidateLoading = ref(false), semanticCheckLoading = ref(false)
const listError = ref(''), workspaceError = ref(''), manualError = ref(''), candidateError = ref(''), semanticCheckError = ref('')
const reviewReason = ref(''), reviewEvidenceReference = ref(''), reviewEvidenceConfirmed = ref(false)
const revokeReason = ref(''), bindingEvidence = ref(''), conflictMessage = ref('')
const batchDialogVisible = ref(false), batchLoading = ref(false), batchSaving = ref(false)
const batchError = ref(''), batchEvidence = ref(''), batchCandidates = ref<FieldSemanticPendingBinding[]>([])
const batchSelectedIds = ref<number[]>([]), batchResult = ref<FieldSemanticBindingBatchResult>()
const candidateExpanded = ref(false)
const hasSearched = ref(false), activeEditorTab = ref('draft'), historyOpen = ref<string[]>([])
const form = reactive({ businessName: '', meaning: '', unit: '', currency: '', enumSemantics: '', metricDefinition: '', applicability: '', evidence: '', unresolvedText: '' })
const formBaseVersion = ref(0), savedFormStamp = ref(''), reviewBaseId = ref<number>(), reviewBaseVersion = ref(0)
const formStamp = computed(() => JSON.stringify([form, selectedGlossaryReviewIds.value]))
const editorDirty = computed(() => formStamp.value !== savedFormStamp.value)
let applyingForm = false, editRevision = 0
watch(formStamp, () => { if (!applyingForm) editRevision++ }, { flush: 'sync' })
const confirmOpen = ref(false)
const confirmClass = `field-semantic-cancel-${Math.random().toString(36).slice(2)}`
let confirmGeneration = 0
useAdminScrollLock(() => batchDialogVisible.value || confirmOpen.value)
let listRequest = 0, columnRequest = 0, workspaceRequest = 0, actionRequest = 0, candidateRequest = 0, checkRequest = 0, batchRequest = 0, batchOperation = 0, locationRequest = 0, disposed = false
const datasourceId = computed(() => context.datasourceId), snapshotId = computed(() => context.snapshotId)
const canViewField = computed(() => can('governance:field:view'))
const canReadDirectory = computed(() => can('metadata:view'))
const canViewDetails = computed(() => canViewField.value && can('knowledge:view'))
const canViewGlossary = computed(() => canViewField.value && can('glossary:view'))
const canManage = computed(() => canViewField.value && can('governance:field:manage') && can('knowledge:manage'))
const canReview = computed(() => canViewField.value && can('knowledge:approve'))
const canRevoke = computed(() => canViewField.value && can('governance:field:manage') && can('knowledge:approve'))
const canRunSemanticCheck = computed(() => can('governance:check'))
const fieldBusy = computed(() => actionLoading.value || candidateLoading.value)
const mayEdit = computed(() => canManage.value && !detailsError.value && workspace.value?.workingStatus !== 'PENDING_REVIEW')
const selectedColumn = computed(() => columns.value.find(column => column.id === selectedColumnId.value))
const currentApproved = computed(() => workspace.value?.currentApproved), currentDraft = computed(() => workspace.value?.draft)
const readyCandidate = computed(() => workspace.value?.candidates?.find(candidate => candidate.status === 'READY'))
const latestCandidate = computed(() => workspace.value?.candidates?.[0])
const pendingQuestions = computed(() => parseList(readyCandidate.value?.unresolvedItemsJson))
const isEffective = computed(() => workspace.value?.effectiveStatus === 'APPROVED')
const versionConflict = computed(() => !!workspace.value && formBaseVersion.value !== workspace.value.rowVersion)
const reviewConflict = computed(() => !!currentDraft.value && (reviewBaseId.value !== currentDraft.value.id || reviewBaseVersion.value !== workspace.value?.rowVersion))
function parseList(value?: string) { try { const items = JSON.parse(value || '[]'); return Array.isArray(items) ? items.map(String) : [] } catch { return [] } }
function parseGlossaryReviewIds(value?: string) { try { const items = JSON.parse(value || '[]'); return Array.isArray(items) ? items.map(item => Number(item.reviewId)).filter(id => Number.isSafeInteger(id) && id > 0) : [] } catch { return [] } }
function fillForm(revision?: FieldSemanticRevision, version = workspace.value?.rowVersion || 0) {
  applyingForm = true
  for (const key of ['businessName','meaning','unit','currency','enumSemantics','metricDefinition','applicability','evidence'] as const) form[key] = revision?.[key] || ''
  form.unresolvedText = parseList(revision?.unresolvedItemsJson).join('\n')
  selectedGlossaryReviewIds.value = parseGlossaryReviewIds(revision?.glossaryRefsJson)
  formBaseVersion.value = version; savedFormStamp.value = formStamp.value; applyingForm = false
}
fillForm()
function errorMessage(error: unknown, fallback: string) { return (error as { response?: { data?: { message?: string } } })?.response?.data?.message || (error instanceof Error ? error.message : fallback) }
function bindingLabel(status?: string) { const labels:Record<string,string>={VALID:'当前来源已核验',PENDING_CONFIRMATION:'待确认来源',CHANGED:'来源已变化',UNBOUND:'尚未绑定',INVALID:'来源无效'}; return status ? labels[status] || status : '未记录' }
function revisionLabel(status: string) { const labels:Record<string,string>={DRAFT:'草稿',PENDING_REVIEW:'待人工审核',APPROVED:'已审核',REJECTED:'已退回',WITHDRAWN:'已撤回'};return labels[status]||status }
function reviewActionLabel(action: string) { const labels: Record<string,string> = { DRAFT_SAVED:'保存草稿',SUBMIT:'提交审核',WITHDRAW:'撤回待审核修订',DRAFT_REPLACED:'替换草稿',AI_ADOPTED:'采纳 AI 候选为草稿',APPROVE:'审核通过',REJECT:'驳回并退回',REVOKE:'撤销当前解释',BIND_CONFIRM:'确认新来源绑定' }; return labels[action] || action }
function closeOwnedConfirm() { confirmGeneration++; document.querySelector<HTMLButtonElement>(`.${confirmClass}`)?.click(); confirmOpen.value = false }
function resetTarget() {
  workspaceRequest++; actionRequest++; candidateRequest++; closeOwnedConfirm()
  workspace.value = undefined; usageStatus.value = undefined; glossaryOptions.value = []
  workspaceLoading.value = false; usageLoading.value = false; glossaryOptionsLoading.value = false; actionLoading.value = false; candidateLoading.value = false
  workspaceError.value = ''; usageError.value = ''; detailsError.value = ''; glossaryOptionsError.value = ''; manualError.value = ''; candidateError.value = ''; conflictMessage.value = ''
  reviewReason.value = ''; reviewEvidenceReference.value = ''; reviewEvidenceConfirmed.value = false; revokeReason.value = ''; bindingEvidence.value = ''
  reviewBaseId.value = undefined; reviewBaseVersion.value = 0; hasSearched.value = false; candidateExpanded.value = false; activeEditorTab.value = 'draft'; historyOpen.value = []; fillForm()
}
function target() { return { scope: capture(), read: workspaceRequest, column: selectedColumnId.value, snapshot: snapshotId.value, source: datasourceId.value, table: selectedTable.value } }
type Target = ReturnType<typeof target>
function targetCurrent(t: Target, includeRead = true) { const raw = route.query.columnMetaId; const objectMatches = raw == null || (typeof raw === 'string' && Number(raw) === t.column && route.query.tableName === t.table); return objectMatches && isCurrent(t.scope) && canViewField.value && t.column === selectedColumnId.value && t.snapshot === snapshotId.value && t.source === datasourceId.value && t.table === selectedTable.value && (!includeRead || t.read === workspaceRequest) }
function validateWorkspace(value: FieldSemanticWorkspace | undefined, t: Target) {
  if (!value || value.columnMetaId !== t.column || value.snapshotId !== t.snapshot || value.datasourceId !== t.source || value.tableName !== t.table || (selectedColumn.value && value.columnName !== selectedColumn.value.columnName)) throw new Error('字段解释来源与当前字段、表或快照不一致。')
  return value
}
function baseWorkspace(value: FieldSemanticWorkspace) { const { draft, candidates, revisions, reviews, bindings, ...basic } = value; return basic }
async function restoreLocation(listTicket = listRequest) {
  const location = ++locationRequest
  const scope = capture(), tableName = typeof route.query.tableName === 'string' ? route.query.tableName : ''
  const raw = route.query.columnMetaId, columnId = raw == null ? undefined : typeof raw === 'string' ? Number(raw) : NaN
  if (columnId !== undefined && (!Number.isSafeInteger(columnId) || columnId <= 0 || !tableName)) { selectedColumnId.value = undefined; resetTarget(); listError.value = '指定字段标识或表上下文无效，未改用其他字段。'; return }
  if (!tableName) return
  if (!tables.value.some(table => table.tableName === tableName)) { selectedTable.value = ''; columns.value = []; selectedColumnId.value = undefined; resetTarget(); listError.value = '指定表不属于当前可读快照，未改用其他表。'; return }
  if (selectedTable.value !== tableName || !columns.value.length) await loadColumns(tableName)
  if (disposed || location !== locationRequest || listTicket !== listRequest || !isCurrent(scope) || selectedTable.value !== tableName || route.query.tableName !== tableName || (route.query.columnMetaId == null ? undefined : Number(route.query.columnMetaId)) !== columnId) return
  if (columnId) {
    if (!columns.value.some(column => column.id === columnId)) { selectedColumnId.value = undefined; resetTarget(); listError.value = '指定字段不属于当前表或无法读取，未改用其他字段。'; return }
    selectedColumnId.value = columnId
  }
}
async function loadTables() {
  const request = ++listRequest, scope = capture(), snapshot = snapshotId.value
  columnRequest++; tables.value = []; columns.value = []; selectedTable.value = ''; selectedColumnId.value = undefined; resetTarget(); listError.value = ''; listLoading.value = false; columnsLoading.value = false
  if (!canViewField.value || !canReadDirectory.value || !snapshot) return
  listLoading.value = true
  const current = () => !disposed && request === listRequest && isCurrent(scope) && canViewField.value && canReadDirectory.value
  try {
    const result = await listSnapshotTables(snapshot)
    if (!current()) return
    tables.value = result.data || []
    await restoreLocation(request)
  } catch (error) { if (current()) listError.value = errorMessage(error, '当前快照的字段目录读取失败。') }
  finally { if (current()) listLoading.value = false }
}
async function loadColumns(tableName: string) {
  const request = ++columnRequest, scope = capture(), snapshot = snapshotId.value
  selectedTable.value = tableName; selectedColumnId.value = undefined; columns.value = []; resetTarget(); listError.value = ''; columnsLoading.value = false
  if (!tableName || !snapshot || !canViewField.value || !canReadDirectory.value) return
  const current = () => !disposed && request === columnRequest && isCurrent(scope) && tableName === selectedTable.value && canReadDirectory.value
  columnsLoading.value = true
  try { const result = await listSnapshotTableColumns(snapshot, tableName); if (current()) columns.value = result.data || [] }
  catch (error) { if (current()) listError.value = errorMessage(error, '字段列表读取失败。') }
  finally { if (current()) columnsLoading.value = false }
}
function changeTable(name: string) { loadColumns(name); router.replace({ query: { ...route.query, tableName: name, columnMetaId: undefined } }) }
function changeColumn(value: number) { router.replace({ query: { ...route.query, tableName: selectedTable.value, columnMetaId: String(value) } }) }
async function loadWorkspace(columnId = selectedColumnId.value, keepForm = false, discardConfirmed = false) {
  if (!columnId || !snapshotId.value || !canViewField.value) return
  const preserveLocal = keepForm || (!discardConfirmed && (editorDirty.value || !!reviewReason.value || !!reviewEvidenceReference.value || reviewEvidenceConfirmed.value))
  const request = ++workspaceRequest, t = target(), revisionAtRead = editRevision
  const current = () => !disposed && request === workspaceRequest && targetCurrent(t)
  workspaceLoading.value = true; workspaceError.value = ''; detailsError.value = ''; usageError.value = ''; glossaryOptionsError.value = ''
  usageStatus.value = undefined; glossaryOptions.value = []; usageLoading.value = canViewDetails.value; glossaryOptionsLoading.value = canViewGlossary.value
  try {
    const result = await getFieldSemanticWorkspace(columnId, t.snapshot!)
    if (!current()) return
    let loaded = baseWorkspace(validateWorkspace(result.data, t)) as FieldSemanticWorkspace
    const [details, usage, glossary] = await Promise.allSettled([
      canViewDetails.value ? getFieldSemanticWorkspaceDetails(columnId, t.snapshot!) : Promise.resolve(undefined),
      canViewDetails.value ? getFieldSemanticUsageStatus(columnId, t.snapshot!) : Promise.resolve(undefined),
      canViewGlossary.value ? getFieldSemanticGlossaryOptions(columnId, t.snapshot!) : Promise.resolve(undefined),
    ])
    if (!current()) return
    if (details.status === 'fulfilled' && details.value) loaded = validateWorkspace(details.value.data, t)
    else if (details.status === 'rejected') detailsError.value = errorMessage(details.reason, '草稿与审核明细读取失败，请重新读取。')
    if (usage.status === 'fulfilled' && usage.value) usageStatus.value = usage.value.data
    else if (usage.status === 'rejected') usageError.value = errorMessage(usage.reason, '问数收录状态读取失败。')
    if (glossary.status === 'fulfilled' && glossary.value) glossaryOptions.value = glossary.value.data || []
    else if (glossary.status === 'rejected') glossaryOptionsError.value = errorMessage(glossary.reason, '已审核术语来源读取失败。')
    if (!current()) return
    workspace.value = loaded
    if (!preserveLocal && editRevision === revisionAtRead && !detailsError.value) { fillForm(loaded.draft, loaded.rowVersion); reviewBaseId.value = loaded.draft?.id; reviewBaseVersion.value = loaded.rowVersion; reviewReason.value = ''; reviewEvidenceReference.value = ''; reviewEvidenceConfirmed.value = false }
    if (!keepForm && !loaded.draft && loaded.candidates?.some(item => item.status === 'READY')) candidateExpanded.value = true
    if (loaded.draft?.status === 'PENDING_REVIEW' && !editorDirty.value) activeEditorTab.value = 'review'
  } catch (error) { if (current()) { workspace.value = undefined; workspaceError.value = errorMessage(error, '字段解释读取失败。') } }
  finally { if (current()) { workspaceLoading.value = false; usageLoading.value = false; glossaryOptionsLoading.value = false } }
}
function openDocuments() { if (can('knowledge:view')) router.push({path:'/admin/semantics/knowledge',query:{datasourceId:context.datasourceId,snapshotId:context.snapshotId}}) }
function draftPayload() { return { snapshotId: snapshotId.value!, expectedVersion: formBaseVersion.value, businessName: form.businessName.trim(), meaning: form.meaning.trim(), unit: form.unit.trim() || undefined, currency: form.currency.trim() || undefined, enumSemantics: form.enumSemantics.trim() || undefined, metricDefinition: form.metricDefinition.trim() || undefined, applicability: form.applicability.trim() || undefined, evidence: form.evidence.trim(), unresolvedItems: form.unresolvedText.split('\n').map(item => item.trim()).filter(Boolean), glossaryReviewIds: selectedGlossaryReviewIds.value.length ? [...selectedGlossaryReviewIds.value] : undefined } }
function currentAction(revisionId?: number, action?: FieldSemanticActionPayload['action'], reason?: string) { return { expectedVersion: workspace.value?.rowVersion || 0, revisionId, action, reason } }
async function mutateWorkspace(allowed: () => boolean, action: () => Promise<{ data: FieldSemanticWorkspace }>, success: string, replaceForm = false) {
  if (!selectedColumn.value || !workspace.value || fieldBusy.value || batchSaving.value || !allowed()) return false
  const request = ++actionRequest, t = target(), revisionAtStart = editRevision
  const current = () => !disposed && request === actionRequest && targetCurrent(t, false) && allowed()
  actionLoading.value = true; manualError.value = ''; conflictMessage.value = ''
  try {
    const result = await action()
    if (!current()) return false
    workspace.value = baseWorkspace(validateWorkspace(result.data, t)) as FieldSemanticWorkspace
    const changed = editRevision !== revisionAtStart
    if (replaceForm) formBaseVersion.value = result.data.rowVersion
    await loadWorkspace(t.column, !replaceForm || changed || !canViewDetails.value, replaceForm && !changed)
    if (!current()) return false
    if (replaceForm && editRevision === revisionAtStart && !canViewDetails.value) savedFormStamp.value = formStamp.value
    if (workspaceError.value || detailsError.value) { manualError.value = `${success} 明细重读未完成，请重试核对；原输入已保留。`; return true }
    ElMessage.success(success); return true
  } catch (error) {
    if (current()) { manualError.value = errorMessage(error, '字段解释操作失败。'); if ((error as { response?: { status?: number } })?.response?.status === 409) conflictMessage.value = '当前版本已变化；人工输入和审核依据已保留。请核对已保存版本，不会自动重试或改用新版本提交。' }
    return false
  } finally { if (!disposed && request === actionRequest && targetCurrent(t, false)) actionLoading.value = false }
}
async function saveDraft() {
  if (!mayEdit.value || fieldBusy.value || versionConflict.value) return
  if (!form.businessName.trim() || !form.meaning.trim() || !form.evidence.trim()) { manualError.value = '请填写业务名称、字段含义和业务依据。'; return }
  const column = selectedColumnId.value!, payload = draftPayload()
  await mutateWorkspace(() => mayEdit.value, () => saveFieldSemanticDraft(column, payload), '字段解释草稿已保存；保存不代表审核通过。', true)
}
async function submitDraft() {
  if (!canManage.value || !workspace.value?.workingRevisionId || editorDirty.value || versionConflict.value || workspace.value.workingStatus !== 'DRAFT') return
  const column = selectedColumnId.value!, payload = currentAction(workspace.value.workingRevisionId)
  await mutateWorkspace(() => canManage.value, () => submitFieldSemanticDraft(column, payload), '字段解释已提交审核。', true)
}
async function withdrawDraft() {
  if (!canManage.value || !workspace.value?.workingRevisionId || workspace.value.workingStatus !== 'PENDING_REVIEW') return
  const column = selectedColumnId.value!, payload = currentAction(workspace.value.workingRevisionId)
  await mutateWorkspace(() => canManage.value, () => withdrawFieldSemanticDraft(column, payload), '待审核修订已撤回，可以继续编辑。', true)
}
async function reviewDraft(action: 'APPROVE' | 'REJECT') {
  if (!canReview.value || !workspace.value?.draft || workspace.value.draft.status !== 'PENDING_REVIEW' || reviewConflict.value || fieldBusy.value) return
  if (!reviewReason.value.trim()) { manualError.value = '审核通过或驳回都需要填写审核依据。'; return }
  if (action === 'APPROVE' && (!reviewEvidenceReference.value.trim() || !reviewEvidenceConfirmed.value)) { manualError.value = '请填写原始资料引用并确认人工核对。'; return }
  const column = selectedColumnId.value!, t = target(), payload = { expectedVersion: reviewBaseVersion.value, revisionId: reviewBaseId.value, action, reason: reviewReason.value.trim(), evidenceReference: reviewEvidenceReference.value.trim(), evidenceConfirmed: reviewEvidenceConfirmed.value }
  if (await mutateWorkspace(() => canReview.value, () => reviewFieldSemantic(column, payload), action === 'APPROVE' ? '字段解释已审核通过；仍需同修订复查并核对文档与索引。' : '字段解释已退回，已有有效解释保持不变。', true)) {
    if (targetCurrent(t, false)) { reviewReason.value = ''; reviewEvidenceReference.value = ''; reviewEvidenceConfirmed.value = false }
  }
}
async function askOwned(message: string, title: string, t: Target, version: number, allowed: () => boolean) {
  if (confirmOpen.value || fieldBusy.value || !allowed()) return false
  const request = ++confirmGeneration; confirmOpen.value = true
  try { await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认继续', cancelButtonText: '取消', cancelButtonClass: confirmClass }); return request === confirmGeneration && targetCurrent(t) && workspace.value?.rowVersion === version && allowed() }
  catch { return false }
  finally { if (request === confirmGeneration) confirmOpen.value = false }
}
async function revoke() {
  if (!canRevoke.value || !workspace.value?.currentApprovedRevisionId || !revokeReason.value.trim() || fieldBusy.value) return
  const t = target(), version = workspace.value.rowVersion, payload = currentAction(workspace.value.currentApprovedRevisionId, undefined, revokeReason.value.trim())
  if (!await askOwned('撤销后，当前解释立即失去使用资格；不会自动回退到更早修订。', '撤销字段解释', t, version, () => canRevoke.value)) return
  if (await mutateWorkspace(() => canRevoke.value, () => revokeFieldSemantic(t.column!, payload), '当前字段解释已撤销。')) { if (targetCurrent(t, false)) revokeReason.value = '' }
}
async function confirmBinding() {
  if (!canManage.value || !workspace.value || !bindingEvidence.value.trim() || fieldBusy.value) return
  const t = target(), version = workspace.value.rowVersion, evidence = bindingEvidence.value.trim()
  if (await mutateWorkspace(() => canManage.value, () => confirmFieldSemanticBinding(t.column!, t.snapshot!, version, evidence), '当前快照来源已确认，并生成新的审核证据。')) { if (targetCurrent(t, false)) bindingEvidence.value = '' }
}
async function reloadDraft() {
  if (!workspace.value || fieldBusy.value) return
  const t = target(), version = workspace.value.rowVersion
  if ((editorDirty.value || reviewReason.value || reviewEvidenceReference.value || reviewEvidenceConfirmed.value) && !await askOwned('重新读取会放弃当前未保存输入与审核意见，请确认已妥善记录。', '重读已保存草稿', t, version, () => canViewField.value)) return
  if (targetCurrent(t)) { await loadWorkspace(t.column, false, true); if (targetCurrent(t, false) && !workspaceError.value && !detailsError.value) conflictMessage.value = '' }
}
async function generateCandidate() {
  if (!canManage.value || !workspace.value || fieldBusy.value || !selectedColumnId.value) return
  const t = target(), version = workspace.value.rowVersion, request = ++candidateRequest
  const current = () => !disposed && request === candidateRequest && targetCurrent(t) && canManage.value
  candidateLoading.value = true; candidateError.value = ''
  try {
    const result = await generateFieldSemanticCandidate(t.column!, t.snapshot!, version)
    if (!current()) return
    validateWorkspace(result.data, t)
    await loadWorkspace(t.column, true)
    if (request === candidateRequest && targetCurrent(t, false) && canManage.value) ElMessage.success('AI 候选已保存；人工输入保持不变，采纳后仍需审核。')
  } catch (error) { if (current()) candidateError.value = errorMessage(error, 'AI 候选生成失败；你仍可手工填写并保存。') }
  finally { if (!disposed && request === candidateRequest && targetCurrent(t, false)) candidateLoading.value = false }
}
async function adoptCandidate(candidate: FieldSemanticCandidate) {
  if (!canManage.value || !workspace.value || fieldBusy.value) return
  const t = target(), version = workspace.value.rowVersion, candidateId = candidate.id
  if (editorDirty.value && !await askOwned('采纳候选将创建新草稿并替换当前未保存输入。请先核对候选与人工依据。', '采纳 AI 候选', t, version, () => canManage.value)) return
  if (!targetCurrent(t)) return
  await mutateWorkspace(() => canManage.value, () => adoptFieldSemanticCandidate(t.column!, candidateId, version), 'AI 候选已采纳为草稿，仍需人工审核。', true)
}
async function loadLatestCheck() {
  const scope = capture(), request = ++checkRequest, snapshot = snapshotId.value
  latestCheck.value = null; semanticCheckError.value = ''; semanticCheckLoading.value = false
  if (!snapshot || !canRunSemanticCheck.value) return
  try { const result = await getLatestSemanticCheck(snapshot); if (isCurrent(scope) && request === checkRequest && canRunSemanticCheck.value) { if (result.data && (result.data.snapshotId !== snapshot || result.data.datasourceId !== datasourceId.value)) throw new Error('语义复查来源不一致。'); latestCheck.value = result.data } }
  catch (error) { if (isCurrent(scope) && request === checkRequest) semanticCheckError.value = errorMessage(error, '最近语义复查读取失败，覆盖状态不可判定。') }
}
async function executeSemanticCheck() {
  if (!snapshotId.value || !canRunSemanticCheck.value || semanticCheckLoading.value || fieldBusy.value) return
  const snapshot = snapshotId.value, scope = capture(), request = ++checkRequest
  const current = () => isCurrent(scope) && request === checkRequest && canRunSemanticCheck.value
  semanticCheckLoading.value = true; semanticCheckError.value = ''
  try {
    const result = await runSemanticCheck(snapshot)
    if (!current()) return
    if (result.data.snapshotId !== snapshot || result.data.datasourceId !== datasourceId.value) throw new Error('语义复查来源与当前范围不一致。')
    latestCheck.value = result.data
    if (result.data.status !== 'SUCCESS') semanticCheckError.value = result.data.errorSummary || '语义复查没有完整完成；当前覆盖状态不能视为通过。'
    else ElMessage.success(`语义复查完成：有效 ${result.data.validCount}，待补充 ${result.data.missingCount}，需复核 ${result.data.staleCount}。`)
  } catch (error) { if (current()) semanticCheckError.value = errorMessage(error, '语义复查失败。') }
  finally { if (current()) semanticCheckLoading.value = false }
}
async function openBatchBindingDialog() {
  if (!snapshotId.value || !canManage.value || fieldBusy.value || batchLoading.value || batchSaving.value) return
  const scope = capture(), snapshot = snapshotId.value, request = ++batchRequest
  batchDialogVisible.value = true; batchLoading.value = true; batchError.value = ''; batchEvidence.value = ''; batchCandidates.value = []; batchSelectedIds.value = []; batchResult.value = undefined
  const current = () => isCurrent(scope) && request === batchRequest && batchDialogVisible.value && canManage.value
  try { const result = await getPendingFieldSemanticBindings(snapshot); if (current()) batchCandidates.value = result.data || [] }
  catch (error) { if (current()) batchError.value = errorMessage(error, '待确认来源列表读取失败。') }
  finally { if (current()) batchLoading.value = false }
}
async function confirmBatchBindings() {
  if (!snapshotId.value || !canManage.value || batchSaving.value || batchLoading.value || !batchDialogVisible.value || !batchEvidence.value.trim()) return
  const selected = batchCandidates.value.filter(item => item.eligible && batchSelectedIds.value.includes(item.columnMetaId))
  if (!selected.length) return
  const scope = capture(), snapshot = snapshotId.value, request = ++batchOperation, payload = selected.map(item => ({ columnMetaId: item.columnMetaId, expectedVersion: item.expectedVersion, evidence: batchEvidence.value.trim() }))
  const current = () => isCurrent(scope) && request === batchOperation && batchDialogVisible.value && canManage.value
  batchSaving.value = true; batchError.value = ''
  try {
    const result = await confirmFieldSemanticBindings(snapshot, payload)
    if (!current()) return
    if (result.data.snapshotId !== snapshot || result.data.items.some(item => !payload.some(sent => sent.columnMetaId === item.columnMetaId))) throw new Error('批量确认结果与提交范围不一致。')
    batchResult.value = result.data
    const failed = new Set(result.data.items.filter(item => !item.success).map(item => item.columnMetaId)), succeeded = new Set(result.data.items.filter(item => item.success).map(item => item.columnMetaId))
    batchCandidates.value = batchCandidates.value.filter(item => !succeeded.has(item.columnMetaId)); batchSelectedIds.value = [...failed]
    if (!result.data.failedCount) ElMessage.success(`已确认 ${result.data.successCount} 个字段来源。`)
    else ElMessage.warning(`已确认 ${result.data.successCount} 个，${result.data.failedCount} 个未完成；逐项原因已保留。`)
    if (selectedColumnId.value && selected.some(item => item.columnMetaId === selectedColumnId.value)) await loadWorkspace(selectedColumnId.value, true)
  } catch (error) { if (current()) batchError.value = errorMessage(error, '批量来源确认失败。') }
  finally { if (current()) batchSaving.value = false }
}
watch(batchDialogVisible, value => { if (!value) { batchRequest++; batchOperation++; batchLoading.value = false; batchSaving.value = false } }, { flush: 'sync' })
watch(selectedColumnId, value => { resetTarget(); if (value) { hasSearched.value = true; loadWorkspace(value) } }, { flush: 'sync' })
watch(() => [route.query.tableName, route.query.columnMetaId], () => { locationRequest++; columnRequest++; columnsLoading.value = false; selectedColumnId.value = undefined; resetTarget(); if (tables.value.length) restoreLocation() }, { flush: 'sync' })
watch(generation, () => { locationRequest++; listRequest++; columnRequest++; checkRequest++; batchRequest++; batchOperation++; batchDialogVisible.value = false; batchCandidates.value = []; batchResult.value = undefined; latestCheck.value = null; semanticCheckLoading.value = false; semanticCheckError.value = ''; loadTables(); loadLatestCheck() }, { immediate: true, flush: 'sync' })
onMounted(async () => { const token = auth.token; try { await Promise.all([context.initialize(), iamS1.load()]); if (!disposed && token === auth.token) { loadTables(); loadLatestCheck() } } catch (error) { if (!disposed && token === auth.token) listError.value = errorMessage(error, '公共范围读取失败。') } })
onBeforeUnmount(() => { disposed = true; closeOwnedConfirm(); listRequest++; columnRequest++; workspaceRequest++; actionRequest++; candidateRequest++; checkRequest++; batchRequest++; batchOperation++ })
</script>
<template>
  <section class="field-semantic-workspace">
    <header class="field-semantic-workspace__intro">
      <p class="field-semantic-intro-copy">先理解来源，再核对候选与人工依据；审核后仍需同修订复查。</p>
      <div class="field-semantic-workspace__intro-actions">

        <el-button v-if="ready && canManage" :disabled="fieldBusy || batchSaving" @click="openBatchBindingDialog">批量确认来源</el-button>
        <el-button v-if="ready && canViewField" :disabled="!canRunSemanticCheck || fieldBusy" :loading="semanticCheckLoading" @click="executeSemanticCheck">复查当前快照</el-button>
      </div>
    </header>

    <el-dialog v-model="batchDialogVisible" title="批量确认字段来源" width="min(760px, 94vw)" top="5vh" class="field-binding-dialog" :close-on-click-modal="!batchSaving" :close-on-press-escape="!batchSaving" :show-close="!batchSaving">
      <p class="field-semantic-workspace__hint">仅勾选结构和依据与原审核来源一致的字段。每个字段仍会重新核验当前权限、版本及来源；下方依据会记录到每一条绑定审核证据中。</p>
      <ErrorState v-if="batchError" :message="batchError" />
      <LoadingState v-else-if="batchLoading" text="正在核验此快照的待确认绑定…" />
      <EmptyState v-else-if="!batchCandidates.length" message="当前快照没有待确认的字段来源绑定。" />
      <div v-else class="field-semantic-batch-list">
        <el-checkbox-group v-model="batchSelectedIds" :disabled="batchSaving">
          <div v-for="candidate in batchCandidates" :key="candidate.columnMetaId" class="field-semantic-batch-list__item">
            <el-checkbox :value="candidate.columnMetaId" :disabled="!candidate.eligible">
              <strong>{{ candidate.tableName }}.{{ candidate.columnName }}</strong>
              <span v-if="candidate.businessName"> · {{ candidate.businessName }}</span>
              <small>版本 {{ candidate.expectedVersion }}</small>
            </el-checkbox>
            <p v-if="candidate.reason" class="field-semantic-workspace__hint">{{ candidate.reason }}</p>
          </div>
        </el-checkbox-group>
        <el-form label-position="top">
          <el-form-item label="本批逐项核验依据" required>
            <el-input v-model="batchEvidence" :disabled="batchSaving" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="说明本批字段如何逐项核对结构和业务依据一致" />
          </el-form-item>
        </el-form>
      </div>
      <div v-if="batchResult" class="field-semantic-batch-result" aria-live="polite">
        <strong>已完成 {{ batchResult.successCount }} 项，未完成 {{ batchResult.failedCount }} 项</strong>
        <ul><li v-for="item in batchResult.items" :key="item.columnMetaId"><el-tag :type="item.success ? 'success' : 'danger'">{{ item.success ? '已确认' : '未完成' }}</el-tag><span>字段 #{{ item.columnMetaId }} · {{ item.message }}</span></li></ul>
      </div>
      <template #footer>
        <el-button :disabled="batchSaving" @click="batchDialogVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="batchLoading || batchSaving || !batchSelectedIds.length || !batchEvidence.trim()" :loading="batchSaving" @click="confirmBatchBindings">确认所选来源</el-button>
      </template>
    </el-dialog>

    <ErrorState v-if="semanticCheckError" :message="semanticCheckError" :show-retry="false" />
    <div v-if="latestCheck" class="field-semantic-check-summary" :class="{ 'is-failed': latestCheck.status !== 'SUCCESS' }">
      <template v-if="latestCheck.status === 'SUCCESS'">
        <strong>最近语义复查 · {{ latestCheck.startedAt }}</strong>
        <span>适用 {{ latestCheck.applicableCount }}</span><span>有效 {{ latestCheck.validCount }}</span>
        <span>待补充 {{ latestCheck.missingCount }}</span><span>需复核 {{ latestCheck.staleCount }}</span>
        <span>不适用 {{ latestCheck.notApplicableCount }}</span>
      </template>
      <template v-else><strong>最近语义复查：{{ latestCheck.status }}</strong><span>{{ latestCheck.errorSummary || '复查未完整完成，覆盖状态不可判定。' }}</span></template>
    </div>
    <p v-if="snapshotId && !canRunSemanticCheck" class="field-semantic-workspace__hint">执行语义复查需要 governance:check 与当前数据源范围；此检查独立运行，不计入来源质量分，也不改变快照发布状态。</p>

    <LoadingState v-if="scopeLoading" text="正在核验数据源、快照与能力…" />
    <ErrorState v-else-if="!ready" :message="scopeIssue" :show-retry="false" />
    <ErrorState v-else-if="!canReadDirectory" message="当前账号没有 metadata:view，无法读取表与字段目录；字段查看或审核权不会自动补足此权限。请由管理员核对角色范围。" :show-retry="false" />
    <EmptyState v-else-if="!datasourceId" message="请先在公共范围栏选择数据源。" />
    <EmptyState v-else-if="!snapshotId" message="请在公共范围栏明确选择快照后再整理字段解释。" />
    <EmptyState v-else-if="!canViewField" message="需要 governance:field:view 及当前数据源范围才能查看字段解释工作面。" />
    <template v-else>
      <ErrorState v-if="listError" :message="listError" @retry="loadTables" />
      <LoadingState v-else-if="listLoading" text="正在读取所选快照的字段目录…" />
      <EmptyState v-else-if="!tables.length" message="所选快照没有可查看的表结构。" />
      <div v-else class="field-semantic-workspace__selector">
        <el-form label-position="top" class="field-semantic-workspace__selector-grid">
          <el-form-item label="表" required><el-select :model-value="selectedTable" aria-label="选择解释表" placeholder="选择表" filterable @change="changeTable"><el-option v-for="table in tables" :key="table.tableName" :label="table.tableComment ? `${table.tableName} · ${table.tableComment}` : table.tableName" :value="table.tableName" /></el-select></el-form-item>
          <el-form-item label="字段" required>
            <el-select v-model="selectedColumnId" aria-label="选择解释字段" @change="changeColumn" placeholder="选择字段" filterable :loading="columnsLoading" :disabled="!selectedTable || !columns.length">
              <el-option v-for="column in columns" :key="column.id" :label="`${column.columnName} · ${column.dataType}`" :value="column.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <p v-if="selectedTable && !columnsLoading && !columns.length" class="field-semantic-workspace__hint">当前筛选表没有字段。</p>
      </div>

      <ErrorState v-if="workspaceError" :message="workspaceError" @retry="loadWorkspace(selectedColumnId, true)" />
      <LoadingState v-else-if="workspaceLoading && !workspace" text="正在核验字段来源、解释与审核状态…" />
      <EmptyState v-else-if="hasSearched && !workspace" message="当前筛选字段没有可读取的解释记录。" />
      <section v-else-if="workspace" class="field-semantic-workspace__content">
        <FieldGovernanceProgress :column-id="workspace.columnMetaId" :snapshot-id="workspace.snapshotId" :version="workspace.rowVersion" :check-id="latestCheck?.id" />
        <aside class="field-semantic-reference">
        <section class="field-semantic-card field-semantic-card--source">
          <div class="field-semantic-card__heading"><div><h3>{{ workspace.tableName }}.{{ workspace.columnName }}</h3><p>{{ workspace.dataType || '类型未记录' }} · 治理状态 {{ governanceStatusLabel(workspace.governanceStatus) }}</p></div><el-tag :type="isEffective ? 'success' : workspace.bindingStatus === 'VALID' ? 'warning' : 'info'">{{ isEffective ? '当前解释有效' : workspace.effectiveStatus === 'NEEDS_REVIEW' ? '来源需复核' : '尚无有效解释' }}</el-tag></div>
          <div class="field-semantic-card__source-notes"><div><span>原始采集注释</span><p>{{ workspace.rawColumnComment || '原始注释为空' }}</p></div><div><span>当前快照绑定</span><p>{{ bindingLabel(workspace.bindingStatus) }}<template v-if="workspace.bindingReason"> · {{ workspace.bindingReason }}</template></p></div></div>
        </section>

        <section class="field-semantic-card">
          <div class="field-semantic-card__heading"><div><h3>当前已审核解释</h3><p v-if="currentApproved">修订 v{{ currentApproved.revisionNo }} · {{ revisionLabel(currentApproved.status) }}</p></div><el-tag v-if="currentApproved" :type="isEffective ? 'success' : 'warning'">{{ isEffective ? '适用于当前来源' : '不能用于当前来源' }}</el-tag></div>
          <div v-if="currentApproved" class="field-semantic-usage-status" aria-label="问数收录状态">
            <strong>问数收录</strong>
            <span v-if="usageLoading">正在核对当前活动构建…</span>
            <ErrorState v-else-if="usageError" :message="usageError" />
            <span v-else-if="usageStatus">{{ usageStatus.message }}<template v-if="usageStatus.buildId"> · Build {{ usageStatus.buildId }}</template><template v-if="usageStatus.docId"> · 文档版本 v{{ usageStatus.versionNo }}</template></span>
            <span v-else>需要 knowledge:view 才能查看当前活动构建的收录状态。</span><el-button v-if="canViewDetails" link type="primary" @click="openDocuments">知识文档与构建</el-button>
          </div>
          <template v-if="currentApproved">
            <h4>{{ currentApproved.businessName }}</h4><p>{{ currentApproved.meaning }}</p>
            <dl class="field-semantic-facts"><div v-if="currentApproved.unit"><dt>单位</dt><dd>{{ currentApproved.unit }}</dd></div><div v-if="currentApproved.currency"><dt>币种</dt><dd>{{ currentApproved.currency }}</dd></div><div v-if="currentApproved.enumSemantics"><dt>枚举含义</dt><dd>{{ currentApproved.enumSemantics }}</dd></div><div v-if="currentApproved.metricDefinition"><dt>指标口径</dt><dd>{{ currentApproved.metricDefinition }}</dd></div><div v-if="currentApproved.applicability"><dt>适用限制</dt><dd>{{ currentApproved.applicability }}</dd></div><div><dt>业务依据</dt><dd>{{ currentApproved.evidence }}</dd></div></dl>
            <p v-if="!isEffective" class="field-semantic-workspace__hint">当前快照未通过来源绑定及审核证据核验；这条历史解释不会自动用于当前问数。</p>
          </template>
          <EmptyState v-else message="当前字段尚无经过审核的业务解释。" />
          <div v-if="workspace.bindingStatus === 'PENDING_CONFIRMATION' && currentApproved && canManage" class="field-semantic-workspace__binding-confirm">
            <el-input v-model="bindingEvidence" aria-label="来源绑定核验依据" placeholder="记录该字段结构未变化的核验依据" />
            <el-button :disabled="!bindingEvidence.trim() || fieldBusy" :loading="actionLoading" @click="confirmBinding">确认当前来源</el-button>
          </div>
          <el-input v-if="currentApproved && isEffective && canRevoke" v-model="revokeReason" aria-label="撤销原因" placeholder="填写撤销原因" class="field-semantic-workspace__reason" />
          <el-button v-if="currentApproved && isEffective && canRevoke" type="danger" plain :disabled="!revokeReason.trim() || fieldBusy" :loading="actionLoading" @click="revoke">撤销当前解释</el-button>
        </section>

        </aside>
        <section class="field-semantic-card">
          <div class="field-semantic-card__heading"><div><h3>修订工作面</h3><p>{{ editorDirty ? '有未保存输入' : '当前输入与已读草稿一致' }}</p></div><el-button :disabled="fieldBusy" @click="reloadDraft">重读已保存草稿</el-button></div>
          <p v-if="detailsError" class="field-semantic-workspace__hint" role="alert">{{ detailsError }} 当前明细未读完整，请重读后再编辑或审核。</p>
          <p v-if="conflictMessage || versionConflict" class="field-semantic-conflict" role="alert">{{ conflictMessage || '已读记录版本发生变化；人工输入保持不变，请核对后重读已保存草稿。' }}</p>
          <ErrorState v-if="manualError" :message="manualError" :show-retry="false" />
          <el-tabs v-model="activeEditorTab" class="field-editor-tabs">
          <el-tab-pane label="候选与草稿" name="draft">
          <div class="field-draft-heading"><span>候选单独保存，人工采纳后才成为草稿。</span><el-button :disabled="!canManage || fieldBusy || workspace.governanceStatus === 'BLOCKED' || workspace.governanceStatus === 'DEPRECATED'" :loading="candidateLoading" @click="generateCandidate">生成 AI 候选</el-button></div>
          <p v-if="!canManage" class="field-semantic-workspace__hint">保存、生成、采纳和提交需要 governance:field:manage 与 knowledge:manage 同时绑定当前数据源。</p>
          <ErrorState v-if="candidateError" :message="candidateError" />
          <article v-if="readyCandidate" class="field-semantic-candidate">
            <div class="field-semantic-candidate__heading"><strong>AI 建议 · 待人工采纳</strong><el-button v-if="candidateExpanded" type="primary" link :disabled="!canManage || fieldBusy" :loading="actionLoading" @click="adoptCandidate(readyCandidate)">采纳为草稿</el-button><el-button link @click="candidateExpanded = !candidateExpanded">{{ candidateExpanded ? '收起候选' : '查看候选' }}</el-button></div><template v-if="candidateExpanded">
            <h4>{{ readyCandidate.businessName }}</h4><p>{{ readyCandidate.meaning }}</p><p v-if="readyCandidate.evidence"><strong>AI 参考说明（未经确认）：</strong>{{ readyCandidate.evidence }}</p>
            <ul v-if="pendingQuestions.length"><li v-for="question in pendingQuestions" :key="question">待确认：{{ question }}</li></ul></template>
          </article>
          <p v-else-if="latestCandidate?.failureReason" class="field-semantic-workspace__hint">{{ latestCandidate.failureReason }}</p>

          <el-form label-position="top" class="field-semantic-form">
            <el-form-item label="业务名称" required><el-input v-model="form.businessName" aria-label="业务名称" maxlength="200" show-word-limit :disabled="!mayEdit || actionLoading" /></el-form-item>
            <el-form-item label="字段含义" required><el-input v-model="form.meaning" aria-label="字段含义" type="textarea" :rows="3" maxlength="4000" show-word-limit :disabled="!mayEdit || actionLoading" /></el-form-item>
            <div class="field-semantic-form__grid"><el-form-item label="单位"><el-input v-model="form.unit" aria-label="单位" maxlength="200" :disabled="!mayEdit || actionLoading" /></el-form-item><el-form-item label="币种"><el-input v-model="form.currency" aria-label="币种" maxlength="100" :disabled="!mayEdit || actionLoading" /></el-form-item></div>
            <el-form-item label="枚举含义"><el-input v-model="form.enumSemantics" aria-label="枚举含义" type="textarea" :rows="2" maxlength="4000" :disabled="!mayEdit || actionLoading" placeholder="无法依据审核材料确认的值，请留空并列入待确认项" /></el-form-item>
            <el-form-item label="指标口径"><el-input v-model="form.metricDefinition" aria-label="指标口径" type="textarea" :rows="2" maxlength="4000" :disabled="!mayEdit || actionLoading" placeholder="不确定时不要推断计算公式" /></el-form-item>
            <el-form-item label="适用限制"><el-input v-model="form.applicability" aria-label="适用限制" type="textarea" :rows="2" maxlength="2000" :disabled="!mayEdit || actionLoading" /></el-form-item>
            <el-form-item label="业务依据" required><el-input v-model="form.evidence" aria-label="业务依据" type="textarea" :rows="2" maxlength="4000" :disabled="!mayEdit || actionLoading" placeholder="说明依据来自何处、如何核实" /></el-form-item>
            <el-form-item label="关联已审核术语（可选）">
              <el-select v-model="selectedGlossaryReviewIds" aria-label="关联已审核术语" multiple clearable filterable :loading="glossaryOptionsLoading" :disabled="!mayEdit || actionLoading || (!canViewGlossary && !selectedGlossaryReviewIds.length)" placeholder="选择映射到当前字段的已审核术语">
                <el-option v-for="option in glossaryOptions" :key="option.reviewId" :label="`${option.name} · 审核 ${option.reviewId}`" :value="option.reviewId">
                  <span>{{ option.name }}</span><small class="field-semantic-glossary-option">{{ option.description || option.synonyms || '完整映射已核验' }}</small>
                </el-option>
              </el-select>
              <p v-if="glossaryOptionsError" class="field-semantic-workspace__hint">{{ glossaryOptionsError }}</p>
              <p v-else-if="!canViewGlossary" class="field-semantic-workspace__hint">引用或保留术语需要 glossary:view；清空选择可独立整理字段解释。</p>
            </el-form-item>
            <el-form-item label="待确认项"><el-input v-model="form.unresolvedText" aria-label="待确认项" type="textarea" :rows="2" :disabled="!mayEdit || actionLoading" placeholder="每行一个；未确认的单位、币种、枚举和口径继续保持待确认" /></el-form-item>
          </el-form>

          <div class="field-semantic-actions">
            <el-button type="primary" :disabled="!mayEdit || fieldBusy || versionConflict" :loading="actionLoading" @click="saveDraft">保存草稿</el-button>
            <el-button v-if="(currentDraft?.status === 'DRAFT' || workspace.workingStatus === 'DRAFT') && workspace.workingRevisionId" :disabled="!canManage || fieldBusy || versionConflict || editorDirty" :loading="actionLoading" @click="submitDraft">提交审核</el-button>
            <el-button v-if="(currentDraft?.status === 'PENDING_REVIEW' || workspace.workingStatus === 'PENDING_REVIEW') && workspace.workingRevisionId" :disabled="!canManage || fieldBusy" :loading="actionLoading" @click="withdrawDraft">撤回待审核</el-button>
          </div>
          </el-tab-pane>
          <el-tab-pane label="人工审核" name="review">
          <p v-if="reviewConflict" class="field-semantic-conflict" role="alert">待审核修订或记录版本已变化，原审核意见已保留；请核对并重读后审核。</p>
          <section v-if="currentDraft" class="field-review-preview"><span>已保存修订 v{{ currentDraft.revisionNo }} · {{ revisionLabel(currentDraft.status) }}</span><h3>{{ currentDraft.businessName }}</h3><p>{{ currentDraft.meaning }}</p><dl class="field-semantic-facts"><div v-for="item in [{label:'单位',value:currentDraft.unit},{label:'币种',value:currentDraft.currency},{label:'枚举含义',value:currentDraft.enumSemantics},{label:'指标口径',value:currentDraft.metricDefinition},{label:'适用限制',value:currentDraft.applicability},{label:'业务依据',value:currentDraft.evidence}]" :key="item.label"><template v-if="item.value"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></template></div></dl><ul v-if="parseList(currentDraft.unresolvedItemsJson).length"><li v-for="item in parseList(currentDraft.unresolvedItemsJson)" :key="item">待确认：{{ item }}</li></ul></section>
          <EmptyState v-else :message="canViewDetails ? '当前没有可读取的待审草稿，请先保存并提交修订。' : '查看草稿与审核明细需要 knowledge:view；此页不会代读。'" />
          <div v-if="currentDraft?.status === 'PENDING_REVIEW'" class="field-semantic-review-box">
            <p>待审核修订 v{{ currentDraft.revisionNo }}。审核权限需要 governance:field:view 与 knowledge:approve 同时绑定当前数据源。</p>
            <el-input v-model="reviewEvidenceReference" :disabled="!canReview || actionLoading" aria-label="字段解释原始资料引用" maxlength="1000" placeholder="原始资料名称、版本及章节，或平台确认记录位置" />
            <el-input v-model="reviewReason" :disabled="!canReview || actionLoading" aria-label="字段解释审核依据" maxlength="800" placeholder="说明原始资料如何支持字段含义、单位和使用条件" />
            <el-checkbox v-model="reviewEvidenceConfirmed" :disabled="!canReview || actionLoading">已人工核对原始资料；AI 自述不作为确认依据</el-checkbox>
            <div><el-button type="success" :disabled="!canReview || fieldBusy || reviewConflict || !reviewReason.trim() || !reviewEvidenceReference.trim() || !reviewEvidenceConfirmed" :loading="actionLoading" @click="reviewDraft('APPROVE')">审核通过</el-button><el-button type="danger" plain :disabled="!canReview || fieldBusy || reviewConflict || !reviewReason.trim()" :loading="actionLoading" @click="reviewDraft('REJECT')">驳回并退回</el-button></div>
          </div>
          </el-tab-pane>
          <el-tab-pane label="来源与历史" name="history">
          <p v-if="canViewField && !canViewDetails" class="field-semantic-workspace__hint">查看草稿、AI 候选和审核明细还需要 knowledge:view。当前角色仍可按已授予的维护权限创建草稿。</p>
          <p v-if="canViewDetails && workspace.reviews?.length" class="field-semantic-workspace__history-title">审核与来源操作</p>
          <ol v-if="canViewDetails && workspace.reviews?.length" class="field-semantic-history">
            <li v-for="review in workspace.reviews" :key="review.id"><strong>{{ reviewActionLabel(review.action) }}</strong><span>{{ review.createdAt }} · 操作人 #{{ review.operatorId }}</span><p v-if="review.reason">{{ review.reason }}</p></li>
          </ol>
          <p v-if="workspace.revisions?.length" class="field-semantic-workspace__history-title">历史修订</p>
          <ol v-if="workspace.revisions?.length" class="field-semantic-history"><li v-for="revision in workspace.revisions" :key="revision.id"><strong>v{{ revision.revisionNo }} · {{ revisionLabel(revision.status) }}</strong><span>{{ revision.businessName }} · {{ revision.createdAt }}</span></li></ol>          <EmptyState v-if="canViewDetails && !workspace.revisions?.length && !workspace.reviews?.length" message="当前没有已返回的审核或历史修订记录。" />
          </el-tab-pane></el-tabs>

        </section>
      </section>
    </template>
  </section>
</template>

<style scoped>
.field-semantic-workspace__intro { align-items:center; }
.field-semantic-intro-copy { margin:0; color:var(--do-muted); font-size:12px; line-height:1.7; }
.field-semantic-workspace__selector { border-radius:0; border-width:1px 0; padding:12px 0; background:transparent; }
.field-semantic-workspace__selector :deep(.el-form-item) { display:grid; grid-template-columns:44px minmax(0,1fr); margin:0; align-items:center; }
.field-semantic-workspace__selector :deep(.el-form-item__label) { padding:0; margin:0; height:auto; }
.field-semantic-workspace__selector :deep(.el-form-item__content) { min-width:0; }

.field-semantic-reference { min-width:0; display:grid; align-content:start; gap:16px; }
.field-semantic-workspace__content { display:grid; grid-template-columns:minmax(270px,.8fr) minmax(0,1.7fr); align-items:start; gap:20px; }
.field-semantic-workspace__content > .field-governance-progress { grid-column:1 / -1; }
.field-semantic-workspace__content > .field-semantic-card { min-width:0; }
.field-semantic-reference .field-semantic-card { padding:18px; border-radius:10px; }
.field-semantic-reference .field-semantic-card__source-notes { grid-template-columns:1fr; }
.field-semantic-reference .field-semantic-card__source-notes > div { padding:8px 0; border-radius:0; background:transparent; }
.field-semantic-reference .field-semantic-card__heading { flex-wrap:wrap; }
.field-draft-heading { display:flex; flex-wrap:wrap; justify-content:space-between; align-items:center; gap:12px; margin-bottom:18px; }
.field-draft-heading > span { color:var(--do-muted); font-size:12px; }
.field-editor-tabs :deep(.el-tabs__header) { margin-bottom:20px; }
.field-editor-tabs :deep(.el-tabs__content) { overflow:visible; }
.field-review-preview { padding:16px; background:var(--do-soft); border-radius:8px; margin-bottom:16px; }
.field-review-preview > span { color:var(--do-muted); font-size:12px; }
.field-review-preview p { white-space:pre-wrap; line-height:1.7; overflow-wrap:anywhere; }
.field-semantic-conflict { margin:10px 0; padding:10px 12px; border-left:2px solid var(--do-warning); background:var(--do-warning-soft); color:var(--do-warning); font-size:13px; line-height:1.7; }
@media(max-width:1100px) { .field-semantic-workspace__content { grid-template-columns:1fr; } .field-semantic-reference { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media(max-width:760px) { .field-semantic-reference { grid-template-columns:1fr; } }

.field-semantic-workspace { display: grid; gap: 14px; min-width: 0; }
.field-semantic-workspace__intro,.field-semantic-card__heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.field-semantic-workspace__intro-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.field-semantic-check-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 16px; padding: 10px 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); color: var(--do-ink); background: var(--do-surface); font-size: 12px; }
.field-semantic-check-summary strong { font-weight: 600; }
.field-semantic-check-summary.is-failed { border-color: var(--do-danger); color: var(--do-danger); }
.field-semantic-workspace__intro h2,.field-semantic-card h3 { margin: 0; color: var(--do-ink); font-size: 16px; }
.field-semantic-workspace__intro p,.field-semantic-card__heading p,.field-semantic-card__heading + p,.field-semantic-workspace__hint { margin: 6px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
.field-semantic-card { min-width: 0; padding: 16px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.field-semantic-workspace__selector-grid,.field-semantic-form__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.field-semantic-workspace__selector :deep(.el-select) { width: 100%; }

.field-semantic-card__heading { margin-bottom: 12px; }
.field-semantic-card__heading p { max-width: 650px; }
.field-semantic-card h4 { margin: 8px 0; color: var(--do-ink); font-size: 15px; }
.field-semantic-card > p { color: var(--do-ink); line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.field-semantic-card--source { border-color: color-mix(in srgb, var(--do-primary) 30%, var(--do-line)); }
.field-semantic-card__source-notes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.field-semantic-card__source-notes > div { padding: 10px 12px; border-radius: var(--do-radius-md); background: var(--do-bg); }
.field-semantic-card__source-notes span,.field-semantic-facts dt { color: var(--do-muted); font-size: 12px; }
.field-semantic-card__source-notes p { margin: 5px 0 0; color: var(--do-ink); white-space: pre-wrap; overflow-wrap: anywhere; }
.field-semantic-facts { display: grid; gap: 8px; margin: 12px 0 0; }
.field-semantic-usage-status { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0; padding: 10px 12px; border-radius: var(--do-radius-md); background: var(--do-bg); color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.field-semantic-usage-status strong { color: var(--do-ink); }
.field-semantic-facts > div { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 10px; }
.field-semantic-facts dd { margin: 0; color: var(--do-ink); white-space: pre-wrap; overflow-wrap: anywhere; }
.field-semantic-workspace__binding-confirm { display: flex; gap: 8px; margin-top: 12px; }
.field-semantic-batch-list { max-height: 42vh; overflow: auto; padding-right: 6px; }
.field-semantic-batch-list__item { padding: 8px 0; border-bottom: 1px solid var(--do-line); }
.field-semantic-batch-list__item .el-checkbox { display: flex; align-items: flex-start; }
.field-semantic-batch-list__item strong { color: var(--do-ink); overflow-wrap: anywhere; }
.field-semantic-batch-list__item small { display: block; color: var(--do-muted); font-size: 11px; }
.field-semantic-batch-list__item p { margin: 4px 0 0 26px; }
.field-semantic-batch-result { margin-top: 14px; padding: 12px; border-radius: var(--do-radius-md); background: var(--do-bg); color: var(--do-ink); }
.field-semantic-batch-result ul { display: grid; gap: 7px; max-height: 24vh; overflow: auto; padding: 0; list-style: none; }
.field-semantic-batch-result li { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; }
.field-semantic-workspace__reason { margin: 12px 0 8px; }
.field-semantic-form { display: grid; grid-template-columns: minmax(0, 1fr); }
.field-semantic-form :deep(.el-form-item) { min-width: 0; }
.field-semantic-form :deep(.el-select) { width: 100%; }
.field-semantic-glossary-option { display: block; max-width: 360px; overflow: hidden; color: var(--do-muted); text-overflow: ellipsis; }
.field-semantic-candidate { margin: 14px 0; padding: 12px; border: 1px solid var(--do-warning); border-radius: var(--do-radius-md); background: var(--do-bg); }
.field-semantic-candidate__heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; color: var(--do-warning); }
.field-semantic-candidate p { color: var(--do-ink); line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.field-semantic-candidate ul { margin-bottom: 0; padding-left: 20px; color: var(--do-warning); }
.field-semantic-actions,.field-semantic-review-box > div { display: flex; flex-wrap: wrap; gap: 8px; }
.field-semantic-review-box { display: grid; gap: 10px; margin-top: 14px; padding: 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); }
.field-semantic-review-box p { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
.field-semantic-workspace__history-title { margin: 18px 0 6px !important; font-weight: 600; }
.field-semantic-history { display: grid; gap: 8px; margin: 0; padding-left: 20px; }
.field-semantic-history li { display: flex; flex-wrap: wrap; gap: 8px; color: var(--do-ink); font-size: 12px; }
.field-semantic-history li span { color: var(--do-muted); }
.field-semantic-history li p { flex-basis: 100%; margin: 0; color: var(--do-muted); white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .field-semantic-workspace__selector-grid,.field-semantic-form__grid,.field-semantic-card__source-notes { grid-template-columns: minmax(0, 1fr); }
  .field-semantic-facts > div { grid-template-columns: minmax(0, 1fr); gap: 3px; }
  .field-semantic-workspace__binding-confirm { flex-direction: column; }
}
</style>
