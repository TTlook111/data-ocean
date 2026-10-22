<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import {
  adoptFieldSemanticCandidate,
  confirmFieldSemanticBindings,
  confirmFieldSemanticBinding,
  generateFieldSemanticCandidate,
  getFieldSemanticWorkspace,
  getFieldSemanticWorkspaceDetails,
  getFieldSemanticUsageStatus,
  getFieldSemanticGlossaryOptions,
  getPendingFieldSemanticBindings,
  getLatestSemanticCheck,
  reviewFieldSemantic,
  revokeFieldSemantic,
  runSemanticCheck,
  saveFieldSemanticDraft,
  submitFieldSemanticDraft,
  withdrawFieldSemanticDraft,
  type FieldSemanticActionPayload,
  type FieldSemanticCandidate,
  type FieldSemanticRevision,
  type FieldSemanticWorkspace,
  type FieldSemanticUsageStatus,
  type FieldSemanticGlossaryOption,
  type FieldSemanticBindingBatchResult,
  type FieldSemanticPendingBinding,
  type SemanticCheckBatch,
} from '../../../api/admin/fieldSemantic'
import { listSnapshotTableColumns, listSnapshotTables, type ColumnMetaItem, type TableMetaItem } from '../../../api/admin/governance'

const context = useAdminContextStore()
const iamS1 = useIamS1Store()
const route = useRoute()
const tables = ref<TableMetaItem[]>([])
const columns = ref<ColumnMetaItem[]>([])
const selectedTable = ref('')
const selectedColumnId = ref<number>()
const workspace = ref<FieldSemanticWorkspace>()
const usageStatus = ref<FieldSemanticUsageStatus>()
const usageError = ref('')
const usageLoading = ref(false)
const latestCheck = ref<SemanticCheckBatch | null>(null)
const glossaryOptions = ref<FieldSemanticGlossaryOption[]>([])
const glossaryOptionsLoading = ref(false)
const glossaryOptionsError = ref('')
const selectedGlossaryReviewIds = ref<number[]>([])
const listLoading = ref(false)
const columnsLoading = ref(false)
const workspaceLoading = ref(false)
const actionLoading = ref(false)
const candidateLoading = ref(false)
const semanticCheckLoading = ref(false)
const listError = ref('')
const workspaceError = ref('')
const manualError = ref('')
const candidateError = ref('')
const semanticCheckError = ref('')
const reviewReason = ref('')
const revokeReason = ref('')
const bindingEvidence = ref('')
const batchDialogVisible = ref(false)
const batchLoading = ref(false)
const batchSaving = ref(false)
const batchError = ref('')
const batchEvidence = ref('')
const batchCandidates = ref<FieldSemanticPendingBinding[]>([])
const batchSelectedIds = ref<number[]>([])
const batchResult = ref<FieldSemanticBindingBatchResult>()
const hasSearched = ref(false)
const form = reactive({
  businessName: '', meaning: '', unit: '', currency: '', enumSemantics: '',
  metricDefinition: '', applicability: '', evidence: '', unresolvedText: '',
})
let listRequest = 0
let columnRequest = 0
let workspaceRequest = 0
let actionRequest = 0
let disposed = false

const datasourceId = computed(() => context.datasourceId)
const snapshotId = computed(() => context.snapshotId)
const canViewField = computed(() => iamS1.canOnDatasource('governance:field:view', datasourceId.value))
const canViewDetails = computed(() => canViewField.value && iamS1.canOnDatasource('knowledge:view', datasourceId.value))
const canViewGlossary = computed(() => iamS1.canOnDatasource('glossary:view', datasourceId.value))
const canManage = computed(() => iamS1.canOnDatasource('governance:field:manage', datasourceId.value)
  && iamS1.canOnDatasource('knowledge:manage', datasourceId.value))
const canReview = computed(() => canViewField.value && iamS1.canOnDatasource('knowledge:approve', datasourceId.value))
const canRevoke = computed(() => iamS1.canOnDatasource('governance:field:manage', datasourceId.value)
  && iamS1.canOnDatasource('knowledge:approve', datasourceId.value))
const canRunSemanticCheck = computed(() => iamS1.canOnDatasource('governance:check', datasourceId.value))
const selectedColumn = computed(() => columns.value.find((column) => column.id === selectedColumnId.value))
const currentApproved = computed(() => workspace.value?.currentApproved)
const currentDraft = computed(() => workspace.value?.draft)
const readyCandidate = computed(() => workspace.value?.candidates?.find((candidate) => candidate.status === 'READY'))
const latestCandidate = computed(() => workspace.value?.candidates?.[0])
const pendingQuestions = computed(() => parseList(readyCandidate.value?.unresolvedItemsJson))
const isEffective = computed(() => workspace.value?.effectiveStatus === 'APPROVED')

function parseList(value?: string) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch { return [] }
}

