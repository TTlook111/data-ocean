<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GitCompareArrows, RefreshCw, RotateCcw, Send } from 'lucide-vue-next'
import { useRouter, useRoute } from 'vue-router'
import { pendingReleaseWrites, trackReleaseWrite } from '../../../composables/releaseWriteTracker'
import { useAuthStore } from '../../../stores/auth'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
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
const route = useRoute(), auth = useAuthStore()
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
const compactTable=ref(window.innerWidth<900)
function measureTable() { compactTable.value=window.innerWidth<900 }
window.addEventListener('resize',measureTable)
const scopeError = computed(() => {
  if (iamS1.errorMessage) return `权限摘要读取失败：${iamS1.errorMessage}`
  const raw = route.query.datasourceId
  if (raw != null && (typeof raw !== 'string' || !Number.isSafeInteger(Number(raw)) || Number(raw) <= 0 || Number(raw) !== datasourceId.value)) return '指定数据源尚未核验，未改用其他范围。'
  if (iamS1.loaded && datasourceId.value && !iamS1.canOnDatasource('metadata:release:view', datasourceId.value)) return '没有当前数据源的元数据版本查看权限。'
  return ''
})
const scopePending=computed(()=>!iamS1.loaded && !iamS1.errorMessage || context.loading || context.routeSyncing)
const scopeReady = computed(() => iamS1.loaded && !context.loading && !context.routeSyncing && !scopeError.value && !!datasourceId.value)
const actionError = ref(''), actionNotice = ref(''), revokeReason = ref('')
const uncertainWrites = pendingReleaseWrites
const uncertainWrite = computed(() => uncertainWrites.value.find(x => x.identity===auth.token && x.source===datasourceId.value))
const unknownAction = computed(() => !!uncertainWrite.value)
const verifyingWrite = ref(false)
async function verifyUncertainWrite() {
  const item=uncertainWrite.value, scope=ticket()
  if (!item || verifyingWrite.value || !hasDatasourceFunction(RELEASE_VIEW_FUNCTION,item.source)) return
  verifyingWrite.value=true
  try {
    const result=await listSnapshotAuditLogs(item.snapshot,{page:1,size:20})
    if (!current(scope) || uncertainWrite.value!==item) return
    const found=result.data.records?.some(row=>item.baselineAuditId!=null && row.id>item.baselineAuditId && (item.action==='publish' ? row.action==='PUBLISH' : item.action==='revoke' ? row.action==='REVOKE' : item.action==='APPROVED' || item.action==='CHECKING' ? row.action==='STATUS_TRANSITION' && row.newStatus===item.action : false))
    if (found) { uncertainWrites.value=uncertainWrites.value.filter(x=>x!==item); actionNotice.value='精确快照审计中已观察到目标操作；不再重复写入。该事件不证明原请求的唯一归属。'; await load() }
    else actionNotice.value='已重读精确快照审计，尚不能确认原请求结果；没有新记录不证明未执行，继续停止重发。'
  } catch(cause) {if(current(scope)) actionNotice.value=getHttpErrorMessage(cause,'精确审计核对失败，继续停止重发')}
  finally {if(current(scope)) verifyingWrite.value=false}
}
const confirmVisible = ref(false), confirmClass = `release-confirm-${Math.random().toString(36).slice(2)}`
let scopeRevision = 0, actionOperation = 0, publishOperation = 0, confirmOperation = 0
function closeOwnedConfirm() { confirmOperation++; document.querySelector<HTMLButtonElement>(`.${confirmClass}`)?.click(); confirmVisible.value = false }
function ticket() { return { revision: scopeRevision, source: datasourceId.value, identity:auth.token } }
function current(t: ReturnType<typeof ticket>) { return !disposed && t.revision === scopeRevision && t.source === datasourceId.value && scopeReady.value }
async function writeTracked(item: VersionHistoryItem, action: string, scope: ReturnType<typeof ticket>, allowed: () => boolean, write: () => Promise<unknown>) {
  let baselineAuditId: number | undefined
  if (hasDatasourceFunction(RELEASE_VIEW_FUNCTION,rowDatasourceId(item))) {
    try { const audit=await listSnapshotAuditLogs(item.snapshotId,{page:1,size:20}); baselineAuditId=Math.max(0,...audit.data.records.map(x=>x.id)) } catch { /* Missing baseline cannot later prove an uncertain result. */ }
  }
  if (!current(scope) || !currentRow(item) || !allowed()) throw 'cancel'
  return trackReleaseWrite({identity:scope.identity,source:scope.source!,snapshot:item.snapshotId,action,started:Date.now(),baselineAuditId},write)
}
function currentRow(item: VersionHistoryItem) { return scopeReady.value && rowDatasourceId(item) === datasourceId.value && history.value.some(row => row.snapshotId === item.snapshotId && row.status === item.status) }
async function ownedConfirm(message: string, title: string) {
  const operation=++confirmOperation; confirmVisible.value = true
  try { await ElMessageBox.confirm(message, title, { type:'warning', cancelButtonClass:confirmClass }) }
  finally { if(operation===confirmOperation) confirmVisible.value = false }
}
function mutationError(cause: unknown, fallback: string) {
  const status = (cause as {response?:{status?:number}})?.response?.status
  const uncertain=!status || status>=500 || status===408
  actionError.value = getHttpErrorMessage(cause, fallback)
  ElMessage.error(actionError.value)
  actionNotice.value = uncertain ? '操作结果待确认；请刷新版本并查看该快照操作记录核实，未核实前不会重发。' : status === 409 ? '发生并发冲突，请重读当前版本；已填写原因保留，不会自动重试。' : '服务端拒绝了本次操作，请核对原因。'
}


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
useAdminScrollLock(() => snapshotDialogVisible.value || publishConfirmVisible.value || confirmVisible.value)
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
  if (row.status === 'DRAFT' && canReviewSnapshot(row)) widths.push(textWidth('进入审核'))
  if (row.status === 'ISSUE_FOUND' && canViewIssuesForRow(row)) widths.push(textWidth('进入问题中心', 13))
  if (['CHECKING', 'ISSUE_FOUND'].includes(row.status) && canReviewSnapshot(row)) widths.push(textWidth('批准'))
  if (row.status === 'APPROVED' && canPublishSnapshot(row)) widths.push(textWidth('发布', 14, 19))
  if (row.status === 'PUBLISHED' && canPublishSnapshot(row)) widths.push(textWidth('撤回', 14, 19))
  if (canCompareWithCurrent(row)) widths.push(textWidth('与当前正式版比较'))

  return widths
}

