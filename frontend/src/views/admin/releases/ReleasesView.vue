<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GitCompareArrows, RefreshCw, RotateCcw, Send } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import { listQualityIssues, triggerQualityCheck } from '../../../api/admin/governance'
import {
  changeSnapshotStatus,
  getPublishedSnapshot,
  getReleaseGate,
  listSnapshotAuditLogs,
  listVersionHistory,
  publishSnapshot,
  revokeSnapshot,
  type AuditLogItem,
  type VersionHistoryItem,
} from '../../../api/admin/versioning'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ScopeBar from '../../../components/admin/ScopeBar.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import SnapshotDetailPanel from '../../../components/admin/SnapshotDetailPanel.vue'
import ReleaseGatePanel from '../../../components/admin/ReleaseGatePanel.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import { useSnapshotDetail, type SnapshotDetailContext } from '../../../composables/useSnapshotDetail'
import { useReleaseGate } from '../../../composables/useReleaseGate'
import { getHttpErrorMessage } from '../../../utils/httpError'
import { buildContextQuery } from '../../../utils/adminNavigation'
import { snapshotStatusLabel } from '../../../utils/enumLabels'

type DetailTab = 'content' | 'operations'

interface CompareVersionOption {
  snapshotId: number
  datasourceId: number
  snapshotVersion: number
  status: string
  datasourceName?: string
}

interface SnapshotDialogContext extends SnapshotDetailContext {
  snapshotVersion: number
  status: string
  datasourceName?: string
}

const router = useRouter()
const context = useAdminContextStore()
const iamS1 = useIamS1Store()

const history = ref<VersionHistoryItem[]>([])
const published = ref<VersionHistoryItem | null>(null)
const total = ref(0)
const page = ref(1)
const pageSize = 20
const listLoading = ref(true)
const publishedLoading = ref(true)
const listError = ref('')
const publishedError = ref('')
const actionLoading = ref(false)
const selectedVersions = ref<CompareVersionOption[]>([])
const hasReviewerNames = computed(() => history.value.some((item) => Boolean(item.reviewedBy)))
const datasourceId = computed(() => context.datasourceId)

const snapshotDialogVisible = ref(false)
const snapshotDialogContext = ref<SnapshotDialogContext | null>(null)
const activeDetailTab = ref<DetailTab>('content')
const {
  detail: snapshotDialogDetail,
  loading: snapshotDialogLoading,
  error: snapshotDialogError,
  load: loadSnapshotDialogDetail,
  clear: clearSnapshotDialogDetail,
} = useSnapshotDetail()
const {
  gate: dialogGate,
  loading: dialogGateLoading,
  error: dialogGateError,
  load: loadDialogGate,
  clear: clearDialogGate,
} = useReleaseGate()

const publishConfirmVisible = ref(false)
const publishTarget = ref<VersionHistoryItem | null>(null)
const publishSubmitting = ref(false)
const {
  gate: publishGate,
  loading: publishGateLoading,
  error: publishGateError,
  load: loadPublishGate,
  clear: clearPublishGate,
} = useReleaseGate()