function fillForm(revision?: FieldSemanticRevision) {
  form.businessName = revision?.businessName || ''
  form.meaning = revision?.meaning || ''
  form.unit = revision?.unit || ''
  form.currency = revision?.currency || ''
  form.enumSemantics = revision?.enumSemantics || ''
  form.metricDefinition = revision?.metricDefinition || ''
  form.applicability = revision?.applicability || ''
  form.evidence = revision?.evidence || ''
  form.unresolvedText = parseList(revision?.unresolvedItemsJson).join('\n')
  selectedGlossaryReviewIds.value = parseGlossaryReviewIds(revision?.glossaryRefsJson)
}

function parseGlossaryReviewIds(value?: string) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map((item) => Number(item.reviewId)).filter((id) => Number.isFinite(id) && id > 0) : []
  } catch { return [] }
}

function errorMessage(error: unknown, fallback: string) {
  const response = (error as { response?: { data?: { message?: string } } })?.response
  return response?.data?.message || (error instanceof Error ? error.message : fallback)
}

function reviewActionLabel(action: string) {
  const labels: Record<string, string> = {
    DRAFT_SAVED: '保存草稿', SUBMIT: '提交审核', WITHDRAW: '撤回待审核修订',
    DRAFT_REPLACED: '替换草稿', AI_ADOPTED: '采纳 AI 候选为草稿',
    APPROVE: '审核通过', REJECT: '驳回并退回', REVOKE: '撤销当前解释',
    BIND_CONFIRM: '确认新来源绑定',
  }
  return labels[action] || action
}

async function loadTables() {
  const request = ++listRequest
  tables.value = []
  columns.value = []
  workspace.value = undefined
  usageStatus.value = undefined
  usageError.value = ''
  glossaryOptions.value = []
  glossaryOptionsError.value = ''
  selectedTable.value = ''
  selectedColumnId.value = undefined
  listError.value = ''
  hasSearched.value = false
  batchDialogVisible.value = false
  if (!snapshotId.value) return
  latestCheck.value = null
  semanticCheckError.value = ''
  if (!canViewField.value) return
  listLoading.value = true
  try {
    const response = await listSnapshotTables(snapshotId.value)
    if (disposed || request !== listRequest || snapshotId.value === undefined) return
    tables.value = response.data || []
    if (snapshotId.value && canRunSemanticCheck.value) {
      try {
        const latest = await getLatestSemanticCheck(snapshotId.value)
        if (!disposed && request === listRequest && snapshotId.value === latest.data?.snapshotId) latestCheck.value = latest.data
      } catch {
        // The semantic-check result has its own status; a failure never appears as zero coverage.
      }
    }
    const routeColumnId = Number(route.query.columnMetaId) || undefined
    const routeSnapshotId = Number(route.query.snapshotId) || undefined
    const routeDatasourceId = Number(route.query.datasourceId) || undefined
    const routeTable = String(route.query.tableName || '')
    if (routeColumnId && routeTable && routeSnapshotId === snapshotId.value
      && routeDatasourceId === datasourceId.value && tables.value.some((item) => item.tableName === routeTable)) {
      await loadColumns(routeTable)
      if (!disposed && request === listRequest && routeSnapshotId === snapshotId.value
        && routeColumnId && columns.value.some((column) => column.id === routeColumnId)) {
        selectedColumnId.value = routeColumnId
      }
    }
  } catch (error) {
    if (!disposed && request === listRequest) listError.value = errorMessage(error, '当前快照的字段目录读取失败。')
  } finally {
    if (!disposed && request === listRequest) listLoading.value = false
  }
}

async function loadColumns(tableName: string) {
  const request = ++columnRequest
  const currentSnapshotId = snapshotId.value
  selectedTable.value = tableName
  selectedColumnId.value = undefined
  columns.value = []
  workspace.value = undefined
  usageStatus.value = undefined
  usageError.value = ''
  glossaryOptions.value = []
  glossaryOptionsError.value = ''
  hasSearched.value = false
  if (!tableName || !currentSnapshotId) return
  columnsLoading.value = true
  try {
    const response = await listSnapshotTableColumns(currentSnapshotId, tableName)
    if (disposed || request !== columnRequest || tableName !== selectedTable.value || currentSnapshotId !== snapshotId.value) return
    columns.value = response.data || []
  } catch (error) {
    if (!disposed && request === columnRequest) listError.value = errorMessage(error, '字段列表读取失败。')
  } finally {
    if (!disposed && request === columnRequest) columnsLoading.value = false
  }
}

