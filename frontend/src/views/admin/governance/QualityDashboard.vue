<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, ArrowRight, ClipboardCheck, RefreshCw } from 'lucide-vue-next'
import GovernanceOverviewCharts from '../../../components/admin/GovernanceOverviewCharts.vue'
import {
  overviewSummary,
  groupOverviewTables,
  loadOverviewIssues,
  overviewStatuses,
  overviewStatusNames,
  type OverviewStatus,
} from '../../../utils/governanceOverview'
import { getHttpErrorMessage } from '../../../utils/httpError'
import LoadingState from '../../../components/common/LoadingState.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import RuleApplicabilitySummary from '../../../components/admin/RuleApplicabilitySummary.vue'
import TitleHelp from '../../../components/admin/TitleHelp.vue'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import {
  listQualityIssues,
  listSnapshotTables,
  type QualityIssueItem,
  getLatestQualityCheckBatch,
  getQualityCheckBatch,
  getQualityRuleSummary,
  getSnapshotQualityIssueSummary,
  type QualityCheckBatch,
  type QualityIssueSummary,
  type QualityRuleSummary,
} from '../../../api/admin/governance'
import { qualityDimensionLabel, snapshotStatusLabel } from '../../../utils/enumLabels'
import { useQualityBusinessScope } from '../../../composables/useQualityBusinessScope'
import { useQualityCheckOperations } from './useQualityCheckOperations'
import { useAuthStore } from '../../../stores/auth'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import { buildContextQuery } from '../../../utils/adminNavigation'
import { positiveId } from '../../../utils/lineageContext'

const context = useAdminContextStore()
const scope = useQualityBusinessScope(),
  auth = useAuthStore()
let initTicket = 0
const iamS1 = useIamS1Store()
const route = useRoute()
const router = useRouter()

// URL 中的批次固定执行证据，问题统计和规则规划仍分别读取当前快照的事实。
const selectedBatchId = computed(() => positiveId(route.query.batchId))
const explicitBatchRequested = computed(() => route.query.batchId !== undefined)
const latestBatch = ref<QualityCheckBatch | null>(null)
const batchHeading = computed(() =>
  explicitBatchRequested.value ? `指定批次 #${selectedBatchId.value ?? '无效'}` : '最近一次检查',
)
async function showLatestBatch() {
  const query = { ...route.query }
  delete query.batchId
  await router.replace({ query })
}
const coverageOpen = ref(false)
const statusHelpOpen = ref(false)
interface PlotFilter {
  status?: OverviewStatus
  tableName?: string
  issueDomain?: 'SOURCE' | 'SEMANTIC'
}
const plotChoice = ref<{ filter: PlotFilter; source: number; snapshot: number; ticket: number } | null>(null)
useAdminScrollLock(() => coverageOpen.value || statusHelpOpen.value || !!plotChoice.value)
const issueSummary = ref<QualityIssueSummary | null>(null)
const ruleSummary = ref<QualityRuleSummary | null>(null)
const aggregateRows = ref<QualityIssueItem[]>([]),
  aggregateComplete = ref(false),
  aggregateLoading = ref(false),
  aggregateMessage = ref('')
const summaryModel = computed(() => overviewSummary(issueSummary.value))
const tableCount = computed(() =>
  aggregateComplete.value
    ? groupOverviewTables(aggregateRows.value).filter((g) => g.name !== null).length
    : null,
)
const scopeReady = ref(false)
const batchLoading = ref(false)
const issueLoading = ref(false)
const rulesLoading = ref(false)
const loading = ref(true)
const checkLoading = ref(false)
const checkError = ref('')
const checkDimensions = ref<string[]>([]),
  checkTables = ref<string[]>([])
const tableChoices = ref<Array<{ tableName: string; tableComment?: string }>>([]),
  tableChoicesLoading = ref(false),
  tableChoicesError = ref('')
const dimensions = ['COMPLETENESS', 'ACCURACY', 'CONSISTENCY', 'TIMELINESS', 'TRACEABILITY']
const checkOperations = useQualityCheckOperations(scope)
const { records: pendingChecks, busy: busyChecks, error: checkRecoveryError } = checkOperations
const pageError = ref('')
const batchError = ref('')
const issueError = ref('')
const rulesError = ref('')
let aggregateEpoch = 0
const evidenceDetails = ref<HTMLDetailsElement>()
function openEvidence() {
  if (evidenceDetails.value) {
    evidenceDetails.value.open = true
    evidenceDetails.value.querySelector('summary')?.focus()
  }
}
function cancelAggregate() {
  aggregateEpoch++
  initTicket++
  aggregateLoading.value = false
  loading.value = false
  aggregateRows.value = []
  aggregateComplete.value = false
  aggregateMessage.value = '表聚合已停止；保留权威摘要，可重新读取。'
}
let requestId = 0
let disposed = false