const hasVisibleRowActions = computed(() => history.value.some((row) => rowActionWidths(row).length > 0))
const actionsColumnWidth = computed(() => 300)

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
  const scope = ticket()
  if (!scopeReady.value || !sourceId) {
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
    if (!current(scope) || currentRequest !== listRequestId) return

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
  if (!currentRow(item) || !sourceId || !canViewAuditRecords(item)) return

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
  if (!scopeReady.value || !snapshotDialogVisible.value || !openedFor) return
  if (!hasDatasourceFunction('metadata:view', openedFor.datasourceId)) { clearSnapshotDialogDetail(); return }
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
  if (!source || !iamS1.canOnDatasource('governance:issue:view',source.datasourceId)) return undefined
  return {
    path: '/admin/governance/issues',
    query: { datasourceId: String(source.datasourceId), snapshotId: String(source.snapshotId) },
  }
})

const publishGateIssueTarget = computed(() => {
  const item = publishTarget.value
  const sourceId = item ? rowDatasourceId(item) : undefined
  if (!item || !sourceId || !iamS1.canOnDatasource('governance:issue:view',sourceId)) return undefined
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
async function ensureApprovable(item: VersionHistoryItem, scope: ReturnType<typeof ticket>): Promise<boolean> {
  // Gate is an auxiliary release:view read; a reviewer without it does not borrow that permission.
  if (!hasDatasourceFunction(RELEASE_VIEW_FUNCTION, rowDatasourceId(item))) return true
  try {
    const result = await getReleaseGate(item.snapshotId)
    if (!current(scope)) return false
    const gate = result.data
    if (!gate || gate.snapshotId !== item.snapshotId || (gate.datasourceId != null && gate.datasourceId !== scope.source)) throw new Error('门禁范围与当前快照不一致')
    if (gate.approvable) return true
    actionError.value = gate.items.filter(x => x.blocksApproval && x.status === 'FAIL').map(x => x.message).join('；') || '存在未解决的治理问题，暂不能批准。'
    const operation=++confirmOperation; confirmVisible.value=true
    try { await ElMessageBox.alert(actionError.value,'暂不能批准',{type:'warning',confirmButtonClass:confirmClass}) } finally {if(operation===confirmOperation) confirmVisible.value=false}
    return false
  } catch (cause) {
    if (!current(scope)) return false
    ElMessage.warning(getHttpErrorMessage(cause, '门禁读取失败，审核时由后端再次校验'))
    return true
  }
}

async function runConfirmed(item: VersionHistoryItem, allowed: () => boolean, message: string, title: string, action: () => Promise<unknown>) {
  if (actionLoading.value || publishSubmitting.value || unknownAction.value || !currentRow(item) || !allowed()) return
  const scope = ticket(), operation = ++actionOperation
  actionLoading.value = true; actionError.value = ''; actionNotice.value = ''
  try {
    if (title === '批准快照' && !(await ensureApprovable(item, scope))) return
    if (!current(scope) || operation !== actionOperation) return
    await ownedConfirm(message, title)
    if (!current(scope) || operation !== actionOperation || !allowed() || !currentRow(item)) return
    await writeTracked(item,title==='批准快照' ? 'APPROVED' : title==='确认进入审核' ? 'CHECKING' : 'quality-check',scope,allowed,action)
    if (!current(scope) || operation !== actionOperation) return
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close' && current(scope) && operation === actionOperation) mutationError(cause, `${title}未完成`)
  } finally { if (current(scope) && operation === actionOperation) actionLoading.value = false }
}
async function enterReview(item: VersionHistoryItem) {
  if (item.status !== 'DRAFT') return
  return runConfirmed(item, () => canReviewSnapshot(item), `确认将快照 v${item.snapshotVersion} 进入人工审核？此操作不代表质量检查已完整完成，也不会自动批准或发布。`, '确认进入审核', async () => {
    await changeSnapshotStatus(item.snapshotId, {targetStatus:'CHECKING',reason:'人工进入审核'})
  })
}
async function changeStatus(item: VersionHistoryItem, targetStatus: string) {
  if (targetStatus === 'APPROVED') {
    if (!['CHECKING','ISSUE_FOUND'].includes(item.status)) return
    return runConfirmed(item, () => canReviewSnapshot(item), `确认批准快照 v${item.snapshotVersion}？批准后仍需独立发布。`, '批准快照', () => changeSnapshotStatus(item.snapshotId,{targetStatus}))
  }
  if (targetStatus !== 'CHECKING' || item.status !== 'DRAFT') return
  const scope=ticket()
  return runConfirmed(item, () => canRunQualityCheck(item), `确认对快照 v${item.snapshotVersion} 执行来源质量检查？这不等于人工审核或发布。`, '开始质量检查', async () => {
    const result=await triggerQualityCheck(item.snapshotId)
    if (!current(scope)) return
    const check=result.data
    if (check?.status==='SUCCESS' && check.complete) ElMessage.success(`质量校验已完整完成${check.requestedScope==='FULL' && check.qualityScore!=null ? `，综合得分 ${check.qualityScore}` : ''}。`)
    else if (check?.status==='PARTIAL' || check?.status==='SUCCESS') ElMessage.warning('质量校验部分完成；本次评分不作为完整结果。')
    else if (check?.executedRuleCount===0 && check.failedRuleCount===0 && check.notApplicableRuleCount>0) ElMessage.warning('本次检查没有可执行的适用项；未生成完整校验结果。')
    else ElMessage.error('质量校验未完成，请查看失败覆盖说明。')
  })
}
function publish(item: VersionHistoryItem) {
  if (actionLoading.value || publishSubmitting.value || unknownAction.value || item.status!=='APPROVED' || !currentRow(item) || !canPublishSnapshot(item)) return
  publishOperation++; publishTarget.value={...item}; publishConfirmVisible.value=true; actionError.value=''; actionNotice.value=''
  if (canViewAuditRecords(item)) void loadPublishGate(item.snapshotId)
}
function closePublishConfirm() {
  publishOperation++; publishConfirmVisible.value=false; publishTarget.value=null; publishSubmitting.value=false; clearPublishGate()
}
function retryPublishGate() { const item=publishTarget.value; if (item && currentRow(item) && canViewAuditRecords(item)) void loadPublishGate(item.snapshotId) }
async function confirmPublish() {
  const item=publishTarget.value
  if (!item || publishSubmitting.value || unknownAction.value || publishGateLoading.value || !currentRow(item) || item.status!=='APPROVED' || !canPublishSnapshot(item)) return
  if (publishGate.value && !publishGate.value.publishable) return
  const scope=ticket(), operation=++publishOperation
  publishSubmitting.value=true
  try {
    await writeTracked(item,'publish',scope,()=>canPublishSnapshot(item),()=>publishSnapshot(item.snapshotId))
    if (!current(scope) || operation!==publishOperation) return
    closePublishConfirm(); await load()
  } catch(cause) { if (current(scope) && operation===publishOperation) mutationError(cause,'快照发布未完成') }
  finally { if (current(scope) && operation===publishOperation) publishSubmitting.value=false }
}
async function revoke(item: VersionHistoryItem) {
  if (actionLoading.value || publishSubmitting.value || unknownAction.value || item.status!=='PUBLISHED' || !currentRow(item) || !canPublishSnapshot(item)) return
  const scope=ticket(),operation=++actionOperation
  actionLoading.value=true; actionError.value='';actionNotice.value='';confirmVisible.value=true
  try {
    const result=await ElMessageBox.prompt('请输入撤回原因','撤回已发布快照',{inputValue:revokeReason.value,inputValidator:value=>Boolean(value?.trim()) || '撤回原因不能为空',confirmButtonText:'确认撤回',cancelButtonText:'取消',cancelButtonClass:confirmClass})
    confirmVisible.value=false
    if (!current(scope) || operation!==actionOperation || !canPublishSnapshot(item) || !currentRow(item)) return
    revokeReason.value=result.value
    await writeTracked(item,'revoke',scope,()=>canPublishSnapshot(item),()=>revokeSnapshot(item.snapshotId,result.value))
    if (!current(scope) || operation!==actionOperation) return
    revokeReason.value=''; await load()
  } catch(cause) { if (cause!=='cancel' && cause!=='close' && current(scope) && operation===actionOperation) mutationError(cause,'快照撤回未完成') }
  finally { if (current(scope) && operation===actionOperation) {actionLoading.value=false;confirmVisible.value=false} }
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

watch(() => [auth.token, iamS1.snapshot, iamS1.loaded, iamS1.errorMessage, context.datasourceId, context.routeSyncing, context.loading, route.query.datasourceId], () => {
  scopeRevision++; listRequestId++; actionOperation++; publishOperation++
  closeOwnedConfirm(); closeSnapshotDialog(); closePublishConfirm(); clearSnapshotDialogDetail(); resetAuditRecords(); resetIssuePresence()
  page.value=1; selectedVersions.value=[]; history.value=[]; published.value=null; total.value=0; actionLoading.value=false
  actionError.value='';actionNotice.value='';revokeReason.value='';verifyingWrite.value=false
  void load()
}, {flush:'sync'})

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
  window.removeEventListener('resize',measureTable)
  scopeRevision++; actionOperation++; publishOperation++; closeOwnedConfirm(); clearDialogGate(); clearPublishGate()
  listRequestId += 1
  auditRequestId += 1
  issuePresenceRequestId += 1
  clearSnapshotDialogDetail()
})
</script>

<template>
  <main class="admin-page releases-page">
    <div class="releases-page__eyebrow">数据资产 / 版本与发布</div>
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

    <p class="releases-page__purpose">选择一个采集版本，核对结构与治理证据，再审核、发布为正式来源。</p>
    <ol class="release-journey" aria-label="元数据到问数的准备路径"><li><strong>01 核对采集</strong><span>比较结构、查看来源质量</span></li><li><strong>02 审核版本</strong><span>人工批准后才能发布</span></li><li><strong>03 发布快照</strong><span>成为当前正式元数据来源</span></li><li><strong>04 准备知识</strong><span>文档发布与索引构建另行确认</span></li></ol>
    <div v-if="scopeError" role="alert" class="release-action-notice">{{ scopeError }}</div>
    <section v-if="unknownAction" role="status" class="release-action-notice">快照 #{{ uncertainWrite?.snapshot }} 的 {{ uncertainWrite?.action }} 结果待确认。切换范围不会清除此记录；离开重进仍保留本次会话记录；整页刷新后仍需人工核对原目标，不可推定请求已取消。<el-button :loading="verifyingWrite" @click="verifyUncertainWrite">核对该快照操作记录</el-button><p>{{ actionNotice }}</p></section>
    <section v-if="actionError" class="release-action-notice" role="alert"><strong>{{ actionError }}</strong><p>{{ actionNotice }}</p><p v-if="revokeReason">已保留撤回原因：{{ revokeReason }}</p><el-button :loading="listLoading" @click="load">重读版本与当前正式版</el-button></section>
    <section class="releases-page__current" aria-label="当前正式版">
      <div class="releases-page__current-label">
        <span>当前正式版</span>
        <strong v-if="published && !publishedLoading && !publishedError">v{{ published.snapshotVersion }}</strong>
      </div>
      <div class="releases-page__current-main">
        <span v-if="scopePending" class="releases-page__current-state" role="status">正在核验账号与数据源范围…</span>
        <span v-else-if="scopeError" class="releases-page__current-state">当前正式版尚不可读取</span>
        <span v-else-if="!datasourceId" class="releases-page__current-state">请先选择数据源</span>
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
      <RouterLink v-if="published && !publishedLoading && !publishedError && iamS1.canOnDatasource('knowledge:view', datasourceId)" class="releases-page__knowledge-link" :to="releaseToKnowledge()">
        进入知识文档
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

      <div v-if="scopePending" class="releases-page__list-state"><LoadingState text="正在核验账号与数据源范围…" /></div>
      <div v-else-if="scopeError" class="releases-page__list-state"><p>版本列表尚不可读取，请核对上方范围或权限提示。</p></div>
      <div v-else-if="!datasourceId" class="releases-page__list-state">
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
          <el-table-column v-if="hasVisibleRowActions" label="操作" :width="actionsColumnWidth" :fixed="compactTable ? undefined : 'right'">
            <template #default="{ row }">
              <div class="release-row-actions">
                <el-button v-if="canViewAuditRecords(row)" link class="release-row-actions__audit" @click="openSnapshotDialog(row, 'operations')">操作记录</el-button>
                <el-button v-if="row.status === 'DRAFT' && canRunQualityCheck(row)" link type="primary" @click="changeStatus(row, 'CHECKING')">开始检查</el-button>
                <el-button v-if="row.status === 'DRAFT' && canReviewSnapshot(row)" link :disabled="actionLoading" @click="enterReview(row)">进入审核</el-button>
                <RouterLink v-if="row.status === 'ISSUE_FOUND' && canViewIssuesForRow(row)" class="releases-page__issue-link" :to="issueCenterTarget(row)">进入问题中心</RouterLink>
                <el-button v-if="['CHECKING', 'ISSUE_FOUND'].includes(row.status) && canReviewSnapshot(row)" link @click="changeStatus(row, 'APPROVED')">批准</el-button>
                <el-button v-if="row.status === 'APPROVED' && canPublishSnapshot(row)" link type="primary" @click="publish(row)"><Send :size="14" aria-hidden="true" />发布</el-button>
                <el-button v-if="row.status === 'PUBLISHED' && canPublishSnapshot(row)" link type="danger" @click="revoke(row)"><RotateCcw :size="14" aria-hidden="true" />撤回</el-button>
                <el-button v-if="canCompareWithCurrent(row)" link type="primary" @click="compareWithCurrentOfficial(row)">与当前正式版比较</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else class="releases-page__list-state">
        <EmptyState message="当前数据源还没有元数据版本。" :action-text="iamS1.canOnDatasource('metadata:collect:view', datasourceId) ? '去采集任务' : undefined" @action="router.push({ path: '/admin/collections', query: buildContextQuery('datasource', { datasourceId }) })" />
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
            v-if="dialogDatasourceId && hasDatasourceFunction('metadata:view', dialogDatasourceId)"
            :detail="snapshotDialogDetail"
            :loading="snapshotDialogLoading"
            :error="snapshotDialogError"
            :show-datasource="false"
            @retry="retrySnapshotDialogDetail"
          />
          <p v-else role="status">没有此源的 metadata:view，无法读取表字段结构；仍可查看操作记录与发布门禁。</p>
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
      :close-on-press-escape="!publishSubmitting"
      :show-close="!publishSubmitting"
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
      <p v-if="actionError" role="alert" class="release-action-notice">{{ actionError }} · {{ actionNotice }}</p>
      <p class="publish-gate-dialog__hint">
        发布后同一数据源的旧已发布版本会自动过期。门禁会在后端再次校验，未通过时发布会被拒绝。
      </p>
      <template #footer>
        <el-button :disabled="publishSubmitting" @click="closePublishConfirm">取消</el-button>
        <el-button
          type="primary"
          :loading="publishSubmitting"
          :disabled="unknownAction || publishGateLoading || (publishGate ? !publishGate.publishable : false)"
          @click="confirmPublish"
        >确认发布</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.releases-page__eyebrow { color:var(--do-primary-strong); font-size:12px; font-weight:650; margin-bottom:-6px; }
.releases-page__purpose { margin:-6px 0 2px; color:var(--do-muted); font-size:14px; }
.release-journey { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); list-style:none; padding:16px 0; margin:0; border-block:1px solid var(--do-line); gap:20px; }
.release-journey li { display:grid; gap:6px; padding-left:16px; border-left:2px solid var(--do-line); }
.release-journey li:first-child { border-color:var(--do-primary); }
.release-journey strong { font-size:13px; }.release-journey span { color:var(--do-muted);font-size:12px;line-height:1.5; }
.release-action-notice { border:1px solid var(--do-line); border-left:3px solid var(--do-warning); padding:14px; font-size:13px; line-height:1.6; overflow-wrap:anywhere; }.release-action-notice p { margin:6px 0; }
@media(max-width:760px) { .release-journey { grid-template-columns:repeat(2,minmax(0,1fr));gap:16px 8px; } }

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
.releases-page__table :deep(.el-table th.el-table__cell) { height: 44px; padding: 0; color: var(--do-muted); background: var(--do-soft); font-size: 12px; font-weight: 600; }
.releases-page__table :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 44px; }
.releases-page__table :deep(.el-table td.el-table__cell) { padding: 12px 0; border-bottom-color: var(--do-line); font-size: 14px; vertical-align: middle; }
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
.release-row-actions { display: flex; align-items: center; justify-content: flex-start; flex-wrap: wrap; gap: 4px 12px; white-space: normal; }
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
.metadata-version-dialog__audit-table :deep(.el-table th.el-table__cell) { height: 40px; padding: 0; color: var(--do-muted); background: var(--do-soft); font-size: 12px; }
.metadata-version-dialog__audit-table :deep(.el-table td.el-table__cell) { padding: 11px 0; border-bottom-color: var(--do-line); font-size: 14px; }
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