async function loadWorkspace(columnId = selectedColumnId.value, keepForm = false) {
  const request = ++workspaceRequest
  const currentSnapshotId = snapshotId.value
  if (!columnId || !currentSnapshotId) return
  selectedColumnId.value = columnId
  hasSearched.value = true
  workspaceLoading.value = true
  usageLoading.value = canViewDetails.value
  workspaceError.value = ''
  glossaryOptionsError.value = ''
  manualError.value = ''
  try {
    const result = await getFieldSemanticWorkspace(columnId, currentSnapshotId)
    if (disposed || request !== workspaceRequest || columnId !== selectedColumnId.value
      || currentSnapshotId !== snapshotId.value) return
    let loaded = result.data
    if (canViewDetails.value) {
      const [details, usage] = await Promise.allSettled([
        getFieldSemanticWorkspaceDetails(columnId, currentSnapshotId),
        getFieldSemanticUsageStatus(columnId, currentSnapshotId),
      ])
      if (disposed || request !== workspaceRequest || columnId !== selectedColumnId.value
        || currentSnapshotId !== snapshotId.value) return
      if (details.status === 'fulfilled') loaded = details.value.data
      if (usage.status === 'fulfilled') {
        usageStatus.value = usage.value.data
        usageError.value = ''
      } else {
        usageStatus.value = undefined
        usageError.value = errorMessage(usage.reason, '问数收录状态读取失败。')
      }
      if (canViewGlossary.value) {
        glossaryOptionsLoading.value = true
        try {
          const glossary = await getFieldSemanticGlossaryOptions(columnId, currentSnapshotId)
          if (!disposed && request === workspaceRequest && selectedColumnId.value === columnId) glossaryOptions.value = glossary.data || []
        } catch (error) {
          if (!disposed && request === workspaceRequest) glossaryOptionsError.value = errorMessage(error, '已审核术语来源读取失败。')
        } finally {
          glossaryOptionsLoading.value = false
        }
      } else {
        glossaryOptions.value = []
        glossaryOptionsError.value = ''
      }
    } else {
      usageStatus.value = undefined
      usageError.value = ''
      glossaryOptions.value = []
      glossaryOptionsError.value = ''
    }
    workspace.value = loaded
    if (!keepForm) fillForm(loaded.draft)
  } catch (error) {
    if (!disposed && request === workspaceRequest) {
      workspace.value = undefined
      workspaceError.value = errorMessage(error, '字段解释读取失败。')
    }
  } finally {
    if (!disposed && request === workspaceRequest) {
      workspaceLoading.value = false
      usageLoading.value = false
    }
  }
}

function draftPayload() {
  return {
    snapshotId: snapshotId.value!, expectedVersion: workspace.value?.rowVersion || 0,
    businessName: form.businessName.trim(), meaning: form.meaning.trim(),
    unit: form.unit.trim() || undefined, currency: form.currency.trim() || undefined,
    enumSemantics: form.enumSemantics.trim() || undefined, metricDefinition: form.metricDefinition.trim() || undefined,
    applicability: form.applicability.trim() || undefined, evidence: form.evidence.trim(),
    unresolvedItems: form.unresolvedText.split('\n').map((item) => item.trim()).filter(Boolean),
    glossaryReviewIds: selectedGlossaryReviewIds.value.length ? [...selectedGlossaryReviewIds.value] : undefined,
  }
}

async function saveDraft() {
  if (!selectedColumn.value || !snapshotId.value || !canManage.value) return
  if (!form.businessName.trim() || !form.meaning.trim() || !form.evidence.trim()) {
    manualError.value = '请填写业务名称、字段含义和业务依据。'
    return
  }
  const requestId = ++actionRequest
  const columnId = selectedColumn.value.id
  const currentSnapshotId = snapshotId.value
  actionLoading.value = true
  manualError.value = ''
  try {
    const result = await saveFieldSemanticDraft(columnId, draftPayload())
    if (disposed || requestId !== actionRequest || selectedColumnId.value !== columnId || snapshotId.value !== currentSnapshotId) return
    workspace.value = result.data
    if (canViewDetails.value) await loadWorkspace(columnId)
    ElMessage.success('字段解释草稿已保存；保存不代表审核通过。')
  } catch (error) {
    manualError.value = errorMessage(error, '字段解释草稿保存失败。')
  } finally {
    if (!disposed && requestId === actionRequest) actionLoading.value = false
  }
}

async function mutateWorkspace(action: () => Promise<{ data: FieldSemanticWorkspace }>, success: string, keepForm = false) {
  if (!selectedColumn.value) return
  const columnId = selectedColumn.value.id
  const currentSnapshotId = snapshotId.value
  const requestId = ++actionRequest
  actionLoading.value = true
  manualError.value = ''
  try {
    const result = await action()
    if (disposed || requestId !== actionRequest || selectedColumnId.value !== columnId || snapshotId.value !== currentSnapshotId) return
    workspace.value = result.data
    if (canViewDetails.value) await loadWorkspace(columnId, keepForm)
    else if (!keepForm && result.data.draft) fillForm(result.data.draft)
    ElMessage.success(success)
  } catch (error) {
    manualError.value = errorMessage(error, '字段解释操作失败。')
  } finally {
    if (!disposed && requestId === actionRequest) actionLoading.value = false
  }
}

function currentAction(revisionId?: number, action?: FieldSemanticActionPayload['action'], reason?: string) {
  return { expectedVersion: workspace.value?.rowVersion || 0, revisionId, action, reason }
}

async function submitDraft() {
  if (!selectedColumn.value || !workspace.value?.workingRevisionId) return
  await mutateWorkspace(() => submitFieldSemanticDraft(selectedColumn.value!.id,
    currentAction(workspace.value?.workingRevisionId)), '字段解释已提交审核。')
}