const datasourceId = scope.datasourceId
const snapshotId = scope.snapshotId
const snapshot = scope.currentSnapshot
const snapshotScoreLabel = computed(() =>
  latestBatch.value?.complete &&
  latestBatch.value.requestedScope === 'FULL' &&
  latestBatch.value.totalScore != null
    ? explicitBatchRequested.value
      ? '所选批次完整评分'
      : '本次完整校验评分'
    : '历史评分',
)
const canViewOverview = computed(() => iamS1.canOnDatasource('governance:view', datasourceId.value))
const canRunCheck = computed(() => iamS1.canOnDatasource('governance:check', datasourceId.value))
const canViewIssues = computed(() => iamS1.canOnDatasource('governance:issue:view', datasourceId.value))
const canViewRules = computed(() => iamS1.canOnDatasource('governance:rule:view', datasourceId.value))
const canReturnToRelease = computed(
  () =>
    String(route.query.returnTo ?? '') === '/admin/releases' ||
    String(route.query.source ?? '') === 'release',
)
const overviewState = computed(() => {
  if (!scopeReady.value || scope.scopeLoading.value)
    return {
      kind: 'loading',
      title: '正在读取治理范围',
      description: '正在确认数据源、快照和当前可用能力。',
    }
  if (iamS1.errorMessage)
    return { kind: 'read-error', title: '治理权限读取失败', description: iamS1.errorMessage }
  if (!scope.ready.value)
    return {
      kind: 'no-scope',
      title: '等待选择校验范围',
      description: scope.scopeIssue.value || '先在上方选择数据源和快照，再查看对应的检查状态和问题。',
    }
  if (!canViewOverview.value)
    return {
      kind: 'no-access',
      title: '无权查看校验状态',
      description: '当前账号没有此数据源的治理总览查看权限；这不表示校验尚未执行。',
    }
  if (batchLoading.value)
    return {
      kind: 'loading',
      title: '正在读取校验状态',
      description: '正在读取最近批次及其覆盖情况。',
    }
  if (batchError.value)
    return {
      kind: 'read-error',
      title: '校验状态读取失败',
      description: '目前无法确认最近检查状态，也无法判断是否存在可核验批次。',
    }
  const batch = latestBatch.value
  if (!batch)
    return {
      kind: 'no-batch',
      title: '尚无可核验的校验批次',
      description:
        snapshot.value?.qualityScore != null
          ? '已有历史评分记录，尚无法确认对应的规则覆盖。'
          : '当前没有可核验的批次记录；这不代表历史上从未执行过校验。',
    }
  if (batch.legacyLimited || batch.status === 'LEGACY')
    return {
      kind: 'legacy',
      title: '历史结果覆盖有限',
      description: '该历史记录缺少可核验的规则版本或覆盖明细，不能作为完整通过依据。',
    }
  if (batch.operationOutcome === 'NOT_STARTED')
    return {
      kind: 'failed',
      title: '检查未开始',
      description: batch.errorSummary || '原请求已终止，没有执行检查。',
    }
  if (batch.status === 'RUNNING')
    return {
      kind: 'running',
      title: '正在执行质量校验',
      description: '当前批次尚未完成；完成前不作为完整校验结果。',
    }
  if (batch.status === 'SUCCESS' && batch.complete)
    return {
      kind: 'success',
      title: '校验已完整完成',
      description: '本次请求范围的检查已完整结束。',
    }
  if (batch.status === 'PARTIAL' || batch.status === 'SUCCESS')
    return {
      kind: 'partial',
      title: '校验部分完成',
      description: '本次检查没有覆盖全部适用目标，不能视作完整通过。',
    }
  if (batch.status === 'FAILED')
    return {
      kind: 'failed',
      title: '校验未完成',
      description: '本次检查没有形成完整结果；请查看失败和跳过原因。',
    }
  return {
    kind: 'unknown',
    title: '校验状态未知',
    description: '当前批次返回了无法识别的状态，请查看批次明细。',
  }
})
function clearSnapshotState() {
  aggregateEpoch++
  aggregateRows.value = []
  aggregateComplete.value = false
  aggregateLoading.value = false
  aggregateMessage.value = ''
  plotChoice.value = null
  latestBatch.value = null
  issueSummary.value = null
  ruleSummary.value = null
  batchLoading.value = false
  issueLoading.value = false
  rulesLoading.value = false
  batchError.value = ''
  issueError.value = ''
  rulesError.value = ''
}

