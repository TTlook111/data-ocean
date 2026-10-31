<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAdminContextStore } from '../../stores/adminContext'
import {
  listBusinessKnowledge,
  saveBusinessKnowledge,
  actBusinessKnowledge,
  governanceError,
  businessSourceStatus,
  type BusinessSourceItem,
  type BusinessSourceStatus,
  type BusinessKnowledge,
  type BusinessKnowledgeDraft,
} from '../../api/admin/governanceWorkflow'
import { listSnapshotTables, listSnapshotTableColumns } from '../../api/admin/governance'
import GovernanceHistoryDialog from './GovernanceHistoryDialog.vue'
import ExpandableText from '../common/ExpandableText.vue'
import { useGovernanceScope } from '../../composables/useGovernanceScope'
import { trackGovernanceWrite } from '../../composables/governanceWriteTracker'
import { trackKnowledgeWrite, pendingKnowledgeWrites } from '../../composables/knowledgeWriteTracker'
import { useAdminScrollLock } from '../../composables/useAdminScrollLock'
import GovernanceWriteNotice from './GovernanceWriteNotice.vue'
import { generateFromSnapshot } from '../../api/admin/knowledge'
const context = useAdminContextStore(),
  router = useRouter(),
  scope = useGovernanceScope()
const ready = scope.ready
const canView = computed(() => scope.can('knowledge:view'))
const canManage = computed(() => canView.value && scope.can('knowledge:manage'))
const canApprove = computed(() => canView.value && scope.can('knowledge:approve'))
const canReadCatalog = computed(() => scope.can('metadata:view'))
const actionTarget = ref<{ row: BusinessKnowledge; command: string; generation: number }>(),
  actionNote = ref(''),
  actionError = ref('')
const editorError = ref(''),
  catalogError = ref('')
let editorSession = 0,
  columnRequest = 0
const generationWrite = computed(() =>
  pendingKnowledgeWrites.value.some(
    (x) =>
      x.identity === scope.auth.token &&
      x.source === context.datasourceId &&
      x.snapshotId === context.snapshotId &&
      x.kind === 'generate-group',
  ),
)
const rows = ref<BusinessKnowledge[]>([]),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  dialog = ref(false)
const showHistory = ref(false)
const historicalCount = computed(
  () => rows.value.filter((row) => ['SUPERSEDED_DRAFT', 'SUPERSEDED'].includes(row.status)).length,
)
const visibleRows = computed(() =>
  showHistory.value
    ? rows.value
    : rows.value.filter((row) => !['SUPERSEDED_DRAFT', 'SUPERSEDED'].includes(row.status)),
)
const historyId = ref<number>()
const selectedFactId = ref<number>(), readerOpen = ref(false), readerRoot = ref<HTMLElement>(), workbenchRoot = ref<HTMLElement>()
const selectedFact = computed(() => rows.value.find(row => row.id === selectedFactId.value))
async function selectFact(row: BusinessKnowledge) {
  if (!canView.value || busy.value || !rows.value.some(item => item.id === row.id)) return
  selectedFactId.value = row.id; readerOpen.value = true; historyId.value = undefined
  await nextTick(); readerRoot.value?.focus()
}
async function returnToFacts() {
  readerOpen.value = false; historyId.value = undefined
  await nextTick(); workbenchRoot.value?.querySelector<HTMLButtonElement>(`[data-fact-id="${selectedFactId.value}"]`)?.focus()
}
function toggleFactHistory(event: Event) {
  const element = event.currentTarget as HTMLDetailsElement
  const id = Number(element.dataset.revisionId)
  if (!canView.value || !readerOpen.value || selectedFact.value?.id !== id) return
  historyId.value = element.open ? id : undefined
}
const savedEvidence = ref<BusinessKnowledge['evidenceConfirmation']>()
const sourceInfo = ref<BusinessSourceStatus>(),
  sourceLoading = ref(false),
  sourceError = ref(''),
  sourceConfirmed = ref(false)
const sourcePage = ref(1)
const options = ref<Array<{ value: string; label: string }>>([]),
  selectedTables = ref<string[]>([])