async function withdrawDraft() {
  if (!selectedColumn.value || !workspace.value?.workingRevisionId) return
  await mutateWorkspace(() => withdrawFieldSemanticDraft(selectedColumn.value!.id,
    currentAction(workspace.value?.workingRevisionId)), '待审核修订已撤回，可以继续编辑。')
}

async function reviewDraft(action: 'APPROVE' | 'REJECT') {
  if (!selectedColumn.value || !workspace.value?.draft) return
  if (!reviewReason.value.trim()) {
    manualError.value = '审核通过或驳回都需要填写审核依据。'
    return
  }
  await mutateWorkspace(() => reviewFieldSemantic(selectedColumn.value!.id,
    currentAction(workspace.value?.draft?.id, action, reviewReason.value.trim())),
  action === 'APPROVE' ? '字段解释已审核通过并绑定当前来源。' : '字段解释已退回，已有有效解释保持不变。')
  reviewReason.value = ''
}

async function revoke() {
  if (!selectedColumn.value || !workspace.value || !revokeReason.value.trim()) return
  try {
    await ElMessageBox.confirm('撤销后，该解释会立即失去当前使用资格；不会自动回退到更早修订。', '撤销字段解释', { type: 'warning' })
  } catch { return }
  await mutateWorkspace(() => revokeFieldSemantic(selectedColumn.value!.id,
    currentAction(workspace.value?.currentApprovedRevisionId, undefined, revokeReason.value.trim())),
  '当前字段解释已撤销。')
  revokeReason.value = ''
}

async function confirmBinding() {
  if (!selectedColumn.value || !workspace.value || !bindingEvidence.value.trim()) return
  await mutateWorkspace(() => confirmFieldSemanticBinding(selectedColumn.value!.id, snapshotId.value!,
    workspace.value!.rowVersion, bindingEvidence.value.trim()), '当前快照来源已确认，并生成了新的审核证据。')
  bindingEvidence.value = ''
}

async function openBatchBindingDialog() {
  if (!snapshotId.value || !canManage.value) return
  batchDialogVisible.value = true
  batchLoading.value = true
  batchError.value = ''
  batchEvidence.value = ''
  batchCandidates.value = []
  batchSelectedIds.value = []
  batchResult.value = undefined
  const snapshot = snapshotId.value
  try {
    const result = await getPendingFieldSemanticBindings(snapshot)
    if (disposed || snapshotId.value !== snapshot) return
    batchCandidates.value = result.data || []
  } catch (error) {
    if (!disposed && snapshotId.value === snapshot) batchError.value = errorMessage(error, '待确认来源列表读取失败。')
  } finally {
    if (!disposed && snapshotId.value === snapshot) batchLoading.value = false
  }
}

async function confirmBatchBindings() {
  const snapshot = snapshotId.value
  if (!snapshot || !canManage.value || !batchEvidence.value.trim() || !batchSelectedIds.value.length) return
  const selected = batchCandidates.value.filter((candidate) => candidate.eligible && batchSelectedIds.value.includes(candidate.columnMetaId))
  if (!selected.length) return
  batchSaving.value = true
  batchError.value = ''
  try {
    const result = await confirmFieldSemanticBindings(snapshot, selected.map((candidate) => ({
      columnMetaId: candidate.columnMetaId,
      expectedVersion: candidate.expectedVersion,
      evidence: batchEvidence.value.trim(),
    })))
    if (disposed || snapshotId.value !== snapshot) return
    batchResult.value = result.data
    const failedIds = new Set(result.data.items.filter((item) => !item.success).map((item) => item.columnMetaId))
    const succeededIds = new Set(result.data.items.filter((item) => item.success).map((item) => item.columnMetaId))
    batchCandidates.value = batchCandidates.value.filter((candidate) => !succeededIds.has(candidate.columnMetaId))
    batchSelectedIds.value = [...failedIds]
    if (result.data.failedCount === 0) ElMessage.success(`已确认 ${result.data.successCount} 个字段来源。`)
    else ElMessage.warning(`已确认 ${result.data.successCount} 个，${result.data.failedCount} 个未完成，请查看逐项原因。`)
    if (selectedColumnId.value && selected.some((item) => item.columnMetaId === selectedColumnId.value)) {
      await loadWorkspace(selectedColumnId.value)
    }
  } catch (error) {
    if (!disposed && snapshotId.value === snapshot) batchError.value = errorMessage(error, '批量来源确认失败。')
  } finally {
    if (!disposed && snapshotId.value === snapshot) batchSaving.value = false
  }
}