async function loadSnapshotState(
  expectedSnapshotId = snapshotId.value,
  expectedDatasourceId = datasourceId.value,
) {
  const current = ++requestId
  clearSnapshotState()
  if (
    !scopeReady.value ||
    !scope.ready.value ||
    !expectedSnapshotId ||
    !expectedDatasourceId ||
    !canViewOverview.value
  )
    return

  const requests: Promise<void>[] = []
  const requestedBatch = selectedBatchId.value
  const invalidBatch = explicitBatchRequested.value && !requestedBatch
  batchLoading.value = canViewOverview.value && !invalidBatch
  if (invalidBatch) batchError.value = '批次标识无效，请返回最近一次检查或使用完整历史链接。'
  if (canViewOverview.value && !invalidBatch)
    requests.push(
      (requestedBatch
        ? getQualityCheckBatch(expectedSnapshotId, requestedBatch)
        : getLatestQualityCheckBatch(expectedSnapshotId)
      )
        .then((response) => {
          if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
          if (
            response.data &&
            (response.data.snapshotId !== expectedSnapshotId ||
              (response.data.datasourceId != null && response.data.datasourceId !== expectedDatasourceId))
          )
            throw new Error('批次回执错属，未展示旧结果。')
          if (requestedBatch && response.data?.id !== requestedBatch)
            throw new Error('批次回执与指定记录不一致。')
          latestBatch.value = response.data ?? null
        })
        .catch((error) => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId))
            batchError.value = `所选校验结果读取失败。${getHttpErrorMessage(error, '请重试。')}`
        })
        .finally(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) batchLoading.value = false
        }),
    )

  if (canViewIssues.value) {
    issueLoading.value = true
    requests.push(
      getSnapshotQualityIssueSummary(expectedSnapshotId)
        .then(async (response) => {
          if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
          if (overviewSummary(response.data).total == null) throw new Error('待处理问题总数未确认。')
          issueSummary.value = response.data
          issueLoading.value = false
          aggregateLoading.value = true
          const aggregateTicket = ++aggregateEpoch
          const result = await loadOverviewIssues(
            expectedDatasourceId,
            expectedSnapshotId,
            response.data,
            listQualityIssues,
            getSnapshotQualityIssueSummary,
            () =>
              isCurrent(current, expectedDatasourceId, expectedSnapshotId) &&
              canViewIssues.value &&
              aggregateTicket === aggregateEpoch,
          )
          if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
          if (result.forbidden) {
            issueSummary.value = null
            issueError.value = result.message
            plotChoice.value = null
          }
          if (aggregateTicket !== aggregateEpoch) return
          aggregateRows.value = result.rows
          aggregateComplete.value = result.complete
          aggregateMessage.value = result.message
          aggregateLoading.value = false
        })
        .catch((error) => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId))
            issueError.value = `待处理问题统计读取失败，无法确认问题数量。${getHttpErrorMessage(error, '请重试。')}`
        })
        .finally(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) issueLoading.value = false
        }),
    )
  } else {
    issueLoading.value = false
  }

  if (canViewRules.value) {
    rulesLoading.value = true
    requests.push(
      getQualityRuleSummary(expectedSnapshotId)
        .then((response) => {
          if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
          ruleSummary.value = response.data ?? null
        })
        .catch(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId))
            rulesError.value = '当前范围规则摘要读取失败。'
        })
        .finally(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) rulesLoading.value = false
        }),
    )
  } else {
    rulesLoading.value = false
  }
  await Promise.all(requests)
}

function isCurrent(id: number, expectedDatasourceId: number, expectedSnapshotId: number) {
  return (
    !disposed &&
    scope.ready.value &&
    iamS1.loaded &&
    !iamS1.errorMessage &&
    id === requestId &&
    expectedDatasourceId === datasourceId.value &&
    expectedSnapshotId === snapshotId.value
  )
}

async function refresh() {
  const init = ++initTicket,
    token = auth.token
  loading.value = true
  pageError.value = ''
  try {
    // Refresh evidence in the already verified public range; do not reselect a newer snapshot.
    await Promise.all([context.initialize(), iamS1.load(Boolean(iamS1.errorMessage))])
    if (disposed || init !== initTicket || token !== auth.token) return
    scopeReady.value = true
    await loadSnapshotState()
  } catch {
    if (!disposed && init === initTicket && token === auth.token)
      pageError.value = '治理范围或权限能力读取失败，请重试。'
  } finally {
    if (!disposed && init === initTicket && token === auth.token) loading.value = false
  }
}
async function runCheck() {
  const source = datasourceId.value,
    snapshot = snapshotId.value,
    ticket = scope.capture()
  if (
    checkLoading.value ||
    explicitBatchRequested.value ||
    !scope.ready.value ||
    !source ||
    !snapshot ||
    !canRunCheck.value
  )
    return
  const current = () =>
    !disposed &&
    scope.isCurrent(ticket) &&
    canRunCheck.value &&
    source === datasourceId.value &&
    snapshot === snapshotId.value
  if (
    checkTables.value.length &&
    (tableChoicesLoading.value ||
      tableChoicesError.value ||
      checkTables.value.some((name) => !tableChoices.value.some((table) => table.tableName === name)))
  ) {
    checkError.value = '所选表范围尚未核验，请重新选择。'
    return
  }
  checkLoading.value = true
  try {
    checkError.value = ''
    const response = await checkOperations.start({
      dimensions: checkDimensions.value.length ? [...checkDimensions.value] : undefined,
      tableNames: checkTables.value.length ? [...checkTables.value] : undefined,
    })
    if (!current() || !response) return
    const result = response.result
    // 原 UUID 的持久终态已经核对，先消费本次操作；随后主动固定 URL 会使页面代次变化。
    if (checkOperations.terminal(result.status)) checkOperations.consume(response.item)
    if (result.complete && result.requestedScope === 'FULL' && result.qualityScore != null)
      context.snapshots = context.snapshots.map((item) =>
        item.id === snapshot
          ? { ...item, qualityScore: result.qualityScore!, status: result.snapshotStatus || item.status }
          : item,
      )
    // 有真实批次 ID 的成功回执直接固定本次执行；未取得 ID 的未知请求不能借 latest 自证成功。
    if (result.checkBatchId)
      await router.replace({ query: { ...route.query, batchId: String(result.checkBatchId) } })
    else await loadSnapshotState(snapshot, source)
    if (!current()) return
    if (result.status === 'SUCCESS' && result.complete)
      ElMessage.success('质量校验已完整完成；仍需核对问题与业务口径。')
    else if (result.status === 'RUNNING') ElMessage.info('检查已登记，仍在执行；请核对原操作状态。')
    else if (result.status === 'PARTIAL') ElMessage.warning('质量校验部分完成；本次评分不作为完整结果。')
    else ElMessage.error('质量校验未完成，请查看失败覆盖说明。')
  } catch (cause) {
    if (current()) {
      await loadSnapshotState(snapshot, source)
      if (current())
        checkError.value =
          (cause as any)?.response?.data?.message ||
          (cause instanceof Error ? cause.message : '检查未确认完成，请先核对最近批次，勿重复发起')
    }
  } finally {
    if (current()) checkLoading.value = false
  }
}