const form = reactive<BusinessKnowledgeDraft>({
  kind: 'DATASOURCE',
  knowledgeKey: '',
  title: '',
  body: {},
  dependencies: [],
  evidence: '',
  unresolvedItems: [],
})
const unresolved = ref('')
const kinds: Record<string, string> = {
  DATASOURCE: '数据源用途',
  TABLE: '表用途与粒度',
  RELATION: '表关系',
  METRIC: '指标口径',
}
const labels: Record<string, string> = {
  description: '业务说明',
  scope: '适用业务范围',
  grain: '一行数据代表什么',
  joinCondition: '完整关联条件',
  cardinality: '关系基数（如一对多）',
  aggregationNotes: '聚合与重复计数注意事项',
  formula: '计算公式',
  conditions: '必要筛选条件',
  limitations: '使用限制',
}
const bodyKeys = computed(() =>
  form.kind === 'DATASOURCE'
    ? ['description', 'scope', 'limitations']
    : form.kind === 'TABLE'
      ? ['description', 'grain', 'limitations']
      : form.kind === 'RELATION'
        ? ['description', 'joinCondition', 'cardinality', 'aggregationNotes', 'limitations']
        : ['description', 'formula', 'conditions', 'limitations'],
)
const statusLabels: Record<string, string> = {
  NOT_APPLICABLE: '已记录不适用',
  SUPERSEDED_DRAFT: '已由新草稿替代',
  DRAFT: '待人工补充',
  PENDING_REVIEW: '待审核',
  APPROVED: '待复查',
  VALID: '知识复查通过',
  REJECTED: '已退回',
  REVOKED: '已撤回',
  SUPERSEDED: '历史版本',
}
let epoch = 0,
  operation = 0,
  sourceRequest = 0,
  disposed = false