async function generateCandidate() {
  if (!selectedColumn.value || !snapshotId.value || !workspace.value || !canManage.value) return
  const columnId = selectedColumn.value.id
  const snapshot = snapshotId.value
  const version = workspace.value.rowVersion
  const requestId = workspaceRequest
  candidateLoading.value = true
  candidateError.value = ''
  try {
    const result = await generateFieldSemanticCandidate(columnId, snapshot, version)
    if (disposed || requestId !== workspaceRequest || selectedColumnId.value !== columnId || snapshotId.value !== snapshot) return
    workspace.value = result.data
    if (canViewDetails.value) await loadWorkspace(columnId)
    ElMessage.success('AI 候选已保存为待人工采纳内容。')
  } catch (error) {
    if (!disposed && selectedColumnId.value === columnId && snapshotId.value === snapshot) {
      candidateError.value = errorMessage(error, 'AI 候选生成失败；你仍可手工填写并保存。')
    }
  } finally {
    candidateLoading.value = false
  }
}

async function executeSemanticCheck() {
  if (!snapshotId.value || !canRunSemanticCheck.value) return
  const snapshot = snapshotId.value
  semanticCheckLoading.value = true
  semanticCheckError.value = ''
  try {
    const result = await runSemanticCheck(snapshot)
    if (disposed || snapshotId.value !== snapshot) return
    latestCheck.value = result.data
    if (result.data.status !== 'SUCCESS') {
      semanticCheckError.value = result.data.errorSummary || '语义复查没有完整完成；当前覆盖状态不能视为通过。'
    } else {
      ElMessage.success(`语义复查完成：有效 ${result.data.validCount}，待补充 ${result.data.missingCount}，需复核 ${result.data.staleCount}。`)
    }
  } catch (error) {
    if (!disposed && snapshotId.value === snapshot) semanticCheckError.value = errorMessage(error, '语义复查失败。')
  } finally {
    semanticCheckLoading.value = false
  }
}

async function adoptCandidate(candidate: FieldSemanticCandidate) {
  if (!selectedColumn.value || !workspace.value) return
  await mutateWorkspace(() => adoptFieldSemanticCandidate(selectedColumn.value!.id, candidate.id,
    workspace.value!.rowVersion), 'AI 候选已采纳为草稿，仍需人工审核。')
}

watch([datasourceId, snapshotId], () => { void loadTables() })
watch(selectedColumnId, (value) => {
  workspaceRequest++
  if (value) void loadWorkspace(value)
  else { workspace.value = undefined; hasSearched.value = false }
})
onMounted(async () => {
  await Promise.all([context.initialize(), iamS1.load()])
  await loadTables()
})
onBeforeUnmount(() => {
  disposed = true
  listRequest++
  columnRequest++
  workspaceRequest++
  actionRequest++
})
</script>