// 原操作核对不触发执行；终态以其精确批次进入工作面，RUNNING继续保留同范围互斥。
async function reconcileCheck(item: (typeof pendingChecks.value)[number], finish = false) {
  const token = auth.token
  const batch = await checkOperations.reconcile(item, finish)
  if (!batch || token !== auth.token || !iamS1.canOnDatasource('governance:check', item.datasourceId)) return
  await router.push({
    path: '/admin/governance',
    query: {
      datasourceId: String(item.datasourceId),
      snapshotId: String(item.snapshotId),
      batchId: String(batch.id),
    },
  })
  if (token === auth.token && checkOperations.terminal(batch.status)) checkOperations.consume(item)
}

function openIssueCenter() {
  if (!scope.ready.value || !canViewIssues.value || !snapshotId.value || !datasourceId.value) return
  router.push({
    path: '/admin/governance/issues',
    query: {
      ...buildContextQuery('datasource-snapshot', {
        datasourceId: datasourceId.value,
        snapshotId: snapshotId.value,
      }),
      ...(latestBatch.value?.id ? { returnBatchId: String(latestBatch.value.id) } : {}),
      issueDomain: 'SOURCE',
    },
  })
}

function openRules() {
  if (!scope.ready.value || !canViewRules.value) return
  router.push({
    path: '/admin/governance/rules',
    query: buildContextQuery('datasource-snapshot', {
      datasourceId: datasourceId.value,
      snapshotId: snapshotId.value,
    }),
  })
}

function openReleasePage() {
  if (!snapshotId.value || !datasourceId.value) return
  router.push({
    path: '/admin/releases',
    query: buildContextQuery('datasource-snapshot', {
      datasourceId: datasourceId.value,
      snapshotId: snapshotId.value,
    }),
  })
}

function batchStateLabel(batch: QualityCheckBatch) {
  if (batch.legacyLimited || batch.status === 'LEGACY') return '历史覆盖有限'
  return (
    (
      {
        RUNNING: '正在执行',
        SUCCESS: batch.complete ? '完整完成' : '未完整完成',
        PARTIAL: '部分完成',
        FAILED: '校验失败',
      } as Record<string, string>
    )[batch.status] || batch.status
  )
}

function coverageLabel(status: string) {
  return (
    (
      { SUCCESS: '已检查', FAILURE: '检查失败', NOT_APPLICABLE: '不适用', NOT_EXECUTED: '未执行' } as Record<
        string,
        string
      >
    )[status] || status
  )
}

function coverageType(status: string) {
  return (
    (
      { SUCCESS: 'success', FAILURE: 'danger', NOT_APPLICABLE: 'warning', NOT_EXECUTED: 'info' } as Record<
        string,
        string
      >
    )[status] || 'info'
  )
}

function scopeTypeLabel(scope?: string) {
  return (
    ({ GLOBAL: '全局', DATASOURCE: '数据源', TABLE: '表', COLUMN: '字段' } as Record<string, string>)[
      scope || ''
    ] || '范围未知'
  )
}

function requestedScopeLabel(batch: QualityCheckBatch) {
  if (batch.operationOutcome === 'NOT_STARTED') return '未开始执行'
  if (batch.legacyLimited || !batch.requestedScope) return '历史批次范围未知'
  if (batch.requestedScope === 'FULL') return '全量快照'
  if (batch.requestedScope === 'TABLE') return `局部表范围 · ${(batch.requestedTableNames || []).join('、')}`
  return `局部维度 · ${(batch.requestedDimensions || []).map(qualityDimensionLabel).join('、')}`
}

function formatTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ')
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function openPlot(filter: PlotFilter) {
  if (
    !scope.ready.value ||
    !canViewIssues.value ||
    !datasourceId.value ||
    !snapshotId.value ||
    issueError.value ||
    summaryModel.value.total == null
  )
    return
  if (filter.status && !overviewStatuses.includes(filter.status)) return
  if (
    filter.tableName &&
    (!aggregateComplete.value || !aggregateRows.value.some((r) => r.tableName === filter.tableName))
  )
    return
  if (filter.issueDomain && !['SOURCE', 'SEMANTIC'].includes(filter.issueDomain)) return
  if (filter.status) {
    navigatePlot(filter, datasourceId.value, snapshotId.value, scope.capture())
    return
  }
  plotChoice.value = {
    filter: { ...filter },
    source: datasourceId.value,
    snapshot: snapshotId.value,
    ticket: scope.capture(),
  }
}
function navigatePlot(filter: PlotFilter, source: number, snapshot: number, ticket: number) {
  if (
    issueError.value ||
    !issueSummary.value ||
    !scope.isCurrent(ticket) ||
    !canViewIssues.value ||
    source !== datasourceId.value ||
    snapshot !== snapshotId.value ||
    !filter.status ||
    !overviewStatuses.includes(filter.status)
  )
    return
  void router.push({
    path: '/admin/governance/issues',
    query: {
      datasourceId: String(source),
      snapshotId: String(snapshot),
      status: filter.status,
      ...(filter.tableName ? { tableName: filter.tableName } : {}),
      ...(filter.issueDomain ? { issueDomain: filter.issueDomain } : {}),
    },
  })
  plotChoice.value = null
}
function chooseStatus(status: OverviewStatus) {
  const target = plotChoice.value
  if (target) navigatePlot({ ...target.filter, status }, target.source, target.snapshot, target.ticket)
}
function choiceCount(status: OverviewStatus) {
  const target = plotChoice.value
  if (!target || !aggregateComplete.value) return '—'
  return aggregateRows.value.filter(
    (r) =>
      r.status === status &&
      (!target.filter.tableName || r.tableName === target.filter.tableName) &&
      (!target.filter.issueDomain || r.issueDomain === target.filter.issueDomain),
  ).length
}
// 表目录只用于显式缩小范围，失败不阻断主结果读取；范围变化立即丢弃旧选项和选择。
watch(
  scope.generation,
  async () => {
    const ticket = scope.capture(),
      snapshot = snapshotId.value
    checkDimensions.value = []
    checkTables.value = []
    tableChoices.value = []
    tableChoicesError.value = ''
    tableChoicesLoading.value = false
    if (!scope.ready.value || !snapshot || !iamS1.canOnDatasource('metadata:view', datasourceId.value)) return
    tableChoicesLoading.value = true
    try {
      const result = await listSnapshotTables(snapshot)
      if (scope.isCurrent(ticket)) tableChoices.value = result.data || []
    } catch {
      if (scope.isCurrent(ticket)) tableChoicesError.value = '表目录读取失败，不能选择表范围。'
    } finally {
      if (scope.isCurrent(ticket)) tableChoicesLoading.value = false
    }
  },
  { immediate: true },
)
// 同源切换历史批次也使旧响应失效；不依赖源/快照发生变化才更新证据。
watch(
  () => route.query.batchId,
  () => {
    if (scopeReady.value) void loadSnapshotState()
  },
  { flush: 'sync' },
)
onMounted(refresh)
watch(
  scope.generation,
  () => {
    requestId++
    clearSnapshotState()
    checkLoading.value = false
    coverageOpen.value = false
    statusHelpOpen.value = false
    plotChoice.value = null
    loading.value = false
    pageError.value = ''
    checkError.value = ''
    if (scopeReady.value) void loadSnapshotState()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  requestId++
})
</script>
<template>
  <main class="quality-page post-login-page governance-workspace">
    <TaskPageHeader title="治理总览" description="当前来源与快照的规模、集中度、处理结构与校验依据。"
      ><template #actions
        ><div v-if="scope.ready.value && canViewOverview" class="overview-numbers">
          <div>
            <b>{{ summaryModel.total ?? '—' }}</b
            ><span>条未解决问题</span>
          </div>
          <div>
            <b>{{ tableCount ?? '—' }}</b
            ><span>个涉及表</span>
          </div>
        </div>
        <el-button
          :icon="RefreshCw"
          :loading="loading || batchLoading || issueLoading || aggregateLoading"
          @click="refresh"
          >刷新</el-button
        ><el-button
          v-if="canReturnToRelease"
          :disabled="!iamS1.canOnDatasource('metadata:release:view', datasourceId)"
          @click="openReleasePage"
          >返回元数据版本</el-button
        ></template
      ></TaskPageHeader
    >
    <!-- 主接口已验证的范围独立展示，不伪造无权读取的公共快照目录。 -->
    <div v-if="scope.primaryVerified.value" class="governance-primary-scope" role="status" aria-label="主业务已验证范围">
      <strong>当前处理范围</strong><span>数据源 #{{ datasourceId }}</span><span>快照 v{{ scope.currentSnapshot.value?.snapshotVersion }} · #{{ snapshotId }}</span>
    </div>
    <section
      v-if="pendingChecks.length || checkRecoveryError || checkError"
      class="quality-check-recovery"
      aria-label="检查操作回执"
      role="status"
    >
      <p v-if="checkError || checkRecoveryError" class="quality-check-recovery__error">
        {{ checkError || checkRecoveryError }}
      </p>
      <div v-for="item in pendingChecks" :key="item.operationId" class="quality-check-recovery__row">
        <div>
          <strong
            >数据源 #{{ item.datasourceId }} · 快照 #{{ item.snapshotId }} ·
            {{
              item.status === 'RUNNING'
                ? '正在检查'
                : item.status
                  ? ({ SUCCESS: '执行完成', PARTIAL: '部分完成', FAILED: '检查失败' } as Record<string, string>)[item.status] || '结果待核对'
                  : '结果待核对'
            }}</strong
          ><small
            >操作 {{ item.operationId
            }}<template v-if="item.batchId"> · 批次 #{{ item.batchId }}</template></small
          >
        </div>
        <div>
          <el-button :disabled="busyChecks.includes(item.operationId)" @click="reconcileCheck(item)"
            >核对原操作</el-button
          ><el-button
            v-if="!item.status"
            :disabled="busyChecks.includes(item.operationId)"
            @click="reconcileCheck(item, true)"
            >结束未开始请求</el-button
          >
        </div>
      </div>
    </section>
    <ErrorState
      v-if="pageError || iamS1.errorMessage"
      :message="pageError || iamS1.errorMessage || ''"
      @retry="refresh"
    />
    <LoadingState v-else-if="!scopeReady || scope.scopeLoading.value" text="正在读取治理范围…" />
    <section v-else-if="!scope.ready.value || !canViewOverview" class="scope-problem" role="status">
      <h2>{{ overviewState.title }}</h2>
      <p>{{ overviewState.description }}</p>
      <p v-if="!scope.ready.value">选择数据源和快照后查看问题。</p>
    </section>
    <template v-else>
      <!-- 执行是独立的显式动作，当前配置、批次证据与当前问题分别提供入口。 -->
      <section class="quality-check-workbench" aria-label="质量检查工作面">
        <div class="quality-check-workbench__identity">
          <span>{{ batchHeading }}</span>
          <h2>{{ overviewState.title }}</h2>
          <p v-if="latestBatch">
            批次 #{{ latestBatch.id }} · {{ requestedScopeLabel(latestBatch) }} ·
            {{ formatTime(latestBatch.startedAt) }}
          </p>
          <p v-else>{{ overviewState.description }}</p>
        </div>
        <div class="quality-check-workbench__actions">
          <div
            v-if="!explicitBatchRequested"
            class="quality-check-workbench__range"
            aria-label="本次检查范围"
          >
            <el-select
              v-model="checkDimensions"
              multiple
              collapse-tags
              placeholder="全部维度"
              aria-label="检查维度"
              :disabled="checkLoading || checkOperations.pending(datasourceId, snapshotId)"
              ><el-option
                v-for="dimension in dimensions"
                :key="dimension"
                :value="dimension"
                :label="qualityDimensionLabel(dimension)"
            /></el-select>
            <el-select
              v-model="checkTables"
              multiple
              filterable
              collapse-tags
              placeholder="全部表"
              aria-label="检查表范围"
              :loading="tableChoicesLoading"
              :disabled="
                checkLoading ||
                checkOperations.pending(datasourceId, snapshotId) ||
                !iamS1.canOnDatasource('metadata:view', datasourceId) ||
                !!tableChoicesError
              "
              ><el-option
                v-for="table in tableChoices"
                :key="table.tableName"
                :value="table.tableName"
                :label="table.tableComment ? `${table.tableName} · ${table.tableComment}` : table.tableName"
            /></el-select>
            <small v-if="tableChoicesError" role="alert">{{ tableChoicesError }}</small>
          </div>
          <el-button v-if="explicitBatchRequested" @click="showLatestBatch">查看最近一次检查</el-button>
          <el-button
            v-else
            type="primary"
            :icon="ClipboardCheck"
            :loading="checkLoading"
            :disabled="
              !scope.ready.value ||
              !canRunCheck ||
              checkLoading ||
              checkOperations.pending(datasourceId, snapshotId) ||
              latestBatch?.status === 'RUNNING'
            "
            @click="runCheck"
            >执行质量校验</el-button
          >
          <el-button v-if="canViewIssues" @click="openIssueCenter">查看当前问题</el-button>
          <el-button v-if="canViewRules" link @click="openRules">管理规则</el-button>
        </div>
      </section>
      <GovernanceOverviewCharts
        :summary="issueSummary"
        :rows="aggregateRows"
        :complete="aggregateComplete"
        :aggregate-loading="aggregateLoading"
        :message="aggregateMessage"
        :issue-loading="issueLoading"
        :issue-error="issueError"
        :can-view-issues="canViewIssues"
        :batch="latestBatch"
        :batch-label="explicitBatchRequested ? '指定批次' : '最近批次'"
        :batch-loading="batchLoading"
        :batch-error="batchError"
        :batch-state="overviewState.kind === 'no-batch' ? '暂无可追溯的检查结果' : overviewState.title"
        :batch-description="overviewState.description"
        :batch-scope="latestBatch ? requestedScopeLabel(latestBatch) : ''"
        @plot="openPlot"
        @help="statusHelpOpen = true"
        @coverage="openEvidence"
        @cancel="cancelAggregate"
        @retry="loadSnapshotState()"
      />
      <details ref="evidenceDetails" class="quality-page__details">
        <summary>
          <h2>逐规则执行依据</h2>
          <span v-if="latestBatch">
            {{ batchStateLabel(latestBatch) }} · {{ formatTime(latestBatch.startedAt) }}
          </span>
          <span v-else-if="batchLoading">正在读取</span>
          <span v-else-if="batchError">读取失败</span>
          <span v-else-if="!canViewOverview">无查看权限</span>
          <span v-else>暂无可追溯批次</span>
        </summary>
        <p v-if="latestBatch?.errorSummary" role="alert">
          <AlertTriangle :size="14" /> {{ latestBatch.errorSummary }}
        </p>
        <div v-if="canViewRules" class="rule-context">
          <h3>规则准备</h3>
          <TitleHelp label="规则准备" text="配置适用规划不等于执行通过；实际执行与规则版本分别核对。" />
          <p v-if="ruleSummary">
            已启用 {{ ruleSummary.enabledCount }} 项 · 当前快照可参与 {{ ruleSummary.applicableCount }} 项
          </p>
          <ErrorState v-if="rulesError" :message="rulesError" @retry="loadSnapshotState()" /><button
            class="text-link"
            @click="coverageOpen = true"
          >
            查看规则规划与覆盖 <ArrowRight :size="13" />
          </button>
        </div>
        <div v-if="canViewOverview && snapshot" class="quality-page__scope-context">
          <span>快照 v{{ snapshot.snapshotVersion }}</span>
          <BusinessStatusBadge :status="snapshot.status" :label="snapshotStatusLabel(snapshot.status)" />
          <span v-if="snapshot.qualityScore != null">
            {{ snapshotScoreLabel }}
            {{
              latestBatch?.complete && latestBatch.requestedScope === 'FULL' && latestBatch.totalScore != null
                ? latestBatch.totalScore
                : snapshot.qualityScore
            }}
            分
          </span>
        </div>
        <p
          v-if="!latestBatch && canViewOverview && snapshot?.qualityScore != null"
          class="quality-page__inline-state"
        >
          已有历史评分记录，尚无法确认对应的规则覆盖。
        </p>
        <ErrorState v-if="batchError" :message="batchError" @retry="loadSnapshotState()" />
        <p v-else-if="!canViewOverview" class="quality-page__inline-state">没有查看校验批次的权限。</p>
        <p v-else-if="batchLoading" class="quality-page__inline-state">正在读取批次…</p>
        <p v-else-if="!latestBatch" class="quality-page__inline-state" aria-label="最近批次执行情况">
          暂无可核验批次。
        </p>
        <div v-else class="quality-page__record">
          <h3>校验明细 · {{ requestedScopeLabel(latestBatch) }}</h3>
          <p v-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'">
            历史覆盖有限，实际执行数不可核验。
          </p>
          <p v-else>
            {{ latestBatch.executedRuleCount ?? '—' }} 项实际执行 ·
            {{ latestBatch.failedRuleCount ?? '—' }} 项失败 ·
            {{ latestBatch.notApplicableRuleCount ?? '—' }} 项不适用
          </p>
          <p
            v-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'"
            class="quality-page__history-note"
            role="status"
          >
            此历史结果没有可证明的规则配置版本和执行覆盖；当前配置不会被用来补造历史依据。
          </p>
          <div
            v-if="Object.keys(latestBatch.dimensionScores || {}).length"
            class="quality-page__dimension-list"
          >
            <div
              v-for="[dimension, score] in Object.entries(latestBatch.dimensionScores || {})"
              :key="dimension"
            >
              <span>{{ qualityDimensionLabel(dimension) }}</span>
              <strong>{{ score }} 分</strong>
            </div>
          </div>
          <div v-if="latestBatch.rules?.length" class="quality-page__coverage-list">
            <article
              v-for="rule in latestBatch.rules"
              :key="`${rule.ruleId}-${rule.ruleVersion}`"
              class="quality-page__coverage-row"
            >
              <div class="quality-page__coverage-rule">
                <strong>{{ rule.ruleName }}</strong>
                <span>
                  {{ qualityDimensionLabel(rule.dimension) }} · {{ scopeTypeLabel(rule.scopeType) }}
                  <template v-if="rule.targetTableName">· {{ rule.targetTableName }}</template>
                  <template v-if="rule.targetColumnName">.{{ rule.targetColumnName }}</template>
                </span>
              </div>
              <div class="quality-page__coverage-status">
                <el-tag size="small" :type="coverageType(rule.status)">
                  {{ coverageLabel(rule.status) }}
                </el-tag>
                <small>
                  v{{ rule.ruleVersion ?? '—' }} · 检查 {{ rule.checkedTargetCount ?? '—' }}/{{
                    rule.targetCount ?? '—'
                  }}
                  个目标 · {{ rule.issueCount }} 个问题
                </small>
                <p
                  v-if="rule.reason"
                  :class="{
                    'quality-page__coverage-reason--error': rule.status === 'FAILURE',
                    'quality-page__coverage-reason--skipped':
                      rule.status === 'NOT_EXECUTED' || rule.status === 'NOT_APPLICABLE',
                  }"
                  :role="rule.status === 'FAILURE' ? 'alert' : 'status'"
                >
                  {{ rule.reason }}
                </p>
              </div>
            </article>
          </div>
          <p
            v-else-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'"
            class="quality-page__inline-state"
          >
            该历史结果没有规则覆盖记录。
          </p>
          <p v-else class="quality-page__inline-state">本批次没有规则覆盖记录。</p>
        </div>
      </details>
    </template>
    <el-drawer
      v-model="coverageOpen"
      title="当前快照规则规划与覆盖"
      size="680px"
      append-to-body
      destroy-on-close
      class="governance-drawer"
      ><RuleApplicabilitySummary
        :summary="ruleSummary"
        :loading="rulesLoading"
        :error="rulesError"
        @retry="loadSnapshotState()"
    /></el-drawer>
    <el-dialog
      :model-value="!!plotChoice"
      title="选择要查看的处理状态"
      width="560px"
      append-to-body
      destroy-on-close
      @close="plotChoice = null"
      ><template v-if="plotChoice"
        ><p class="plot-context">
          当前来源 #{{ plotChoice.source }} · 快照 #{{ plotChoice.snapshot
          }}<template v-if="plotChoice.filter.tableName"> · 表 {{ plotChoice.filter.tableName }}</template
          ><template v-if="plotChoice.filter.issueDomain">
            · {{ plotChoice.filter.issueDomain === 'SOURCE' ? '来源问题' : '语义问题' }}</template
          >
        </p>
        <p>问题页一次按一个处理状态筛选。选择状态后前往专页；不把全部状态冒充未解决集合。</p>
        <div class="status-choices">
          <button v-for="status in overviewStatuses" :key="status" @click="chooseStatus(status)">
            {{ overviewStatusNames[status] }}<b>{{ choiceCount(status) }}</b
            ><small>{{ aggregateComplete ? '条 · 完整明细对应数量' : '交叉数量未核实' }}</small>
          </button>
        </div></template
      ></el-dialog
    >
    <el-dialog
      v-model="statusHelpOpen"
      title="需重新处理是什么意思？"
      width="600px"
      append-to-body
      destroy-on-close
      ><p><strong>此前已经结束处理的问题，又回到需要确认、处理的范围。</strong></p>
      <p>
        可能是人工重新开启已解决或已驳回的问题，也可能是语义复查发现已结束的问题仍有异常；不能统一解释成复查失败或再次发现。
      </p>
      <p>后续仍需重新确认，不能直接标为已解决；问题状态变化不代表原业务情景已经核验。</p></el-dialog
    >
  </main>