useAdminScrollLock(() => dialog.value || !!actionTarget.value)
async function load(reset = true) {
  const ticket = ++epoch,
    snapshot = context.snapshotId,
    g = scope.capture()
  rows.value = []
  error.value = ''
  loading.value = false
  if (reset) {
    dialog.value = false
    historyId.value = undefined
    sourceRequest++
    editorSession++
    sourceInfo.value = undefined
    sourceLoading.value = false
  }
  showHistory.value = false
  if (!canView.value || !snapshot) return
  loading.value = true
  try {
    const result = await listBusinessKnowledge(snapshot)
    if (!disposed && ticket === epoch && scope.isCurrent(g)) rows.value = result.data
  } catch {
    if (!disposed && ticket === epoch && scope.isCurrent(g)) error.value = '业务知识读取失败，请重试。'
  } finally {
    if (!disposed && ticket === epoch && scope.isCurrent(g)) loading.value = false
  }
}
async function open(row?: BusinessKnowledge) {
  if (!canView.value || !context.snapshotId || busy.value || (!row && !canManage.value)) return
  const session = ++editorSession,
    g = scope.capture()
  sourceRequest++
  columnRequest++
  sourceLoading.value = false
  form.expectedRevisionId = row?.id
  form.expectedVersion = row?.rowVersion
  form.confirmedSourceHash = undefined
  sourceInfo.value = undefined
  sourceError.value = ''
  sourceConfirmed.value = false
  editorError.value = ''
  catalogError.value = ''
  form.kind = row?.kind || 'DATASOURCE'
  form.knowledgeKey = row ? row.knowledgeKey.replace(/^[^:]+:/, '') : crypto.randomUUID()
  form.title = row?.title || ''
  savedEvidence.value = row?.evidenceConfirmation
  form.evidenceReference = row?.evidenceConfirmation?.reference || ''
  form.evidenceConfirmed = false
  form.body = { ...(row?.body || {}) }
  form.dependencies = [...(row?.dependencies || [])]
  form.evidence = row?.evidence || ''
  unresolved.value = (row?.unresolvedItems || []).join('\n')
  options.value = form.dependencies.map((value) => ({ value, label: value.replace(/^(table:|column:)/, '') }))
  selectedTables.value = form.dependencies.filter((d) => d.startsWith('table:')).map((d) => d.slice(6))
  dialog.value = true
  if (!canManage.value) return
  if (!canReadCatalog.value) {
    catalogError.value = '没有metadata:view，不能读取辅助表字段目录；原修订依赖保留，不补权。'
    return
  }
  const snapshot = context.snapshotId
  try {
    const result = await listSnapshotTables(snapshot)
    if (disposed || session !== editorSession || !scope.isCurrent(g) || !dialog.value) return
    options.value = [
      ...options.value,
      ...result.data
        .filter((t) => !['BLOCKED', 'DEPRECATED'].includes(t.governanceStatus || ''))
        .map((t) => ({ value: `table:${t.tableName}`, label: t.tableName })),
    ].filter((o, i, a) => a.findIndex((x) => x.value === o.value) === i)
    await loadColumnOptions()
  } catch {
    if (!disposed && session === editorSession && scope.isCurrent(g) && dialog.value)
      catalogError.value = '元数据范围读取失败，已有依赖保持不变，请重试。'
  }
}
async function checkSources() {
  if (!canView.value || !dialog.value || !form.expectedRevisionId) return
  const snapshot = context.snapshotId,
    id = form.expectedRevisionId,
    ticket = epoch,
    session = editorSession,
    g = scope.capture(),
    request = ++sourceRequest
  sourceLoading.value = true
  sourceError.value = ''
  try {
    const dependencies = new Set(form.dependencies)
    for (const dep of form.dependencies)
      if (dep.startsWith('column:')) dependencies.add(`table:${dep.slice(7).split('.')[0]}`)
    const result = await businessSourceStatus(id, [...dependencies])
    if (
      disposed ||
      ticket !== epoch ||
      request !== sourceRequest ||
      session !== editorSession ||
      !scope.isCurrent(g) ||
      snapshot !== context.snapshotId ||
      id !== form.expectedRevisionId ||
      !dialog.value
    )
      return
    sourceInfo.value = result.data
    sourceConfirmed.value = false
    sourcePage.value = 1
  } catch (error) {
    if (
      !disposed &&
      ticket === epoch &&
      session === editorSession &&
      scope.isCurrent(g) &&
      request === sourceRequest
    )
      sourceError.value = governanceError(error, '来源核对未完成，请重试')
  } finally {
    if (
      !disposed &&
      ticket === epoch &&
      session === editorSession &&
      scope.isCurrent(g) &&
      request === sourceRequest
    )
      sourceLoading.value = false
  }
}
function describeSource(item: BusinessSourceItem) {
  return item.reviewedSemantics?.qualified
    ? `${item.reviewedSemantics.body || ''}\n语义来源编号：${item.reviewedSemantics.sourceIds?.join(' / ') || '未记录'}`
    : `${item.dataType || '表结构'} · 原始说明：${item.comment || item.tableComment || '未填写'}${item.columnName ? '；目前没有可用的已审核字段解释' : ''}`
}
const allSourcePairs = computed(() => {
  const data = sourceInfo.value
  if (!data) return []
  return [...new Set([...data.original, ...data.current].map((item) => item.dependency))].map(
    (dependency) => ({
      dependency,
      before: data.original.find((item) => item.dependency === dependency),
      after: data.current.find((item) => item.dependency === dependency),
    }),
  )
})
const sourcePairs = computed(() =>
  allSourcePairs.value.slice((sourcePage.value - 1) * 10, sourcePage.value * 10),
)
async function loadColumnOptions() {
  if (!context.snapshotId || !dialog.value || !canReadCatalog.value) return
  const snapshot = context.snapshotId,
    session = editorSession,
    g = scope.capture(),
    request = ++columnRequest,
    names = [...selectedTables.value]
  try {
    const responses = await Promise.all(names.map((name) => listSnapshotTableColumns(snapshot, name)))
    if (
      disposed ||
      session !== editorSession ||
      request !== columnRequest ||
      !scope.isCurrent(g) ||
      !dialog.value ||
      names.join() !== selectedTables.value.join()
    )
      return
    const retained = options.value.filter(
      (o) => o.value.startsWith('table:') || form.dependencies.includes(o.value),
    )
    options.value = [
      ...retained,
      ...responses.flatMap((r, i) =>
        r.data
          .filter((c) => !['BLOCKED', 'DEPRECATED'].includes(c.governanceStatus || ''))
          .map((c) => ({
            value: `column:${names[i]}.${c.columnName}`,
            label: `${names[i]}.${c.columnName}`,
          })),
      ),
    ].filter((o, i, a) => a.findIndex((x) => x.value === o.value) === i)
  } catch {
    if (!disposed && session === editorSession && scope.isCurrent(g) && request === columnRequest)
      catalogError.value = '字段依赖读取失败，原依赖保留，请重试。'
  }
}
function draft() {
  const deps = new Set(form.dependencies)
  for (const dep of form.dependencies)
    if (dep.startsWith('column:')) deps.add(`table:${dep.slice(7).split('.')[0]}`)
  return {
    ...form,
    body: Object.fromEntries(bodyKeys.value.map((k) => [k, form.body[k] || ''])),
    dependencies: [...deps],
    unresolvedItems: unresolved.value
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean),
    confirmedSourceHash: sourceConfirmed.value ? sourceInfo.value?.currentHash : undefined,
  }
}
async function save(ai = false) {
  if (
    busy.value ||
    !canManage.value ||
    !dialog.value ||
    !context.snapshotId ||
    !form.title.trim() ||
    !form.dependencies.length
  )
    return
  const snapshot = context.snapshotId,
    g = scope.capture(),
    session = editorSession,
    action = ++operation,
    payload = JSON.parse(JSON.stringify(draft())) as BusinessKnowledgeDraft,
    signature = JSON.stringify(draft())
  const current = () =>
    !disposed &&
    scope.isCurrent(g) &&
    session === editorSession &&
    action === operation &&
    dialog.value &&
    canManage.value
  busy.value = true
  editorError.value = ''
  try {
    await trackGovernanceWrite(
      scope.auth.token,
      [`business-knowledge:${snapshot}:${payload.kind}:${payload.knowledgeKey}`],
      `${ai ? 'AI候选' : '保存业务知识'} · ${payload.title} / 快照 #${snapshot}`,
      () => saveBusinessKnowledge(snapshot, payload, ai),
    )
    if (!current()) return
    if (signature !== JSON.stringify(draft())) {
      editorError.value = '原输入已保存为新修订，等待期间的新输入仍保留；请核对最新修订后继续，不能自动覆盖。'
      await load(false)
      return
    }
    ElMessage.success(ai ? 'AI 候选已保存，请编辑并人工确认' : '新修订已保存，仍需审核与复查')
    dialog.value = false
    await load()
  } catch (cause) {
    if (current())
      editorError.value = governanceError(
        cause,
        '保存未确认完成，原稿保留；请核对来源、内容与权限，勿重复提交',
      )
  } finally {
    if (!disposed && action === operation) busy.value = false
  }
}
function openDocuments() {
  if (!canView.value) return
  router.push({
    path: '/admin/semantics/knowledge',
    query: { datasourceId: context.datasourceId, snapshotId: context.snapshotId },
  })
}
async function generateDocuments() {
  if (busy.value || !canManage.value || !context.snapshotId || !context.datasourceId) return
  const snapshot = context.snapshotId,
    ds = context.datasourceId,
    g = scope.capture(),
    action = ++operation
  busy.value = true
  error.value = ''
  try {
    const result = await trackKnowledgeWrite(
      {
        identity: scope.auth.token,
        source: ds,
        snapshotId: snapshot,
        kind: 'generate-group',
        started: Date.now(),
      },
      () => generateFromSnapshot(ds, snapshot),
    )
    if (disposed || !scope.isCurrent(g) || action !== operation) return
    ElMessage.success(
      `已生成 ${result.data?.length ?? '未知数量'} 篇主题草稿，仍需审核发布和单独确认 RAG 构建`,
    )
    openDocuments()
  } catch (cause) {
    if (!disposed && scope.isCurrent(g) && action === operation)
      error.value = governanceError(
        cause,
        cause instanceof Error ? cause.message : '文档生成未确认完成，请检查来源和权限',
      )
  } finally {
    if (!disposed && action === operation) busy.value = false
  }
}
function permitted(command: string) {
  return ['APPROVE', 'REJECT'].includes(command)
    ? canApprove.value
    : command === 'REVOKE'
      ? canManage.value && canApprove.value
      : canManage.value
}
async function action(row: BusinessKnowledge, command: string) {
  if (busy.value || !permitted(command) || !rows.value.some((x) => x.id === row.id)) return
  const target = {
    row: JSON.parse(JSON.stringify(row)) as BusinessKnowledge,
    command,
    generation: scope.capture(),
  }
  if (['APPROVE', 'REJECT', 'REVOKE', 'NOT_APPLICABLE'].includes(command)) {
    actionTarget.value = target
    actionNote.value = ''
    actionError.value = ''
    return
  }
  await performAction(target, '')
}
async function confirmAction() {
  const target = actionTarget.value
  if (!target || !actionNote.value.trim()) return
  await performAction(target, actionNote.value.trim())
}
async function performAction(
  target: { row: BusinessKnowledge; command: string; generation: number },
  note: string,
) {
  const { row, command, generation: g } = target
  if (busy.value || !scope.isCurrent(g) || !permitted(command)) return
  const op = ++operation
  busy.value = true
  error.value = ''
  actionError.value = ''
  const current = () => !disposed && scope.isCurrent(g) && op === operation && permitted(command)
  try {
    await trackGovernanceWrite(
      scope.auth.token,
      [`business-knowledge:${context.snapshotId}:${row.kind}:${row.knowledgeKey.replace(/^[^:]+:/, '')}`],
      `${command} · 业务知识 #${row.id}`,
      () => actBusinessKnowledge(row.id, row.rowVersion, command, note),
    )
    if (current()) {
      actionTarget.value = undefined
      ElMessage.success(command === 'RECHECK' ? '知识已复查；文档发布和 RAG 生效仍需分别核对' : '操作成功')
      await load()
    }
  } catch (cause) {
    if (current()) {
      const message = governanceError(cause, '操作未确认完成，可能存在来源变化或并发修订；请核对原目标')
      if (actionTarget.value === target) actionError.value = message
      else error.value = message
    }
  } finally {
    if (!disposed && op === operation) busy.value = false
  }
}
watch(
  () => form.dependencies.join('\n'),
  () => {
    sourceRequest++
    sourceInfo.value = undefined
    sourceConfirmed.value = false
    sourceLoading.value = false
  },
  { flush: 'sync' },
)
watch(
  dialog,
  (open) => {
    if (!open) {
      editorSession++
      sourceRequest++
      columnRequest++
      sourceLoading.value = false
    }
  },
  { flush: 'sync' },
)
watch(
  scope.generation,
  () => {
    selectedFactId.value = undefined; readerOpen.value = false; historyId.value = undefined
    operation++
    busy.value = false
    actionTarget.value = undefined
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  epoch++
  operation++
  editorSession++
  sourceRequest++
  columnRequest++
})
</script>
<template>
  <section ref="workbenchRoot" class="business-workbench">
    <GovernanceWriteNotice />
    <p v-if="scope.scopeIssue.value" role="status">{{ scope.scopeIssue.value }}</p>
    <p v-if="generationWrite" role="status">当前源/快照有执行中或结果未知的整组生成，请先到文档工作区核对；不能重复生成。</p>
    <header class="business-heading">
      <div><h3>{{ readerOpen ? '业务事实详情' : '当前业务事实' }}</h3><p>先核对事实、阶段与依据，再决定是否修订。</p></div>
      <div class="business-workspace-actions">
        <el-button :disabled="!canView || busy" @click="load">刷新</el-button>
        <el-button v-if="!readerOpen" type="primary" :disabled="!canManage || busy" @click="open()">补充业务知识</el-button>
        <details class="business-more"><summary>文档与工作区操作</summary><div><el-button :disabled="!canView || busy" @click="openDocuments">文档与构建</el-button><el-button :disabled="!canManage || busy || generationWrite" @click="generateDocuments">生成主题文档</el-button></div></details>
      </div>
    </header>
    <p v-if="!canManage && canView" class="business-permission-note">当前可查看；维护、生成与提交需要当前数据源的 knowledge:manage。审核与维护能力分别核验。</p>
    <p v-if="!ready">请先选择数据源与快照。</p>
    <p v-else-if="!canView">没有当前范围的知识查看权限。</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <p v-else-if="loading" role="status">正在读取当前来源的业务事实…</p>
    <section v-else-if="readerOpen && selectedFact" ref="readerRoot" tabindex="-1" class="business-fact-reader" data-testid="business-fact-reader">
      <button class="business-back" type="button" @click="returnToFacts">← 返回事实列表</button>
      <header class="business-reader-heading"><div><p>{{ kinds[selectedFact.kind] || selectedFact.kind }} · 修订 #{{ selectedFact.id }}</p><h2>{{ selectedFact.title }}</h2></div><el-tag :type="selectedFact.status==='REVOKED' ? 'danger' : selectedFact.status==='VALID' && selectedFact.effectiveStatus!=='NEEDS_REVIEW' ? 'success' : 'info'">{{ selectedFact.effectiveStatus==='NEEDS_REVIEW' ? '来源需复核' : statusLabels[selectedFact.status] || selectedFact.status }}</el-tag></header>
      <p class="business-pending-count">{{ Array.isArray(selectedFact.unresolvedItems) ? selectedFact.unresolvedItems.length+' 项待确认' : '待确认数未提供' }} · 待确认数不代表发布或索引完成。</p>
      <p v-if="selectedFact.status==='REVOKED'" role="status" class="business-withdrawn-note">此事实已撤回，不能作为当前有效业务知识。待确认0也不代表有效。</p>
      <p v-if="selectedFact.effectiveStatus==='NEEDS_REVIEW'" role="alert">当前来源需要复核，不能把旧修订自动视为适用于当前来源。</p>
      <dl class="business-reading-facts">
        <div v-for="(value,key) in selectedFact.body || {}" :key="key"><dt>{{ labels[key] || key }}</dt><dd><ExpandableText :text="value" /></dd></div>
        <div><dt>作用对象与依赖</dt><dd><span v-if="selectedFact.dependencies?.length">{{ selectedFact.dependencies.map(value=>value.replace(/^(table:|column:)/,'')).join(' · ') }}</span><span v-else>本修订未返回依赖对象。</span></dd></div>
        <div><dt>原始依据</dt><dd><ExpandableText :text="selectedFact.evidence" /></dd></div>
        <div v-if="selectedFact.evidenceConfirmation?.reference"><dt>资料引用</dt><dd><ExpandableText :text="selectedFact.evidenceConfirmation.reference" /></dd></div>
      </dl>
      <ul v-if="selectedFact.unresolvedItems?.length" class="business-unresolved"><li v-for="(item,index) in selectedFact.unresolvedItems" :key="index">{{ item }}</li></ul>
      <details :key="selectedFact.id" :data-revision-id="selectedFact.id" class="business-inline-history" :open="historyId===selectedFact.id" @toggle="toggleFactHistory">
        <summary>审核、复查与修订记录</summary><GovernanceHistoryDialog v-if="historyId===selectedFact.id" :id="selectedFact.id" kind="BUSINESS" embedded @close="historyId=undefined" />
      </details>
      <footer class="business-reader-actions">
        <div><p v-if="selectedFact.status==='VALID' && selectedFact.effectiveStatus!=='NEEDS_REVIEW'">当前事实复查通过，无需重复审核。需要调整内容时开始新修订。</p><p v-else-if="selectedFact.status==='APPROVED'">人工审核通过，下一步复查同一修订。</p><p v-else-if="selectedFact.status==='PENDING_REVIEW'">核对原始依据后审核；审核权不等于维护或发布权。</p><p v-else>核对当前阶段与来源依据，再决定是否修订或提交。</p></div>
        <div class="business-object-actions">
          <el-button :disabled="!canManage || busy" @click="open(selectedFact)">开始新修订</el-button>
          <el-button v-if="selectedFact.status==='DRAFT'" :disabled="!canManage || busy" @click="action(selectedFact,'SUBMIT')">提交审核</el-button>
          <el-button v-if="['DRAFT','REJECTED'].includes(selectedFact.status)" :disabled="!canManage || busy" @click="action(selectedFact,'NOT_APPLICABLE')">标记不适用</el-button>
          <template v-if="selectedFact.status==='PENDING_REVIEW'"><el-button :disabled="!canApprove || busy" @click="action(selectedFact,'APPROVE')">审核通过</el-button><el-button :disabled="!canApprove || busy" @click="action(selectedFact,'REJECT')">退回修订</el-button></template>
          <el-button v-if="selectedFact.status==='APPROVED'" type="primary" :disabled="!canManage || busy" @click="action(selectedFact,'RECHECK')">系统复查</el-button>
          <details v-if="selectedFact.status==='VALID'" class="business-more"><summary>维护操作</summary><div><el-button type="danger" :disabled="!canManage || !canApprove || busy" @click="action(selectedFact,'REVOKE')">撤回</el-button></div></details>
        </div>
      </footer>
      <p v-if="selectedFact.status==='PENDING_REVIEW' && !canApprove" class="business-permission-note">当前数据源没有 knowledge:approve；可阅读依据，不能审核此修订。</p>
      <p v-if="selectedFact.status==='VALID' && (!canManage || !canApprove)" class="business-permission-note">撤回有效事实需要当前源的 knowledge:manage 与 knowledge:approve 同时成立。</p>
    </section>
    <template v-else>
      <el-checkbox v-if="historicalCount" v-model="showHistory">显示历史修订（{{ historicalCount }}）</el-checkbox>
      <p v-if="!visibleRows.length">尚无业务知识，请补充已有资料，或让 AI 整理首轮候选。</p>
      <div v-else class="business-fact-list" aria-label="业务事实列表">
        <button v-for="row in visibleRows" :key="row.id" type="button" :data-fact-id="row.id" :class="{'last-selected':selectedFactId===row.id}" @click="selectFact(row)">
          <div class="business-list-title"><strong :title="row.title">{{ row.title }}</strong><span>{{ kinds[row.kind] || row.kind || '类型未提供' }}</span></div>
          <div class="business-list-state"><span :class="{'is-revoked':row.status==='REVOKED'}">{{ row.effectiveStatus==='NEEDS_REVIEW' ? '来源需复核' : statusLabels[row.status] || row.status }}</span><small>{{ Array.isArray(row.unresolvedItems) ? row.unresolvedItems.length+' 项待确认' : '待确认数未提供' }}</small></div><span class="business-list-open">查看详情 →</span>
        </button>
      </div>
    </template>
    <details class="business-contract"><summary>业务事实如何进入知识文档</summary><p>对象事实 → 人工审核 → 同修订复查 → 主题文档。文档审核、发布与RAG构建仍是独立操作。</p></details>
    <el-dialog
      v-model="dialog"
      :show-close="!busy"
      :close-on-press-escape="!busy"
      title="业务知识修订"
      width="min(760px,94vw)"
      :close-on-click-modal="false"
    >
      <p v-if="editorError" role="alert">{{ editorError }}</p>
      <p v-if="catalogError" role="status">{{ catalogError }}</p>
      <p v-if="savedEvidence" class="business-saved-evidence">
        已保存的资料确认：{{ savedEvidence.reference || '未记录引用' }} · 确认人编号
        {{ savedEvidence.confirmedBy ?? '未提供' }}。下方勾选仅用于本次新修订。
      </p>
      <el-form label-position="top" :disabled="busy || !canManage">
        <div class="business-grid">
          <el-form-item label="知识类型">
            <el-select v-model="form.kind" :disabled="!!form.expectedRevisionId">
              <el-option v-for="(label, key) in kinds" :key="key" :value="key" :label="label" />
            </el-select>
          </el-form-item>
          <el-form-item label="主题"><el-input v-model="form.title" maxlength="200" /></el-form-item>
        </div>
        <el-form-item label="关联表">
          <el-select
            :disabled="!canReadCatalog"
            v-model="selectedTables"
            multiple
            filterable
            @change="loadColumnOptions"
          >
            <el-option
              v-for="o in options.filter((o) => o.value.startsWith('table:'))"
              :key="o.value"
              :label="o.label"
              :value="o.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="知识依赖的表与字段">
          <el-select v-model="form.dependencies" multiple filterable>
            <el-option v-for="o in options" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-for="key in bodyKeys" :key="key" :label="labels[key]">
          <el-input v-model="form.body[key]" type="textarea" :rows="2" maxlength="16000" />
        </el-form-item>
        <el-form-item label="依据引用（资料名称、版本及章节，或平台记录位置）">
          <el-input
            v-model="form.evidenceReference"
            maxlength="2000"
            placeholder="例如：订单业务手册 v2，第 3.1 节"
          />
        </el-form-item>
        <el-form-item label="原始资料摘录与人工确认说明">
          <el-input v-model="form.evidence" type="textarea" :rows="3" maxlength="8000" />
        </el-form-item>
        <el-checkbox v-if="canManage" v-model="form.evidenceConfirmed">
          我已核对上述原始资料，并确认其支持当前修订；AI 推断不能替代依据
        </el-checkbox>
        <p v-if="['RELATION', 'METRIC'].includes(form.kind)">
          公式与关联条件请使用真实表名.字段名；首期不支持别名、子查询或窗口，业务解释填写在使用限制中。
        </p>
        <el-form-item label="尚未确认的问题（每行一项）">
          <el-input
            v-model="unresolved"
            type="textarea"
            :rows="2"
            placeholder="有疑问时保留，明确后再提交审核"
          />
        </el-form-item>
      </el-form>
      <section v-if="form.expectedRevisionId" class="business-source-review">
        <el-button :loading="sourceLoading" @click="checkSources">核对已保存修订的来源</el-button>
        <p v-if="sourceError" role="alert">{{ sourceError }}</p>
        <template v-if="sourceInfo">
          <p>
            {{
              sourceInfo.sourceChanged
                ? '依赖字段、审核或结构来源已经变化，请核对业务口径。'
                : '依赖来源与本修订保存时一致。'
            }}
          </p>
          <p v-if="sourceInfo.reason" role="alert">{{ sourceInfo.reason }}</p>
          <el-collapse>
            <el-collapse-item
              v-for="pair in sourcePairs"
              :key="pair.dependency"
              :title="pair.dependency.replace(/^(table:|column:)/, '')"
              :name="pair.dependency"
            >
              <div class="business-grid">
                <div>
                  <strong>保存时的依据</strong>
                  <p class="business-source-text">
                    {{ pair.before ? describeSource(pair.before) : '本次新增的依赖。' }}
                  </p>
                </div>
                <div>
                  <strong>当前依据</strong>
                  <p class="business-source-text">
                    {{
                      pair.after
                        ? describeSource(pair.after)
                        : sourceInfo.reason
                          ? '当前来源无法完整核验，请先处理上方原因。'
                          : '本次不再纳入此依赖。'
                    }}
                  </p>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
          <el-pagination
            v-if="allSourcePairs.length > 10"
            v-model:current-page="sourcePage"
            :total="allSourcePairs.length"
            :page-size="10"
            layout="prev,pager,next,total"
          />
          <el-checkbox
            v-if="sourceInfo.sourceChanged && sourceInfo.currentHash && canManage"
            v-model="sourceConfirmed"
          >
            已核对上述来源变化，并相应确认或修订业务口径
          </el-checkbox>
        </template>
      </section>
      <template #footer>
        <el-button :disabled="busy" @click="dialog = false">取消修订</el-button>
        <template v-if="canManage">
          <el-button
            :loading="busy"
            :disabled="!form.title.trim() || !form.dependencies.length"
            @click="save(true)"
          >
            AI 整理候选
          </el-button>
          <el-button
            type="primary"
            :loading="busy"
            :disabled="!form.title.trim() || !form.dependencies.length"
            @click="save(false)"
          >
            保存新修订
          </el-button>
        </template>
      </template>
    </el-dialog>
    <el-dialog
      :model-value="!!actionTarget"
      title="核对业务知识处理依据"
      width="min(620px,94vw)"
      :show-close="!busy"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      @update:model-value="actionTarget = undefined"
    >
      <p>
        目标：{{ actionTarget?.row.title }} · 修订 #{{ actionTarget?.row.id }} / 版本
        {{ actionTarget?.row.rowVersion }}
      </p>
      <p>审核、同修订复查和文档发布分别留存依据；此动作不会自动构建RAG。</p>
      <p v-if="actionError" role="alert">{{ actionError }}</p>
      <el-input
        v-model="actionNote"
        :disabled="busy"
        type="textarea"
        :rows="4"
        aria-label="业务处理依据"
        placeholder="资料引用、业务核对结论或退回原因"
      />
      <template #footer>
        <el-button :disabled="busy" @click="actionTarget = undefined">取消</el-button>
        <el-button type="primary" :loading="busy" :disabled="!actionNote.trim()" @click="confirmAction">
          确认处理
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
<style scoped>
.business-contract {
  padding: 16px 20px;
  background: var(--do-primary-soft);
  border-left: 3px solid var(--do-primary);
  border-radius: 8px;
  font-size: 13px;
}
.business-contract p {
  margin: 6px 0 0;
  color: var(--do-muted);
  line-height: 1.7;
}
.business-workbench {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: 14px;
  background: var(--do-surface);
  display: grid;
  gap: 16px;
  min-width: 0;
}
.business-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.business-heading h3 {
  margin: 0;
}
.business-heading p {
  font-size: 13px;
  color: var(--do-ink-muted);
}
.business-grid {
  display: grid;
  grid-template-columns: 1fr 2fr;
  gap: 16px;
}
.business-workbench :deep(.el-select) {
  width: 100%;
}
@media (max-width: 640px) {
  .business-grid {
    grid-template-columns: 1fr;
  }
}
.business-source-review {
  border-top: 1px solid var(--do-line);
  padding-top: 12px;
}
.business-source-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
}