<template>
  <section class="field-semantic-workspace">
    <header class="field-semantic-workspace__intro">
      <div><h2>业务解释</h2><p>平台解释独立于原始采集注释；AI 只提供候选，采纳后仍需审核。</p></div>
      <div class="field-semantic-workspace__intro-actions">
        <el-tag v-if="snapshotId" type="info">来源快照 #{{ snapshotId }}</el-tag>
        <el-button v-if="snapshotId && canManage" @click="openBatchBindingDialog">批量确认来源</el-button>
        <el-button v-if="snapshotId" :disabled="!canRunSemanticCheck" :loading="semanticCheckLoading" @click="executeSemanticCheck">复查当前快照</el-button>
      </div>
    </header>

    <el-dialog v-model="batchDialogVisible" title="批量确认字段来源" width="min(760px, 94vw)" top="5vh" :close-on-click-modal="!batchSaving">
      <p class="field-semantic-workspace__hint">仅勾选结构和依据与原审核来源一致的字段。每个字段仍会重新核验当前权限、版本及来源；下方依据会记录到每一条绑定审核证据中。</p>
      <ErrorState v-if="batchError" :message="batchError" />
      <LoadingState v-else-if="batchLoading" text="正在核验此快照的待确认绑定…" />
      <EmptyState v-else-if="!batchCandidates.length" message="当前快照没有待确认的字段来源绑定。" />
      <div v-else class="field-semantic-batch-list">
        <el-checkbox-group v-model="batchSelectedIds">
          <div v-for="candidate in batchCandidates" :key="candidate.columnMetaId" class="field-semantic-batch-list__item">
            <el-checkbox :label="candidate.columnMetaId" :disabled="!candidate.eligible">
              <strong>{{ candidate.tableName }}.{{ candidate.columnName }}</strong>
              <span v-if="candidate.businessName"> · {{ candidate.businessName }}</span>
              <small>版本 {{ candidate.expectedVersion }}</small>
            </el-checkbox>
            <p v-if="candidate.reason" class="field-semantic-workspace__hint">{{ candidate.reason }}</p>
          </div>
        </el-checkbox-group>
        <el-form label-position="top">
          <el-form-item label="本批逐项核验依据" required>
            <el-input v-model="batchEvidence" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="说明本批字段如何逐项核对结构和业务依据一致" />
          </el-form-item>
        </el-form>
      </div>
      <div v-if="batchResult" class="field-semantic-batch-result" aria-live="polite">
        <strong>已完成 {{ batchResult.successCount }} 项，未完成 {{ batchResult.failedCount }} 项</strong>
        <ul><li v-for="item in batchResult.items" :key="item.columnMetaId"><el-tag :type="item.success ? 'success' : 'danger'">{{ item.success ? '已确认' : '未完成' }}</el-tag><span>字段 #{{ item.columnMetaId }} · {{ item.message }}</span></li></ul>
      </div>
      <template #footer>
        <el-button @click="batchDialogVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="batchLoading || !batchSelectedIds.length || !batchEvidence.trim()" :loading="batchSaving" @click="confirmBatchBindings">确认所选来源</el-button>
      </template>
    </el-dialog>

    <ErrorState v-if="semanticCheckError" :message="semanticCheckError" />
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

    <EmptyState v-if="!datasourceId" message="请先在公共范围栏选择数据源。" />
    <EmptyState v-else-if="!snapshotId" message="请在公共范围栏明确选择快照后再整理字段解释。" />
    <EmptyState v-else-if="!canViewField" message="需要 governance:field:view 及当前数据源范围才能查看字段解释工作面。" />
    <template v-else>
      <ErrorState v-if="listError" :message="listError" @retry="loadTables" />
      <LoadingState v-else-if="listLoading" text="正在读取所选快照的字段目录…" />
      <EmptyState v-else-if="!tables.length" message="所选快照没有可查看的表结构。" />
      <div v-else class="field-semantic-workspace__selector">
        <el-form label-position="top" class="field-semantic-workspace__selector-grid">
          <el-form-item label="表" required><el-select :model-value="selectedTable" placeholder="选择表" filterable @change="loadColumns"><el-option v-for="table in tables" :key="table.tableName" :label="table.tableComment ? `${table.tableName} · ${table.tableComment}` : table.tableName" :value="table.tableName" /></el-select></el-form-item>
          <el-form-item label="字段" required>
            <el-select v-model="selectedColumnId" placeholder="选择字段" filterable :loading="columnsLoading" :disabled="!selectedTable || !columns.length">
              <el-option v-for="column in columns" :key="column.id" :label="`${column.columnName} · ${column.dataType}`" :value="column.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <p v-if="selectedTable && !columnsLoading && !columns.length" class="field-semantic-workspace__hint">当前筛选表没有字段。</p>
      </div>

      <ErrorState v-if="workspaceError" :message="workspaceError" @retry="loadWorkspace()" />
      <LoadingState v-else-if="workspaceLoading" text="正在核验字段来源、解释与审核状态…" />
      <EmptyState v-else-if="hasSearched && !workspace" message="当前筛选字段没有可读取的解释记录。" />
      <section v-else-if="workspace" class="field-semantic-workspace__content">
        <section class="field-semantic-card field-semantic-card--source">
          <div class="field-semantic-card__heading"><div><h3>{{ workspace.tableName }}.{{ workspace.columnName }}</h3><p>{{ workspace.dataType || '类型未记录' }} · 治理状态 {{ workspace.governanceStatus || '未知' }}</p></div><el-tag :type="isEffective ? 'success' : workspace.bindingStatus === 'VALID' ? 'warning' : 'info'">{{ isEffective ? '当前解释有效' : workspace.effectiveStatus === 'NEEDS_REVIEW' ? '来源需复核' : '尚无有效解释' }}</el-tag></div>
          <div class="field-semantic-card__source-notes"><div><span>原始采集注释</span><p>{{ workspace.rawColumnComment || '原始注释为空' }}</p></div><div><span>当前快照绑定</span><p>{{ workspace.bindingStatus }}<template v-if="workspace.bindingReason"> · {{ workspace.bindingReason }}</template></p></div></div>
        </section>

        <section class="field-semantic-card">
          <div class="field-semantic-card__heading"><div><h3>当前已审核解释</h3><p v-if="currentApproved">修订 v{{ currentApproved.revisionNo }} · {{ currentApproved.status }}</p></div><el-tag v-if="currentApproved" :type="isEffective ? 'success' : 'warning'">{{ isEffective ? '适用于当前来源' : '不能用于当前来源' }}</el-tag></div>
          <div v-if="currentApproved" class="field-semantic-usage-status" aria-label="问数收录状态">
            <strong>问数收录</strong>
            <span v-if="usageLoading">正在核对当前活动构建…</span>
            <ErrorState v-else-if="usageError" :message="usageError" />
            <span v-else-if="usageStatus">{{ usageStatus.message }}<template v-if="usageStatus.buildId"> · Build {{ usageStatus.buildId }}</template><template v-if="usageStatus.docId"> · 文档版本 v{{ usageStatus.versionNo }}</template></span>
            <span v-else>需要 knowledge:view 才能查看当前活动构建的收录状态。</span>
          </div>
          <template v-if="currentApproved">
            <h4>{{ currentApproved.businessName }}</h4><p>{{ currentApproved.meaning }}</p>
            <dl class="field-semantic-facts"><div v-if="currentApproved.unit"><dt>单位</dt><dd>{{ currentApproved.unit }}</dd></div><div v-if="currentApproved.currency"><dt>币种</dt><dd>{{ currentApproved.currency }}</dd></div><div v-if="currentApproved.enumSemantics"><dt>枚举含义</dt><dd>{{ currentApproved.enumSemantics }}</dd></div><div v-if="currentApproved.metricDefinition"><dt>指标口径</dt><dd>{{ currentApproved.metricDefinition }}</dd></div><div v-if="currentApproved.applicability"><dt>适用限制</dt><dd>{{ currentApproved.applicability }}</dd></div><div><dt>业务依据</dt><dd>{{ currentApproved.evidence }}</dd></div></dl>
            <p v-if="!isEffective" class="field-semantic-workspace__hint">当前快照未通过来源绑定及审核证据核验；这条历史解释不会自动用于当前问数。</p>
          </template>
          <EmptyState v-else message="当前字段尚无经过审核的业务解释。" />
          <div v-if="workspace.bindingStatus === 'PENDING_CONFIRMATION' && currentApproved && canManage" class="field-semantic-workspace__binding-confirm">
            <el-input v-model="bindingEvidence" aria-label="来源绑定核验依据" placeholder="记录该字段结构未变化的核验依据" />
            <el-button :disabled="!bindingEvidence.trim()" :loading="actionLoading" @click="confirmBinding">确认当前来源</el-button>
          </div>
          <el-input v-if="currentApproved && isEffective && canRevoke" v-model="revokeReason" aria-label="撤销原因" placeholder="填写撤销原因" class="field-semantic-workspace__reason" />
          <el-button v-if="currentApproved && isEffective && canRevoke" type="danger" plain :disabled="!revokeReason.trim()" :loading="actionLoading" @click="revoke">撤销当前解释</el-button>
        </section>

        <section class="field-semantic-card">
          <div class="field-semantic-card__heading"><div><h3>人工填写或 AI 候选</h3><p>业务名称、含义和依据必填。未确认内容保留为待确认项。</p></div><el-button :disabled="!canManage || candidateLoading || workspace.governanceStatus === 'BLOCKED' || workspace.governanceStatus === 'DEPRECATED'" :loading="candidateLoading" @click="generateCandidate">生成 AI 候选</el-button></div>
          <p v-if="!canManage" class="field-semantic-workspace__hint">保存、生成、采纳和提交需要 governance:field:manage 与 knowledge:manage 同时绑定当前数据源。</p>
          <ErrorState v-if="candidateError" :message="candidateError" />
          <article v-if="readyCandidate" class="field-semantic-candidate">
            <div class="field-semantic-candidate__heading"><strong>AI 建议 · 待人工采纳</strong><el-button type="primary" link :disabled="!canManage || candidateLoading" :loading="actionLoading" @click="adoptCandidate(readyCandidate)">采纳为草稿</el-button></div>
            <h4>{{ readyCandidate.businessName }}</h4><p>{{ readyCandidate.meaning }}</p><p v-if="readyCandidate.evidence"><strong>参考依据：</strong>{{ readyCandidate.evidence }}</p>
            <ul v-if="pendingQuestions.length"><li v-for="question in pendingQuestions" :key="question">待确认：{{ question }}</li></ul>
          </article>
          <p v-else-if="latestCandidate?.failureReason" class="field-semantic-workspace__hint">{{ latestCandidate.failureReason }}</p>

          <el-form label-position="top" class="field-semantic-form">
            <el-form-item label="业务名称" required><el-input v-model="form.businessName" maxlength="200" show-word-limit :disabled="!canManage" /></el-form-item>
            <el-form-item label="字段含义" required><el-input v-model="form.meaning" type="textarea" :rows="3" maxlength="4000" show-word-limit :disabled="!canManage" /></el-form-item>
            <div class="field-semantic-form__grid"><el-form-item label="单位"><el-input v-model="form.unit" maxlength="200" :disabled="!canManage" /></el-form-item><el-form-item label="币种"><el-input v-model="form.currency" maxlength="100" :disabled="!canManage" /></el-form-item></div>
            <el-form-item label="枚举含义"><el-input v-model="form.enumSemantics" type="textarea" :rows="2" maxlength="4000" :disabled="!canManage" placeholder="无法依据审核材料确认的值，请留空并列入待确认项" /></el-form-item>
            <el-form-item label="指标口径"><el-input v-model="form.metricDefinition" type="textarea" :rows="2" maxlength="4000" :disabled="!canManage" placeholder="不确定时不要推断计算公式" /></el-form-item>
            <el-form-item label="适用限制"><el-input v-model="form.applicability" type="textarea" :rows="2" maxlength="2000" :disabled="!canManage" /></el-form-item>
            <el-form-item label="业务依据" required><el-input v-model="form.evidence" type="textarea" :rows="2" maxlength="4000" :disabled="!canManage" placeholder="说明依据来自何处、如何核实" /></el-form-item>
            <el-form-item label="关联已审核术语（可选）">
              <el-select v-model="selectedGlossaryReviewIds" multiple clearable filterable :loading="glossaryOptionsLoading" :disabled="!canManage || (!canViewGlossary && !selectedGlossaryReviewIds.length)" placeholder="选择映射到当前字段的已审核术语">
                <el-option v-for="option in glossaryOptions" :key="option.reviewId" :label="`${option.name} · 审核 ${option.reviewId}`" :value="option.reviewId">
                  <span>{{ option.name }}</span><small class="field-semantic-glossary-option">{{ option.description || option.synonyms || '完整映射已核验' }}</small>
                </el-option>
              </el-select>
              <p v-if="glossaryOptionsError" class="field-semantic-workspace__hint">{{ glossaryOptionsError }}</p>
              <p v-else-if="!canViewGlossary" class="field-semantic-workspace__hint">引用或保留术语需要 glossary:view；清空选择可独立整理字段解释。</p>
            </el-form-item>
            <el-form-item label="待确认项"><el-input v-model="form.unresolvedText" type="textarea" :rows="2" :disabled="!canManage" placeholder="每行一个；未确认的单位、币种、枚举和口径继续保持待确认" /></el-form-item>
          </el-form>
          <ErrorState v-if="manualError" :message="manualError" />
          <div class="field-semantic-actions">
            <el-button type="primary" :disabled="!canManage || actionLoading" :loading="actionLoading" @click="saveDraft">保存草稿</el-button>
            <el-button v-if="(currentDraft?.status === 'DRAFT' || workspace.workingStatus === 'DRAFT') && workspace.workingRevisionId" :disabled="!canManage || actionLoading" :loading="actionLoading" @click="submitDraft">提交审核</el-button>
            <el-button v-if="(currentDraft?.status === 'PENDING_REVIEW' || workspace.workingStatus === 'PENDING_REVIEW') && workspace.workingRevisionId" :disabled="!canManage || actionLoading" :loading="actionLoading" @click="withdrawDraft">撤回待审核</el-button>
          </div>
          <div v-if="currentDraft?.status === 'PENDING_REVIEW'" class="field-semantic-review-box">
            <p>待审核修订 v{{ currentDraft.revisionNo }}。审核权限需要 governance:field:view 与 knowledge:approve 同时绑定当前数据源。</p>
            <el-input v-model="reviewReason" aria-label="字段解释审核依据" placeholder="填写本次审核或驳回依据" />
            <div><el-button type="success" :disabled="!canReview || !reviewReason.trim()" :loading="actionLoading" @click="reviewDraft('APPROVE')">审核通过</el-button><el-button type="danger" plain :disabled="!canReview || !reviewReason.trim()" :loading="actionLoading" @click="reviewDraft('REJECT')">驳回并退回</el-button></div>
          </div>
          <p v-if="canViewField && !canViewDetails" class="field-semantic-workspace__hint">查看草稿、AI 候选和审核明细还需要 knowledge:view。当前角色仍可按已授予的维护权限创建草稿。</p>
          <p v-if="canViewDetails && workspace.reviews?.length" class="field-semantic-workspace__history-title">审核与来源操作</p>
          <ol v-if="canViewDetails && workspace.reviews?.length" class="field-semantic-history">
            <li v-for="review in workspace.reviews" :key="review.id"><strong>{{ reviewActionLabel(review.action) }}</strong><span>{{ review.createdAt }} · 操作人 #{{ review.operatorId }}</span><p v-if="review.reason">{{ review.reason }}</p></li>
          </ol>
          <p v-if="workspace.revisions?.length" class="field-semantic-workspace__history-title">历史修订</p>
          <ol v-if="workspace.revisions?.length" class="field-semantic-history"><li v-for="revision in workspace.revisions" :key="revision.id"><strong>v{{ revision.revisionNo }} · {{ revision.status }}</strong><span>{{ revision.businessName }} · {{ revision.createdAt }}</span></li></ol>
        </section>
      </section>
    </template>
  </section>
</template>

<style scoped>
.field-semantic-workspace { display: grid; gap: 14px; min-width: 0; }
.field-semantic-workspace__intro,.field-semantic-card__heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.field-semantic-workspace__intro-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.field-semantic-check-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 16px; padding: 10px 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); color: var(--do-ink); background: var(--do-surface); font-size: 12px; }
.field-semantic-check-summary strong { font-weight: 600; }
.field-semantic-check-summary.is-failed { border-color: var(--do-danger); color: var(--do-danger); }
.field-semantic-workspace__intro h2,.field-semantic-card h3 { margin: 0; color: var(--do-ink); font-size: 16px; }
.field-semantic-workspace__intro p,.field-semantic-card__heading p,.field-semantic-card__heading + p,.field-semantic-workspace__hint { margin: 6px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
.field-semantic-workspace__selector,.field-semantic-card { min-width: 0; padding: 16px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.field-semantic-workspace__selector-grid,.field-semantic-form__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.field-semantic-workspace__selector :deep(.el-select) { width: 100%; }
.field-semantic-workspace__content { display: grid; gap: 14px; min-width: 0; }
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