const auditRecords = ref<AuditLogItem[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditPageSize = 20
const auditState = ref<'idle' | 'loading' | 'error' | 'success'>('idle')
const auditError = ref('')
const auditLoadedKey = ref('')
const auditRequestKey = ref('')

const issuePresenceState = ref<'idle' | 'loading' | 'error' | 'success'>('idle')
const pendingIssueCount = ref<number | null>(null)
const issuePresenceError = ref('')

let listRequestId = 0
let auditRequestId = 0
let issuePresenceRequestId = 0
let disposed = false

const RELEASE_VIEW_FUNCTION = 'metadata:release:view'
const RELEASE_REVIEW_FUNCTION = 'metadata:release:review'
const RELEASE_PUBLISH_FUNCTION = 'metadata:release:publish'
const GOVERNANCE_VIEW_FUNCTION = 'governance:view'
const GOVERNANCE_CHECK_FUNCTION = 'governance:check'
const GOVERNANCE_ISSUE_VIEW_FUNCTION = 'governance:issue:view'
const GOVERNANCE_ISSUE_MANAGE_FUNCTION = 'governance:issue:manage'
const PENDING_ISSUE_STATUSES = ['OPEN', 'CONFIRMED', 'REOPENED'] as const

const currentDatasourceName = computed(() => context.currentDatasource?.name || '')
const dialogSnapshotVersion = computed(() =>
  snapshotDialogDetail.value?.snapshot.snapshotVersion ?? snapshotDialogContext.value?.snapshotVersion,
)
const dialogTitle = computed(() => dialogSnapshotVersion.value ? `版本 v${dialogSnapshotVersion.value}` : '版本详情')
const dialogStatus = computed(() => snapshotDialogDetail.value?.snapshot.status || snapshotDialogContext.value?.status || '')
const dialogDatasourceId = computed(() => snapshotDialogDetail.value?.snapshot.datasourceId ?? snapshotDialogContext.value?.datasourceId)
const dialogDatasourceName = computed(() =>
  snapshotDialogDetail.value?.datasourceName?.trim()
    || snapshotDialogDetail.value?.snapshot.datasourceName?.trim()
    || snapshotDialogContext.value?.datasourceName
    || currentDatasourceName.value
    || (dialogDatasourceId.value ? `数据源 ID ${dialogDatasourceId.value}` : ''),
)
const dialogIsCurrentOfficial = computed(() => {
  const snapshotId = snapshotDialogDetail.value?.snapshot.id ?? snapshotDialogContext.value?.snapshotId
  return snapshotId !== undefined && snapshotId === published.value?.snapshotId
})
const canViewDialogOperations = computed(() => hasDatasourceFunction(RELEASE_VIEW_FUNCTION, dialogDatasourceId.value))
const canViewDialogGate = computed(() => hasDatasourceFunction(RELEASE_VIEW_FUNCTION, dialogDatasourceId.value))
const canViewDialogGovernance = computed(() => hasDatasourceFunction(GOVERNANCE_VIEW_FUNCTION, dialogDatasourceId.value))
const canViewDialogIssues = computed(() => hasDatasourceFunction(GOVERNANCE_ISSUE_VIEW_FUNCTION, dialogDatasourceId.value))
const canManageDialogIssues = computed(() => hasDatasourceFunction(GOVERNANCE_ISSUE_MANAGE_FUNCTION, dialogDatasourceId.value))
const canShowIssueAction = computed(() => canViewDialogIssues.value && (pendingIssueCount.value ?? 0) > 0)
const shouldEmphasizeIssueAction = computed(() => canShowIssueAction.value && canManageDialogIssues.value)

const compareSelectionReason = computed(() => {
  if (!datasourceId.value) return '请先选择数据源'
  if (listLoading.value) return '正在读取版本列表…'
  if (listError.value) return '版本列表读取失败，暂不可比较'
  if (total.value < 2) return '至少需要两个可比较版本才能比较'
  if (selectedVersions.value.length < 2) return '选择两个版本，第一个作为基准版本'
  const [base, compare] = selectedVersions.value
  if (base.snapshotId === compare.snapshotId) return '请选择两个不同版本'
  if (base.datasourceId !== compare.datasourceId || base.datasourceId !== datasourceId.value) {
    return '只能比较同一数据源中的版本'
  }
  return ''
})
const canCompareSelected = computed(() => compareSelectionReason.value === '')
const compareDirectionLabel = computed(() => {
  if (selectedVersions.value.length !== 2) return ''
  const [base, compare] = selectedVersions.value
  return `基准 v${base.snapshotVersion} → 对比 v${compare.snapshotVersion}`
})

function hasDatasourceFunction(functionCode: string, sourceId?: number): boolean {
  if (sourceId !== undefined) return iamS1.canOnDatasource(functionCode, sourceId)
  return iamS1.systemAdmin
}

function rowDatasourceId(row: VersionHistoryItem): number | undefined {
  return row.datasourceId ?? datasourceId.value
}

function canViewAuditRecords(row: VersionHistoryItem): boolean {
  return hasDatasourceFunction(RELEASE_VIEW_FUNCTION, rowDatasourceId(row))
}

function canReviewSnapshot(row: VersionHistoryItem): boolean {
  return hasDatasourceFunction(RELEASE_REVIEW_FUNCTION, rowDatasourceId(row))
}

function canPublishSnapshot(row: VersionHistoryItem): boolean {
  return hasDatasourceFunction(RELEASE_PUBLISH_FUNCTION, rowDatasourceId(row))
}

function canRunQualityCheck(row: VersionHistoryItem): boolean {
  return hasDatasourceFunction(GOVERNANCE_CHECK_FUNCTION, rowDatasourceId(row))
}

function canViewIssuesForRow(row: VersionHistoryItem): boolean {
  return hasDatasourceFunction(GOVERNANCE_ISSUE_VIEW_FUNCTION, rowDatasourceId(row))
}

function isCurrentOfficial(row: VersionHistoryItem): boolean {
  return Boolean(published.value?.snapshotId && row.snapshotId === published.value.snapshotId)
}

function canCompareWithCurrent(row: VersionHistoryItem): boolean {
  const official = published.value
  const rowSourceId = rowDatasourceId(row)
  const officialSourceId = official?.datasourceId ?? datasourceId.value
  return Boolean(
    official?.snapshotId
    && row.snapshotId !== official.snapshotId
    && rowSourceId
    && rowSourceId === officialSourceId
    && rowSourceId === datasourceId.value,
  )
}

function rowActionWidths(row: VersionHistoryItem): number[] {
  const widths: number[] = []
  const textWidth = (label: string, fontSize = 14, iconWidth = 0) => Math.ceil(label.length * fontSize + iconWidth + 10)

  if (canViewAuditRecords(row)) widths.push(textWidth('操作记录'))
  if (row.status === 'DRAFT' && canRunQualityCheck(row)) widths.push(textWidth('开始检查'))
  if (row.status === 'ISSUE_FOUND' && canViewIssuesForRow(row)) widths.push(textWidth('进入问题中心', 13))
  if (row.status === 'ISSUE_FOUND' && canReviewSnapshot(row)) widths.push(textWidth('批准'))
  if (row.status === 'APPROVED' && canPublishSnapshot(row)) widths.push(textWidth('发布', 14, 19))
  if (row.status === 'PUBLISHED' && canPublishSnapshot(row)) widths.push(textWidth('撤回', 14, 19))
  if (canCompareWithCurrent(row)) widths.push(textWidth('与当前正式版比较'))

  return widths
}

const hasVisibleRowActions = computed(() => history.value.some((row) => rowActionWidths(row).length > 0))
const actionsColumnWidth = computed(() => Math.max(
  72,
  ...history.value.map((row) => {
    const widths = rowActionWidths(row)
    if (!widths.length) return 0
    return widths.reduce((sum, width) => sum + width, 24 + (widths.length - 1) * 16)
  }),
))

function formatVersionTime(value?: string | null) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ')
  const parts = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(date)
  const part = (type: string) => parts.find((item) => item.type === type)?.value || ''
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`
}

function formatCount(value?: number | null) {
  return value == null ? '未采集统计' : value.toLocaleString('zh-CN')
}

function versionOption(row: VersionHistoryItem): CompareVersionOption | null {
  const sourceId = rowDatasourceId(row)
  if (!sourceId) return null
  return {
    snapshotId: row.snapshotId,
    datasourceId: sourceId,
    snapshotVersion: row.snapshotVersion,
    status: row.status,
    datasourceName: row.datasourceName,
  }
}

function isSelectedForCompare(snapshotId: number) {
  return selectedVersions.value.some((item) => item.snapshotId === snapshotId)
}

function canSelectForCompare(row: VersionHistoryItem) {
  const selected = isSelectedForCompare(row.snapshotId)
  return selected || selectedVersions.value.length < 2
}

function toggleCompareSelection(row: VersionHistoryItem, value: unknown) {
  const selected = Boolean(value)
  if (!selected) {
    selectedVersions.value = selectedVersions.value.filter((item) => item.snapshotId !== row.snapshotId)
    return
  }
  if (isSelectedForCompare(row.snapshotId)) return
  const option = versionOption(row)
  if (!option) return
  if (selectedVersions.value.length >= 2) return
  selectedVersions.value = [...selectedVersions.value, option]
}

function clearCompareSelection() {
  selectedVersions.value = []
}

async function load() {
  const sourceId = datasourceId.value
  const currentRequest = ++listRequestId
  if (!sourceId) {
    history.value = []
    published.value = null
    total.value = 0
    selectedVersions.value = []
    listError.value = ''
    publishedError.value = ''
    listLoading.value = false
    publishedLoading.value = false
    return
  }

  listLoading.value = true
  publishedLoading.value = true
  listError.value = ''
  publishedError.value = ''
  try {
    const [historyResult, publishedResult] = await Promise.allSettled([
      listVersionHistory(sourceId, { page: page.value, size: pageSize }),
      getPublishedSnapshot(sourceId),
    ])
    if (disposed || currentRequest !== listRequestId || sourceId !== datasourceId.value) return

    if (historyResult.status === 'fulfilled') {
      history.value = historyResult.value.data.records || []
      total.value = historyResult.value.data.total || 0
      const refreshedRows = new Map(history.value.map((item) => [item.snapshotId, item]))
      selectedVersions.value = selectedVersions.value.map((item) => {
        const refreshed = refreshedRows.get(item.snapshotId)
        return refreshed ? (versionOption(refreshed) || item) : item
      })
    } else {
      history.value = []
      total.value = 0
      listError.value = getHttpErrorMessage(historyResult.reason, '版本列表读取失败')
    }

    if (publishedResult.status === 'fulfilled') {
      published.value = publishedResult.value.data || null
    } else {
      published.value = null
      publishedError.value = getHttpErrorMessage(publishedResult.reason, '当前正式版读取失败')
    }
  } finally {
    if (!disposed && currentRequest === listRequestId) {
      listLoading.value = false
      publishedLoading.value = false
    }
  }
}

function changePage(nextPage: number) {
  page.value = nextPage
  void load()
}

function openSnapshotDialog(item: VersionHistoryItem, tab: DetailTab = 'content') {
  const sourceId = rowDatasourceId(item)
  if (!sourceId || (tab === 'operations' && !canViewAuditRecords(item))) return

  snapshotDialogContext.value = {
    snapshotId: item.snapshotId,
    datasourceId: sourceId,
    snapshotVersion: item.snapshotVersion,
    status: item.status,
    datasourceName: item.datasourceName || context.currentDatasource?.name,
  }
  activeDetailTab.value = tab
  auditPage.value = 1
  resetAuditRecords()
  resetIssuePresence()
  clearSnapshotDialogDetail()
  clearDialogGate()
  snapshotDialogVisible.value = true
  void loadDialogDetailFromContext()
  if (hasDatasourceFunction(RELEASE_VIEW_FUNCTION, sourceId)) {
    void loadDialogGate(item.snapshotId)
  }
}

function closeSnapshotDialog() {
  snapshotDialogVisible.value = false
  clearDialogGate()
}

function retryDialogGate() {
  const openedFor = snapshotDialogContext.value
  if (openedFor && hasDatasourceFunction(RELEASE_VIEW_FUNCTION, openedFor.datasourceId)) {
    void loadDialogGate(openedFor.snapshotId)
  }
}

function retrySnapshotDialogDetail() {
  void loadDialogDetailFromContext()
}

function loadDialogDetailFromContext() {
  const openedFor = snapshotDialogContext.value
  if (!snapshotDialogVisible.value || !openedFor) return
  return loadSnapshotDialogDetail({ snapshotId: openedFor.snapshotId, datasourceId: openedFor.datasourceId })
}

function resetAuditRecords() {
  auditRequestId += 1
  auditRecords.value = []
  auditTotal.value = 0
  auditState.value = 'idle'
  auditError.value = ''
  auditLoadedKey.value = ''
  auditRequestKey.value = ''
}

function loadAuditRecords(force = false) {
  const openedFor = snapshotDialogContext.value
  if (!snapshotDialogVisible.value || activeDetailTab.value !== 'operations' || !openedFor) return
  if (!hasDatasourceFunction(RELEASE_VIEW_FUNCTION, openedFor.datasourceId)) return
  const key = `${openedFor.snapshotId}:${auditPage.value}`
  if (!force && auditLoadedKey.value === key && auditState.value === 'success') return
  if (!force && auditRequestKey.value === key && auditState.value === 'loading') return

  const currentRequest = ++auditRequestId
  const snapshotId = openedFor.snapshotId
  const datasourceIdAtOpen = openedFor.datasourceId
  auditState.value = 'loading'
  auditRequestKey.value = key
  auditError.value = ''
  auditRecords.value = []
  void (async () => {
    try {
      const result = await listSnapshotAuditLogs(snapshotId, { page: auditPage.value, size: auditPageSize })
      if (
        disposed
        || currentRequest !== auditRequestId
        || !snapshotDialogVisible.value
        || snapshotDialogContext.value?.snapshotId !== snapshotId
        || snapshotDialogContext.value?.datasourceId !== datasourceIdAtOpen
      ) return
      auditRecords.value = result.data.records || []
      auditTotal.value = result.data.total || 0
      auditLoadedKey.value = key
      auditState.value = 'success'
    } catch (cause) {
      if (
        currentRequest !== auditRequestId
        || snapshotDialogContext.value?.snapshotId !== snapshotId
        || snapshotDialogContext.value?.datasourceId !== datasourceIdAtOpen
      ) return
      auditError.value = getHttpErrorMessage(cause, '操作记录读取失败')
      auditState.value = 'error'
    }
  })()
}

function retryAuditRecords() {
  loadAuditRecords(true)
}

function changeAuditPage(nextPage: number) {
  auditPage.value = nextPage
  loadAuditRecords(true)
}

function resetIssuePresence() {
  issuePresenceRequestId += 1
  issuePresenceState.value = 'idle'
  pendingIssueCount.value = null
  issuePresenceError.value = ''
}

function loadPendingIssuePresence() {
  const detail = snapshotDialogDetail.value
  if (!snapshotDialogVisible.value || !detail) return
  const snapshotId = detail.snapshot.id
  const datasourceIdAtOpen = detail.snapshot.datasourceId
  if (!hasDatasourceFunction(GOVERNANCE_ISSUE_VIEW_FUNCTION, datasourceIdAtOpen)) {
    resetIssuePresence()
    return
  }

  const currentRequest = ++issuePresenceRequestId
  issuePresenceState.value = 'loading'
  pendingIssueCount.value = null
  issuePresenceError.value = ''
  void (async () => {
    try {
      const results = await Promise.all(PENDING_ISSUE_STATUSES.map((status) =>
        listQualityIssues(snapshotId, { status, page: 1, size: 1 }),
      ))
      if (
        disposed
        || currentRequest !== issuePresenceRequestId
        || !snapshotDialogVisible.value
        || snapshotDialogDetail.value?.snapshot.id !== snapshotId
        || snapshotDialogDetail.value.snapshot.datasourceId !== datasourceIdAtOpen
      ) return
      pendingIssueCount.value = results.reduce((sum, result) => sum + (result.data.total || 0), 0)
      issuePresenceState.value = 'success'
    } catch (cause) {
      if (currentRequest !== issuePresenceRequestId || snapshotDialogDetail.value?.snapshot.id !== snapshotId) return
      issuePresenceError.value = getHttpErrorMessage(cause, '待处理治理问题读取失败')
      issuePresenceState.value = 'error'
    }
  })()
}

function openGovernance() {
  const snapshot = snapshotDialogDetail.value?.snapshot
  if (!snapshot || !hasDatasourceFunction(GOVERNANCE_VIEW_FUNCTION, snapshot.datasourceId)) return
  void router.push({
    path: '/admin/governance',
    query: {
      datasourceId: String(snapshot.datasourceId),
      snapshotId: String(snapshot.id),
      returnTo: '/admin/releases',
    },
  })
}

const dialogGateIssueTarget = computed(() => {
  const source = snapshotDialogContext.value
  if (!source) return undefined
  return {
    path: '/admin/governance/issues',
    query: { datasourceId: String(source.datasourceId), snapshotId: String(source.snapshotId) },
  }
})

const publishGateIssueTarget = computed(() => {
  const item = publishTarget.value
  const sourceId = item ? rowDatasourceId(item) : undefined
  if (!item || !sourceId) return undefined
  return {
    path: '/admin/governance/issues',
    query: { datasourceId: String(sourceId), snapshotId: String(item.snapshotId) },
  }
})

function openIssueCenter() {
  const snapshot = snapshotDialogDetail.value?.snapshot
  if (!snapshot || !canShowIssueAction.value) return
  void router.push({
    path: '/admin/governance/issues',
    query: {
      datasourceId: String(snapshot.datasourceId),
      snapshotId: String(snapshot.id),
    },
  })
}

function openCompare(base: CompareVersionOption, compare: CompareVersionOption) {
  if (base.snapshotId === compare.snapshotId) {
    ElMessage.warning('请选择两个不同版本')
    return
  }
  if (
    !datasourceId.value
    || base.datasourceId !== compare.datasourceId
    || base.datasourceId !== datasourceId.value
  ) {
    ElMessage.warning('只能比较同一数据源中的版本')
    return
  }
  void router.push(`/admin/releases/snapshots/${base.snapshotId}/diff/${compare.snapshotId}`)
}

function compareSelectedVersions() {
  if (!canCompareSelected.value) return
  const [base, compare] = selectedVersions.value
  openCompare(base, compare)
}

function compareWithCurrentOfficial(row: VersionHistoryItem) {
  const official = published.value
  const rowOption = versionOption(row)
  const officialSourceId = official?.datasourceId ?? datasourceId.value
  if (!official?.snapshotId || !rowOption || !officialSourceId) return
  if (row.snapshotId === official.snapshotId) return
  openCompare({
    snapshotId: official.snapshotId,
    datasourceId: officialSourceId,
    snapshotVersion: official.snapshotVersion,
    status: official.status,
    datasourceName: official.datasourceName,
  }, rowOption)
}

function issueCenterTarget(row: VersionHistoryItem) {
  const sourceId = rowDatasourceId(row)
  return {
    path: '/admin/governance/issues',
    query: {
      ...(sourceId ? { datasourceId: String(sourceId) } : {}),
      snapshotId: String(row.snapshotId),
    },
  }
}

/**
 * 批准前读取门禁。来源高风险未解决时不进入确认框；读取失败不阻断前端，
 * 由后端在审核接口再次校验并返回 409。
 */
async function ensureApprovable(item: VersionHistoryItem): Promise<boolean> {
  try {
    const result = await getReleaseGate(item.snapshotId)
    const gate = result.data
    if (!gate || gate.approvable) {
      if (gate && gate.warningCount > 0) {
        ElMessage.warning(`门禁有 ${gate.warningCount} 项提示；语义问题不阻断发布`)
      }
      return true
    }
    const blockers = gate.items
      .filter((gateItem) => gateItem.blocksApproval && gateItem.status === 'FAIL')
      .map((gateItem) => gateItem.message)
    await ElMessageBox.alert(
      blockers.join('；') || '存在未解决的治理问题，暂不能批准。',
      '暂不能批准',
      { type: 'warning' },
    )
    return false
  } catch (cause) {
    ElMessage.warning(getHttpErrorMessage(cause, '发布门禁读取失败，审核时将由后端再次校验'))
    return true
  }
}

async function changeStatus(item: VersionHistoryItem, targetStatus: string) {
  const labels: Record<string, string> = { CHECKING: '开始质量检查', APPROVED: '批准快照' }
  if (actionLoading.value) return
  if (targetStatus === 'CHECKING' && !canRunQualityCheck(item)) return
  if (targetStatus === 'APPROVED' && !canReviewSnapshot(item)) return
  if (targetStatus === 'APPROVED' && !(await ensureApprovable(item))) return
  const confirmMessage = targetStatus === 'APPROVED'
    ? '确认批准快照 v' + item.snapshotVersion + '？批准后仍需通过发布门禁才能发布。'
    : '确认对快照 v' + item.snapshotVersion + '执行“' + labels[targetStatus] + '”？'
  try {
    await ElMessageBox.confirm(confirmMessage, '确认操作', { type: 'warning' })
    actionLoading.value = true
    if (targetStatus === 'CHECKING') {
      const result = await triggerQualityCheck(item.snapshotId)
      ElMessage.success(`质量校验完成，综合得分 ${result.data?.qualityScore}`)
    } else {
      await changeSnapshotStatus(item.snapshotId, { targetStatus })
      ElMessage.success('快照状态已更新')
    }
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(getHttpErrorMessage(cause, '快照状态更新失败'))
  } finally {
    actionLoading.value = false
  }
}

function publish(item: VersionHistoryItem) {
  if (!canPublishSnapshot(item)) return
  publishTarget.value = item
  publishConfirmVisible.value = true
  void loadPublishGate(item.snapshotId)
}

function closePublishConfirm() {
  publishConfirmVisible.value = false
  publishTarget.value = null
  publishSubmitting.value = false
  clearPublishGate()
}

function retryPublishGate() {
  if (publishTarget.value) void loadPublishGate(publishTarget.value.snapshotId)
}

async function confirmPublish() {
  const item = publishTarget.value
  if (!item || publishSubmitting.value) return
  // 已知阻断时不提交；门禁读取失败仍允许提交，由后端 409 兜底。
  if (publishGate.value && publishGate.value.blockingCount > 0) return
  publishSubmitting.value = true
  try {
    await publishSnapshot(item.snapshotId)
    ElMessage.success('快照已发布')
    closePublishConfirm()
    await load()
    const openedFor = snapshotDialogContext.value
    if (snapshotDialogVisible.value && openedFor) {
      void loadDialogGate(openedFor.snapshotId)
    }
  } catch (cause) {
    ElMessage.error(getHttpErrorMessage(cause, '快照发布失败'))
  } finally {
    publishSubmitting.value = false
  }
}

async function revoke(item: VersionHistoryItem) {
  if (item.status !== 'PUBLISHED' || !canPublishSnapshot(item)) return
  try {
    const result = await ElMessageBox.prompt('请输入撤回原因', '撤回已发布快照', {
      inputValidator: (value) => Boolean(value?.trim()) || '撤回原因不能为空',
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
    })
    actionLoading.value = true
    await revokeSnapshot(item.snapshotId, result.value)
    ElMessage.success('快照已撤回')
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(getHttpErrorMessage(cause, '快照撤回失败'))
  } finally {
    actionLoading.value = false
  }
}

function auditActionLabel(action: string) {
  const labels: Record<string, string> = {
    STATUS_TRANSITION: '状态流转',
    PUBLISH: '发布',
    REVOKE: '撤回',
    EXPIRE: '过期',
  }
  return labels[action] || action
}

function releaseToKnowledge() {
  if (!datasourceId.value) return { path: '/admin/semantics/knowledge' }
  return {
    path: '/admin/semantics/knowledge',
    query: buildContextQuery('datasource', { datasourceId: datasourceId.value }),
  }
}

watch(datasourceId, (next, previous) => {
  if (next === previous) return
  page.value = 1
  selectedVersions.value = []
  if (snapshotDialogVisible.value) closeSnapshotDialog()
  void load()
})

watch(activeDetailTab, (tab) => {
  if (tab === 'operations') loadAuditRecords()
})

watch([snapshotDialogVisible, auditPage], ([visible]) => {
  if (visible && activeDetailTab.value === 'operations') loadAuditRecords()
}, { flush: 'post' })

watch([snapshotDialogVisible, () => snapshotDialogDetail.value?.snapshot.id], ([visible, snapshotId]) => {
  if (!visible || !snapshotId) {
    resetIssuePresence()
    return
  }
  loadPendingIssuePresence()
}, { flush: 'post' })

watch(snapshotDialogVisible, (visible) => {
  if (visible) return
  snapshotDialogContext.value = null
  activeDetailTab.value = 'content'
  auditPage.value = 1
  resetAuditRecords()
  resetIssuePresence()
  clearSnapshotDialogDetail()
}, { flush: 'sync' })

onMounted(async () => {
  try {
    await Promise.all([context.initialize(), iamS1.load()])
    await load()
  } catch (cause) {
    const message = getHttpErrorMessage(cause, '数据源范围加载失败')
    listError.value = message
    publishedError.value = message
    listLoading.value = false
    publishedLoading.value = false
  }
})

onBeforeUnmount(() => {
  disposed = true
  listRequestId += 1
  auditRequestId += 1
  issuePresenceRequestId += 1
  clearSnapshotDialogDetail()
})
</script>

<template>
  <main class="admin-page releases-page">
    <TaskPageHeader
      title="元数据版本"
      description="查看元数据快照、当前正式版和版本间的结构变化。发布、撤回、问题处理与知识发布各自独立。"
    >
      <template #actions>
        <div class="releases-page__source-controls">
          <ScopeBar mode="datasource" refresh-label="刷新数据源列表" compact />
        </div>
      </template>
    </TaskPageHeader>

    <section class="releases-page__current" aria-label="当前正式版">
      <div class="releases-page__current-label">
        <span>当前正式版</span>
        <strong v-if="published && !publishedLoading && !publishedError">v{{ published.snapshotVersion }}</strong>
      </div>
      <div class="releases-page__current-main">
        <span v-if="!datasourceId" class="releases-page__current-state">请先选择数据源</span>
        <span v-else-if="publishedLoading" class="releases-page__current-state" role="status">正在读取当前正式版…</span>
        <div v-else-if="publishedError" class="releases-page__current-error" role="alert">
          <span>{{ publishedError }}</span>
          <el-button link type="primary" @click="load">重试</el-button>
        </div>
        <span v-else-if="!published" class="releases-page__current-state">当前没有正式版</span>
        <template v-else>
          <BusinessStatusBadge :status="published.status" :label="snapshotStatusLabel(published.status)" />
          <time v-if="published.publishedAt" :datetime="published.publishedAt" :title="published.publishedAt">
            发布时间 {{ formatVersionTime(published.publishedAt) }}
          </time>
          <span v-if="!published.publishedAt" class="releases-page__current-state">接口未提供发布时间</span>
        </template>
      </div>
      <RouterLink v-if="published && !publishedLoading && !publishedError" class="releases-page__knowledge-link" :to="releaseToKnowledge()">
        生成语义知识
      </RouterLink>
    </section>

    <section class="releases-page__workspace" aria-label="元数据版本列表">
      <div class="releases-page__toolbar">
        <div class="releases-page__toolbar-heading">
          <h2>版本列表</h2>
          <span v-if="!listLoading && !listError">{{ total.toLocaleString('zh-CN') }} 个版本</span>
        </div>
        <div class="releases-page__toolbar-actions">
          <el-button :icon="RefreshCw" :loading="listLoading || publishedLoading" @click="load">刷新版本</el-button>
          <div class="releases-page__compare-controls" aria-live="polite">
            <span v-if="selectedVersions.length === 2 && !canCompareSelected" class="releases-page__compare-status releases-page__compare-invalid" role="alert">{{ compareSelectionReason }}</span>
            <span v-else-if="compareDirectionLabel" class="releases-page__compare-status releases-page__compare-direction" role="status">{{ compareDirectionLabel }}</span>
            <span v-else class="releases-page__compare-status" role="status">{{ compareSelectionReason }}</span>
            <el-button type="primary" :disabled="!canCompareSelected" @click="compareSelectedVersions">
              <GitCompareArrows :size="15" aria-hidden="true" />比较版本
            </el-button>
            <el-button v-if="selectedVersions.length" link type="info" @click="clearCompareSelection">清除选择</el-button>
          </div>
        </div>
      </div>

      <div v-if="!datasourceId" class="releases-page__list-state">
        <EmptyState message="请先选择数据源，再查看元数据版本。" />
      </div>
      <div v-else-if="listLoading" class="releases-page__list-state">
        <LoadingState variant="skeleton" :rows="6" text="正在读取版本列表…" />
      </div>
      <div v-else-if="listError" class="releases-page__list-state">
        <ErrorState :message="listError" @retry="load" />
      </div>
      <div v-else-if="history.length" class="releases-page__table-region">
        <el-table v-loading="actionLoading" :data="history" class="releases-page__table" row-key="snapshotId">
          <el-table-column label="比较" width="54" align="center">
            <template #default="{ row }">
              <el-checkbox
                :model-value="isSelectedForCompare(row.snapshotId)"
                :disabled="!canSelectForCompare(row)"
                :aria-label="`选择版本 v${row.snapshotVersion} 参与比较`"
                @change="(value: unknown) => toggleCompareSelection(row, value)"
              />
            </template>
          </el-table-column>
          <el-table-column label="版本" min-width="160">
            <template #default="{ row }">
              <div class="release-version">
                <el-button link type="primary" class="release-version__link" :aria-label="`查看版本 v${row.snapshotVersion} 详情`" @click="openSnapshotDialog(row)">
                  v{{ row.snapshotVersion }}
                </el-button>
                <span v-if="isCurrentOfficial(row)" class="release-current-tag">当前正式版</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="发布状态" width="126">
            <template #default="{ row }"><BusinessStatusBadge :status="row.status" :label="snapshotStatusLabel(row.status)" /></template>
          </el-table-column>
          <el-table-column label="表数" width="92" align="right">
            <template #default="{ row }"><span class="release-count">{{ formatCount(row.tableCount) }}</span></template>
          </el-table-column>
          <el-table-column label="字段数" width="92" align="right">
            <template #default="{ row }"><span class="release-count">{{ formatCount(row.columnCount) }}</span></template>
          </el-table-column>
          <el-table-column label="质量分" width="82" align="right">
            <template #default="{ row }"><span class="release-quality-score">{{ row.qualityScore == null ? '未校验' : row.qualityScore }}</span></template>
          </el-table-column>
          <el-table-column label="采集 / 发布时间" width="210">
            <template #default="{ row }">
              <div class="release-time-cell">
                <time v-if="row.createdAt" :datetime="row.createdAt" :title="row.createdAt">采集 {{ formatVersionTime(row.createdAt) }}</time>
                <time v-if="row.publishedAt" :datetime="row.publishedAt" :title="row.publishedAt">发布 {{ formatVersionTime(row.publishedAt) }}</time>
                <span v-if="!row.createdAt && !row.publishedAt">未提供</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column v-if="hasReviewerNames" prop="reviewedBy" label="审校人" width="150" show-overflow-tooltip />
          <el-table-column v-if="hasVisibleRowActions" label="操作" :width="actionsColumnWidth" fixed="right">
            <template #default="{ row }">
              <div class="release-row-actions">
                <el-button v-if="canViewAuditRecords(row)" link class="release-row-actions__audit" @click="openSnapshotDialog(row, 'operations')">操作记录</el-button>
                <el-button v-if="row.status === 'DRAFT' && canRunQualityCheck(row)" link type="primary" @click="changeStatus(row, 'CHECKING')">开始检查</el-button>
                <RouterLink v-if="row.status === 'ISSUE_FOUND' && canViewIssuesForRow(row)" class="releases-page__issue-link" :to="issueCenterTarget(row)">进入问题中心</RouterLink>
                <el-button v-if="row.status === 'ISSUE_FOUND' && canReviewSnapshot(row)" link @click="changeStatus(row, 'APPROVED')">批准</el-button>
                <el-button v-if="row.status === 'APPROVED' && canPublishSnapshot(row)" link type="primary" @click="publish(row)"><Send :size="14" aria-hidden="true" />发布</el-button>
                <el-button v-if="row.status === 'PUBLISHED' && canPublishSnapshot(row)" link type="danger" @click="revoke(row)"><RotateCcw :size="14" aria-hidden="true" />撤回</el-button>
                <el-button v-if="canCompareWithCurrent(row)" link type="primary" @click="compareWithCurrentOfficial(row)">与当前正式版比较</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else class="releases-page__list-state">
        <EmptyState message="当前数据源还没有元数据版本。" action-text="去采集任务" @action="router.push({ path: '/admin/collections', query: buildContextQuery('datasource', { datasourceId }) })" />
      </div>

      <el-pagination
        v-if="!listLoading && !listError && total > pageSize"
        class="releases-page__pagination"
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="changePage"
      />
    </section>

    <el-dialog
      v-model="snapshotDialogVisible"
      class="metadata-version-dialog"
      width="min(960px, calc(100vw - 32px))"
      top="10vh"
      append-to-body
      destroy-on-close
      :close-on-click-modal="true"
      :close-on-press-escape="true"
    >
      <template #header="{ titleId, titleClass }">
        <div class="metadata-version-dialog__header">
          <div class="metadata-version-dialog__title-row">
            <h2 :id="titleId" :class="titleClass">{{ dialogTitle }}</h2>
            <BusinessStatusBadge v-if="dialogStatus" :status="dialogStatus" :label="snapshotStatusLabel(dialogStatus)" />
            <span v-if="dialogIsCurrentOfficial" class="release-current-tag">当前正式版</span>
          </div>
          <div v-if="dialogDatasourceName" class="metadata-version-dialog__source">
            <span>数据源</span>
            <strong :title="dialogDatasourceName" tabindex="0">{{ dialogDatasourceName }}</strong>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeDetailTab" class="metadata-version-dialog__tabs">
        <el-tab-pane label="版本内容" name="content">
          <SnapshotDetailPanel
            :detail="snapshotDialogDetail"
            :loading="snapshotDialogLoading"
            :error="snapshotDialogError"
            :show-datasource="false"
            @retry="retrySnapshotDialogDetail"
          />
        </el-tab-pane>
        <el-tab-pane v-if="canViewDialogOperations" label="操作记录" name="operations">
          <div class="metadata-version-dialog__audit">
            <LoadingState v-if="auditState === 'loading'" text="正在读取操作记录…" />
            <ErrorState v-else-if="auditState === 'error'" :message="auditError" @retry="retryAuditRecords" />
            <template v-else-if="auditState === 'success' && auditRecords.length">
              <el-table :data="auditRecords" class="metadata-version-dialog__audit-table" size="small">
                <el-table-column label="操作" min-width="130">
                  <template #default="{ row }"><strong>{{ auditActionLabel(row.action) }}</strong></template>
                </el-table-column>
                <el-table-column prop="operatorName" label="操作人" min-width="140" show-overflow-tooltip />
                <el-table-column prop="reason" label="原因" min-width="240" show-overflow-tooltip />
                <el-table-column label="时间" min-width="180">
                  <template #default="{ row }"><time :datetime="row.createdAt" :title="row.createdAt">{{ formatVersionTime(row.createdAt) }}</time></template>
                </el-table-column>
              </el-table>
              <el-pagination
                v-if="auditTotal > auditPageSize"
                class="metadata-version-dialog__audit-pagination"
                background
                layout="total, prev, pager, next"
                :total="auditTotal"
                :page-size="auditPageSize"
                :current-page="auditPage"
                @current-change="changeAuditPage"
              />
            </template>
            <EmptyState v-else-if="auditState === 'success'" message="暂无操作记录" />
          </div>
        </el-tab-pane>
      </el-tabs>

      <ReleaseGatePanel
        v-if="canViewDialogGate"
        class="metadata-version-dialog__gate"
        :gate="dialogGate"
        :loading="dialogGateLoading"
        :error="dialogGateError"
        compact
        :issue-target="dialogGateIssueTarget"
        @retry="retryDialogGate"
      />

      <div v-if="snapshotDialogDetail" class="metadata-version-dialog__governance-status">
        <span v-if="issuePresenceState === 'loading'" role="status">正在核对待处理问题…</span>
        <span v-else-if="issuePresenceState === 'error'" class="metadata-version-dialog__issue-error" role="alert">
          {{ issuePresenceError }}
          <el-button link type="primary" @click="loadPendingIssuePresence">重试</el-button>
        </span>
      </div>
      <template #footer>
        <div class="metadata-version-dialog__footer">
          <div class="metadata-version-dialog__footer-links">
            <el-button v-if="canViewDialogGovernance && snapshotDialogDetail" link type="info" @click="openGovernance">查看治理情况</el-button>
            <el-button
              v-if="canShowIssueAction"
              :type="shouldEmphasizeIssueAction ? 'primary' : 'info'"
              :link="!shouldEmphasizeIssueAction"
              @click="openIssueCenter"
            >{{ shouldEmphasizeIssueAction ? `处理待处理问题（${pendingIssueCount}）` : `查看质量问题（${pendingIssueCount}）` }}</el-button>
          </div>
          <el-button :type="shouldEmphasizeIssueAction ? 'default' : 'primary'" @click="closeSnapshotDialog">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="publishConfirmVisible"
      class="publish-gate-dialog"
      width="min(640px, calc(100vw - 32px))"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template #header>
        <h2 class="publish-gate-dialog__title">
          发布快照 v{{ publishTarget?.snapshotVersion }}
        </h2>
      </template>
      <ReleaseGatePanel
        :gate="publishGate"
        :loading="publishGateLoading"
        :error="publishGateError"
        :issue-target="publishGateIssueTarget"
        @retry="retryPublishGate"
      />
      <p class="publish-gate-dialog__hint">
        发布后同一数据源的旧已发布版本会自动过期。门禁会在后端再次校验，未通过时发布会被拒绝。
      </p>
      <template #footer>
        <el-button @click="closePublishConfirm">取消</el-button>
        <el-button
          type="primary"
          :loading="publishSubmitting"
          :disabled="publishGateLoading || (publishGate?.blockingCount ?? 0) > 0"
          @click="confirmPublish"
        >确认发布</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.releases-page {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16px;
  min-width: 0;
  min-height: 0;
  padding: 0;
  color: var(--do-ink);
  background: transparent;
}
.releases-page :deep(.task-page-header) { align-items: center; flex-wrap: wrap; gap: 12px 20px; margin-bottom: 0; }
.releases-page :deep(.task-page-header__actions) { flex: 0 0 auto; min-width: 0; justify-content: flex-end; }
.releases-page :deep(.task-page-header h1) { font-size: clamp(24px, 2.1vw, 28px); line-height: 1.2; letter-spacing: -.02em; }
.releases-page__source-controls { display: flex; flex: 0 0 auto; align-items: center; flex-wrap: nowrap; min-width: 0; }
.releases-page__source-controls :deep(.scope-bar--compact .scope-bar__datasource-control) { flex: 0 1 488px; }
.releases-page__source-controls :deep(.scope-bar__refresh-tooltip) { margin-left: 8px; }

.releases-page__current {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 20px;
  min-width: 0;
  padding: 16px 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-surface);
}
.releases-page__current-label { display: flex; align-items: center; gap: 12px; }
.releases-page__current-label > span { color: var(--do-muted); font-size: 13px; }
.releases-page__current-label > strong { color: var(--do-ink); font-size: 18px; font-weight: 700; }
.releases-page__current-main { display: flex; flex: 1 1 auto; align-items: center; flex-wrap: wrap; gap: 8px 16px; min-width: 0; color: var(--do-muted); font-size: 13px; }
.releases-page__current-main time { color: var(--do-ink); }
.releases-page__current-state { color: var(--do-muted); }
.releases-page__current-error { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; color: var(--do-danger); }
.releases-page__knowledge-link { flex: 0 0 auto; margin-left: auto; color: var(--do-primary-strong); font-size: 13px; font-weight: 600; text-decoration: none; }
.releases-page__knowledge-link:hover { text-decoration: underline; }
.releases-page__knowledge-link:focus-visible { border-radius: 3px; outline: 2px solid color-mix(in srgb, var(--do-primary) 45%, transparent); outline-offset: 3px; }

.releases-page__workspace { min-width: 0; overflow: hidden; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.releases-page__toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px 18px; padding: 16px 18px; border-bottom: 1px solid var(--do-line); }
.releases-page__toolbar-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px 12px; }
.releases-page__toolbar-heading h2 { margin: 0; color: var(--do-ink); font-size: 16px; font-weight: 700; }
.releases-page__toolbar-heading > span { color: var(--do-muted); font-size: 12px; }
.releases-page__toolbar-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px 12px; min-width: 0; }
.releases-page__toolbar-actions :deep(.el-button) { min-height: 36px; }
.releases-page__compare-controls { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; min-width: 0; }
.releases-page__compare-status { flex: 0 1 auto; max-width: min(34vw, 320px); color: var(--do-muted); font-size: 12px; line-height: 1.4; }
.releases-page__compare-direction { color: var(--do-ink); font-weight: 600; }
.releases-page__compare-invalid { color: var(--do-danger); }
.releases-page__list-state { padding: 28px 18px; }
.releases-page__table-region { min-width: 0; max-width: 100%; overflow-x: auto; padding: 0 18px; }
.releases-page__table :deep(.el-table) { width: 100%; min-width: 0; border: 0; color: var(--do-ink); background: var(--do-surface); }
.releases-page__table :deep(.el-table.is-scrolling-none .el-table-fixed-column--right::before) { box-shadow: none; }
.releases-page__table :deep(.el-table::before),
.releases-page__table :deep(.el-table__inner-wrapper::before) { display: none; }
.releases-page__table :deep(.el-table th.el-table__cell) { height: 44px; padding: 0; color: var(--do-muted); background: #f4f6f9; font-size: 12px; font-weight: 600; }
.releases-page__table :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 44px; }
.releases-page__table :deep(.el-table td.el-table__cell) { padding: 12px 0; border-bottom-color: #edf0f4; font-size: 14px; vertical-align: middle; }
.releases-page__table :deep(.el-table td.el-table__cell .cell) { padding: 0 12px; line-height: 1.4; }
.releases-page__table :deep(.el-table tr:last-child td.el-table__cell) { border-bottom: 0; }
.release-count { font-size: 12px; white-space: nowrap; }
.release-version { display: grid; justify-items: start; gap: 3px; }
.release-version__link { height: auto; padding: 0; color: var(--do-primary-strong); font-size: 15px; font-weight: 700; }
.release-version__link:hover { text-decoration: underline; }
.release-current-tag { display: inline-flex; align-items: center; min-height: 20px; padding: 1px 7px; border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-primary-soft); font-size: 11px; font-weight: 600; white-space: nowrap; }
.release-time-cell { display: grid; gap: 3px; color: var(--do-muted); font-size: 12px; line-height: 1.4; }
.release-time-cell time:last-child:not(:first-child) { color: var(--do-ink); }
.release-quality-score { color: var(--do-muted); font-size: 13px; }
.release-row-actions { display: flex; align-items: center; justify-content: flex-start; flex-wrap: nowrap; gap: 4px 16px; white-space: nowrap; }
.release-row-actions :deep(.el-button) { min-height: 30px; }
.release-row-actions :deep(.release-row-actions__audit) {
  --el-button-text-color: var(--do-muted);
  --el-button-hover-link-text-color: var(--do-primary-strong);
  --el-button-active-color: var(--do-primary-strong);
}
.release-row-actions :deep(.release-row-actions__audit:not(.is-disabled):is(:hover, :focus-visible)) {
  color: var(--do-primary-strong);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.release-row-actions :deep(.el-button--danger.is-link) { font-weight: 500; }
.releases-page__issue-link { color: var(--do-primary-strong); font-size: 13px; font-weight: 600; white-space: nowrap; }
.releases-page__issue-link:hover { text-decoration: underline; }
.releases-page__pagination { justify-content: flex-end; margin: 14px 18px 16px; }

:global(.metadata-version-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  width: min(960px, calc(100vw - 32px));
  max-width: calc(100vw - 24px);
  max-height: 80vh;
  margin-top: 10vh !important;
  overflow: hidden;
  border: 1px solid #e1e5eb;
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow-lg);
}
:global(.metadata-version-dialog .el-dialog__header) { flex: 0 0 auto; margin-right: 0; padding: 16px 56px 14px 20px; border-bottom: 1px solid #e8ebf0; background: var(--do-surface); }
:global(.metadata-version-dialog .el-dialog__headerbtn) { top: 10px; right: 12px; width: 36px; height: 36px; border-radius: var(--do-radius-sm); }
:global(.metadata-version-dialog .el-dialog__headerbtn:focus-visible) { outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 1px; }
:global(.metadata-version-dialog .el-dialog__body) { flex: 1 1 auto; min-height: 0; overflow: auto; padding: 0 20px 18px; background: var(--do-surface); }
:global(.metadata-version-dialog .el-dialog__footer) { flex: 0 0 auto; padding: 12px 20px 16px; border-top: 1px solid #e8ebf0; background: var(--do-surface); }
.metadata-version-dialog__header { display: grid; gap: 8px; min-width: 0; }
.metadata-version-dialog__title-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 10px; min-width: 0; }
.metadata-version-dialog__title-row h2 { margin: 0; color: var(--do-ink); font-size: 18px; font-weight: 700; line-height: 1.35; }
.metadata-version-dialog__source { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 10px; color: var(--do-muted); font-size: 12px; }
.metadata-version-dialog__source strong { color: var(--do-ink); font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.metadata-version-dialog__source strong:focus-visible { border-radius: 3px; outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 3px; }
.metadata-version-dialog__tabs { min-width: 0; }
.metadata-version-dialog__tabs :deep(.el-tabs__header) { margin: 0 0 14px; }
.metadata-version-dialog__tabs :deep(.el-tabs__content) { min-width: 0; }
.metadata-version-dialog__audit { min-height: 80px; }
.metadata-version-dialog__audit-table :deep(.el-table) { min-width: 720px; border: 0; background: var(--do-surface); }
.metadata-version-dialog__audit-table :deep(.el-table::before),
.metadata-version-dialog__audit-table :deep(.el-table__inner-wrapper::before) { display: none; }
.metadata-version-dialog__audit-table :deep(.el-table th.el-table__cell) { height: 40px; padding: 0; color: var(--do-muted); background: #f4f6f9; font-size: 12px; }
.metadata-version-dialog__audit-table :deep(.el-table td.el-table__cell) { padding: 11px 0; border-bottom-color: #edf0f4; font-size: 14px; }
.metadata-version-dialog__audit-table :deep(.el-table td.el-table__cell .cell),
.metadata-version-dialog__audit-table :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 1.4; }
.metadata-version-dialog__audit-pagination { justify-content: flex-end; margin-top: 12px; }
.metadata-version-dialog__governance-status { min-height: 18px; margin-top: 12px; color: var(--do-muted); font-size: 12px; }
.metadata-version-dialog__issue-error { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 10px; color: var(--do-danger); }
.metadata-version-dialog__gate { margin-top: 12px; }
.metadata-version-dialog__footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
.metadata-version-dialog__footer-links { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }

.publish-gate-dialog__title { margin: 0; font-size: 16px; color: var(--do-ink); }
.publish-gate-dialog__hint { margin: 12px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }

@media (max-width: 900px) {
  .releases-page :deep(.task-page-header__actions) { flex: 1 1 100%; width: 100%; justify-content: flex-start; }
  .releases-page__source-controls { flex: 1 1 100%; width: 100%; }
  .releases-page__source-controls :deep(.scope-bar--compact .scope-bar__datasource-control) { flex: 1 1 0; min-width: 0; max-width: none; }
  .releases-page__source-controls :deep(.scope-bar--compact .scope-bar__datasource-control .el-select) { flex: 1 1 0; width: auto; min-width: 0; }
}

@media (max-width: 760px) {
  .releases-page { gap: 12px; }
  .releases-page__current { align-items: flex-start; gap: 8px 12px; padding: 12px; }
  .releases-page__current-main { flex-basis: 100%; }
  .releases-page__knowledge-link { margin-left: 0; }
  .releases-page__toolbar { gap: 8px; padding: 12px; }
  .releases-page__toolbar-actions,
  .releases-page__compare-controls { justify-content: flex-start; }
  .releases-page__compare-status { max-width: 100%; }
  .releases-page__table-region { padding: 0 12px; }
  .releases-page__list-state { padding: 20px 12px; }
  :global(.metadata-version-dialog.el-dialog) { width: calc(100vw - 20px); max-width: calc(100vw - 20px); max-height: 80vh; margin-top: 10vh !important; }
  :global(.metadata-version-dialog .el-dialog__header) { padding: 14px 50px 12px 14px; }
  :global(.metadata-version-dialog .el-dialog__body) { padding: 0 12px 14px; }
  :global(.metadata-version-dialog .el-dialog__footer) { padding: 10px 12px 12px; }
}

@media (min-width: 761px) and (max-width: 900px) {
  .releases-page__current { gap: 10px 16px; }
}
</style>