.business-workbench{padding:0;border:0;border-radius:0;background:transparent;}
.business-heading{align-items:start;}.business-heading p{margin:7px 0 0;color:var(--do-muted);font-size:12px;}
.business-workspace-actions{display:flex;gap:8px;align-items:start;flex-wrap:wrap;}
.business-more{font-size:12px;position:relative;}.business-more summary{cursor:pointer;color:var(--do-muted);padding:8px 5px;min-height:32px;}
.business-more>div{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px;}
.business-permission-note{font-size:12px;color:var(--do-muted);margin:0;line-height:1.7;}
.business-fact-list{border:1px solid var(--do-line);border-radius:7px;overflow:hidden;background:var(--do-surface);}
.business-fact-list>button{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(170px,1fr) auto;gap:20px;align-items:center;width:100%;text-align:left;border:0;border-bottom:1px solid var(--do-line);background:transparent;padding:18px 22px;cursor:pointer;color:var(--do-ink);font:inherit;}
.business-fact-list>button:last-child{border-bottom:0;}.business-fact-list>button:hover,.business-fact-list>button.last-selected{background:var(--do-soft);}
.business-fact-list>button:focus-visible,.business-back:focus-visible{outline:2px solid var(--do-primary);outline-offset:-3px;}
.business-list-title,.business-list-state{display:grid;gap:6px;min-width:0;}.business-list-title strong{font-size:14px;overflow-wrap:anywhere;}.business-list-title span,.business-list-state small{font-size:12px;color:var(--do-muted);}.business-list-state>span{font-size:13px;}.is-revoked{color:var(--do-danger);font-weight:600;}.business-list-open{font-size:12px;color:var(--do-primary-strong);}
.business-fact-reader{padding:24px 26px;border:1px solid var(--do-line);border-radius:8px;background:var(--do-surface);min-width:0;}.business-fact-reader:focus{outline:none;}.business-fact-reader:focus-visible{outline:2px solid var(--do-primary);}
.business-back{border:1px solid var(--do-line);border-radius:5px;padding:7px 10px;background:var(--do-surface);font:inherit;font-size:12px;color:var(--do-muted);cursor:pointer;margin-bottom:18px;}
.business-reader-heading{display:flex;justify-content:space-between;align-items:start;gap:16px;}.business-reader-heading h2{font-size:23px;margin:4px 0 0;overflow-wrap:anywhere;}.business-reader-heading p,.business-pending-count{color:var(--do-muted);font-size:12px;margin:0;line-height:1.7;}
.business-pending-count{margin:12px 0 22px;}.business-withdrawn-note{font-size:13px;color:var(--do-danger);border-left:3px solid var(--do-danger);padding:10px 14px;background:var(--do-soft);}
.business-reading-facts{display:grid;gap:19px;margin:0 0 23px;}.business-reading-facts>div{display:grid;grid-template-columns:130px minmax(0,1fr);gap:14px;}.business-reading-facts dt{color:var(--do-muted);font-size:12px;}.business-reading-facts dd{margin:0;min-width:0;font-size:14px;line-height:1.85;overflow-wrap:anywhere;}
.business-inline-history{padding:17px 0;border-top:1px solid var(--do-line);}.business-inline-history summary{cursor:pointer;min-height:28px;font-size:13px;color:var(--do-muted);}
.business-reader-actions{border-top:1px solid var(--do-line);padding-top:18px;display:flex;align-items:start;justify-content:space-between;gap:18px;}.business-reader-actions p{font-size:12px;color:var(--do-muted);line-height:1.7;margin:0;}.business-object-actions{display:flex;flex-wrap:wrap;gap:8px;justify-content:flex-end;}.business-object-actions>.el-button{margin:0;}
.business-contract{border-top:1px solid var(--do-line);padding-top:13px;font-size:12px;color:var(--do-muted);}.business-contract summary{cursor:pointer;min-height:28px;}
@media(max-width:760px){.business-heading{display:grid;grid-template-columns:1fr;}.business-workspace-actions{display:grid;grid-template-columns:1fr 1fr;width:100%;}.business-workspace-actions>.el-button{width:100%;min-height:44px;margin:0;}.business-workspace-actions>.business-more{grid-column:1/-1;}.business-fact-list>button{grid-template-columns:minmax(0,1fr) auto;gap:12px;padding:16px;}.business-list-title{grid-column:1/-1;}.business-list-state{gap:5px;}.business-list-open{align-self:end;}.business-fact-reader{padding:17px;}.business-reader-heading{flex-wrap:wrap;}.business-reader-heading h2{font-size:20px;}.business-reading-facts>div{grid-template-columns:minmax(0,1fr);gap:4px;}.business-reader-actions{display:grid;grid-template-columns:1fr;}.business-object-actions{justify-content:start;width:100%;}.business-object-actions>.el-button{min-height:44px;flex:1;}.business-back{min-height:44px;}}
</style>