</template>
<style scoped>
.quality-check-recovery {
  padding: 16px 20px;
  border: 1px solid var(--do-line);
  border-left: 3px solid var(--do-warning);
  border-radius: 8px;
  background: var(--do-surface);
}
.quality-check-recovery__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 0;
}
.quality-check-recovery__row small {
  display: block;
  margin-top: 5px;
  color: var(--do-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.quality-check-recovery__error {
  color: var(--do-danger);
  margin: 0 0 8px;
}
/* 执行带沿用同一工作面边界，关键动作无需展开历史；窄窗口保持身份和动作各自成行。 */
.quality-check-workbench {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  padding: 20px 24px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}
.quality-check-workbench__identity > span {
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 600;
}
.quality-check-workbench__identity h2 {
  margin: 6px 0;
  font-size: 19px;
  font-weight: 600;
  color: var(--do-ink);
}
.quality-check-workbench__identity p {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--do-muted);
}
.quality-check-workbench__range {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
  justify-content: flex-end;
}
.quality-check-workbench__range .el-select {
  width: 180px;
}
.quality-check-workbench__actions {
  max-width: 520px;
  justify-content: flex-end;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
@media (max-width: 1100px) {
  .quality-check-workbench {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
  }
}

.quality-page {
  display: grid;
  gap: 18px;
  min-width: 0;
}
.quality-page :deep(.task-page-header) {
  margin-bottom: 0;
}
.overview-numbers {
  display: flex;
  gap: 22px;
  align-items: center;
  margin-right: 5px;
}
.overview-numbers > div {
  display: flex;
  align-items: baseline;
  gap: 7px;
}
.overview-numbers > div + div {
  border-left: 1px solid var(--do-line);
  padding-left: 20px;
}
.overview-numbers b {
  font-size: 27px;
  line-height: 1;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.overview-numbers span {
  font-size: 11px;
  color: var(--do-muted);
}
.scope-problem {
  padding: 24px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 10px;
}
.scope-problem h2 {
  font-size: 18px;
}
.scope-problem p {
  font-size: 13px;
  color: var(--do-muted);
  line-height: 1.8;
}
.quality-page__details {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  padding: 14px 18px;
  color: var(--do-muted);
  font-size: 12px;
}
.quality-page__details > summary {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 20px;
  list-style-position: inside;
}
.quality-page__details summary h2 {
  font-size: 13px;
  font-weight: 550;
  display: inline;
  margin: 0;
  color: var(--do-ink);
}
.quality-page__details summary > span {
  margin-left: auto;
  font-size: 11px;
}
.evidence-actions {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  padding: 20px 0;
  border-bottom: 1px solid var(--do-line);
}
.quality-page__scope-context {
  display: flex;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
  margin: 16px 0;
}
.quality-page__record h3,
.rule-context h3 {
  font-size: 14px;
  font-weight: 600;
}
.quality-page__record p,
.quality-page__inline-state,
.quality-page__history-note {
  font-size: 12px;
  line-height: 1.8;
}
.quality-page__coverage-list {
  display: grid;
  gap: 10px;
}
.quality-page__coverage-row {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  border-top: 1px solid var(--do-line);
  padding: 12px 0;
}
.quality-page__coverage-rule {
  display: grid;
  gap: 6px;
}
.quality-page__coverage-rule span,
.quality-page__coverage-status small {
  font-size: 11px;
  color: var(--do-muted);
}
.quality-page__coverage-reason--error {
  color: var(--do-danger);
}
.quality-page__coverage-status {
  text-align: right;
}
.quality-page__dimension-list {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}
.quality-page__dimension-list div {
  display: flex;
  gap: 8px;
}
.rule-context {
  padding-top: 14px;
  position: relative;
}
.text-link {
  border: 0;
  background: none;
  color: var(--do-primary-strong);
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0;
  cursor: pointer;
  font: inherit;
}
.plot-context {
  overflow-wrap: anywhere;
}
.status-choices {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin: 20px 0;
}
.status-choices button {
  border: 1px solid var(--do-line);
  border-radius: 6px;
  background: var(--do-surface);
  text-align: left;
  padding: 13px;
  color: var(--do-ink);
  font: inherit;
  cursor: pointer;
}
.status-choices b {
  display: block;
  font-size: 24px;
  margin: 7px 0;
}
.status-choices small {
  font-size: 11px;
  color: var(--do-muted);
}
button:focus-visible {
  outline: 3px solid var(--do-primary);
  outline-offset: 3px;
}
</style>
