<script setup lang="ts">
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  listQualityIssues,
  getSnapshotQualityIssueSummary,
  handleIssue,
  batchHandleIssues,
  assignIssue,
  listReviewRecords,
  listSnapshotTables,
  type ReviewRecord,
  type QualityIssueItem
} from '../../../api/admin/governance'
import { listUsers } from '../../../api/admin/user'
import {
  issueStatusLabel,
  issueStatusType,
  qualityDimensionLabel,
  severityLabel,
} from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'

const loading = ref(false)
const issues = ref<QualityIssueItem[]>([])
const domainSummary = ref<{ totalUnresolved: number; domainCounts?: Record<string, number> }>()
const domainSummaryLoading = ref(false)
const domainSummaryError = ref('')
const total = ref<number | null>(null)
const resultLoaded = ref(false)
const selectedIds = ref<number[]>([])
const errorMessage = ref('')
const tableOptions = ref<{ tableName: string; tableComment?: string }[]>([])
const tableOptionsLoading = ref(false)
const tableOptionsError = ref('')
const selectedIssue = ref<QualityIssueItem | null>(null)
const reviewRecords = ref<ReviewRecord[]>([])
const detailVisible = ref(false)
const detailLoading = ref(false)

/**
 * 责任人分派。
 *
 * `listUsers` 的 `pageSize` 上限是后端硬限制（PageRequest.MAX_PAGE_SIZE = 100），
 * 且该接口没有服务端搜索参数，因此超过 100 名用户时下拉会缺人——用 total 显式提示，
 * 不假装完整（开发指导 §12）。
 */
const USER_PAGE_SIZE = 100
const userOptions = ref<{ id: number; label: string }[]>([])
const userOptionsTotal = ref(0)
const userOptionsLoading = ref(false)
const assigneeError = ref('')
const assigneeSelection = ref<number | undefined>()
const assignLoading = ref(false)
let userOptionsLoaded = false
let issueRequestId = 0
let summaryRequestId = 0
let tableOptionsRequestId = 0
let detailRequestId = 0
let detailSessionId = 0
let assignmentRequestId = 0
let disposed = false
let activeScopeKey = ''
const adminContext = useAdminContextStore()
const auth = useAuthStore()
const iamS1 = useIamS1Store()
const route = useRoute()
const router = useRouter()

/** 当前登录用户 ID：用于「分配给我的」快捷筛选 */
const currentUserId = computed(() => auth.currentUser?.id ?? auth.user?.userId)

const MANAGE_FUNCTION = 'governance:issue:manage'
const VIEW_FUNCTION = 'governance:issue:view'
const RELEASE_VIEW_FUNCTION = 'metadata:release:view'
const datasourceId = computed(() => adminContext.datasourceId)
const snapshotId = computed(() => adminContext.snapshotId)
const requestedDatasourceId = computed(() => Number(route.query.datasourceId) || undefined)
const requestedSnapshotId = computed(() => Number(route.query.snapshotId) || undefined)
const routeScopeMatches = computed(() =>
  (!requestedDatasourceId.value || requestedDatasourceId.value === datasourceId.value)
  && (!requestedSnapshotId.value || requestedSnapshotId.value === snapshotId.value))
const routeScopeUnavailable = computed(() => {
  if (adminContext.routeSyncError) return true
  if (!adminContext.initialized || adminContext.routeSyncing) return false
  if (requestedDatasourceId.value
    && !adminContext.datasources.some((item) => item.id === requestedDatasourceId.value)) return true
  return Boolean(requestedSnapshotId.value && !adminContext.loading && !snapshotId.value)
})
const rangeSynchronizing = computed(() => !routeScopeUnavailable.value && (
  !adminContext.initialized
  || adminContext.loading
  || adminContext.routeSyncing
  || !routeScopeMatches.value))
const rangeReady = computed(() => adminContext.initialized
  && !adminContext.loading
  && routeScopeMatches.value
  && !routeScopeUnavailable.value
  && Boolean(datasourceId.value)
  && Boolean(snapshotId.value))
const canViewCurrentScope = computed(() => Boolean(datasourceId.value)
  && iamS1.canOnDatasource(VIEW_FUNCTION, datasourceId.value))
const canReadIssues = computed(() => rangeReady.value && iamS1.loaded && canViewCurrentScope.value)

/**
 * 能否处理某条问题。
 *
 * 处理要求 `governance:issue:manage` 与**该问题所属数据源**落在同一条启用的角色绑定上。
 * 前端只控制按钮可见性，后端仍会独立拒绝；给出中文原因而不是让用户点了才失败。
 */
function canManageIssue(issue: { datasourceId?: number } | null | undefined): boolean {
  if (!issue?.datasourceId) return iamS1.systemAdmin
  return iamS1.canOnDatasource(MANAGE_FUNCTION, issue.datasourceId)
}

/** 同表治理记录与「返回发布流程」属于发布域，不因拥有治理查看权而自动获得。 */
function canViewReleaseRecords(issue: { datasourceId?: number } | null | undefined): boolean {
  if (!issue?.datasourceId) return iamS1.systemAdmin
  return iamS1.canOnDatasource(RELEASE_VIEW_FUNCTION, issue.datasourceId)
}

/** 当前页里可处理的问题 ID；批量操作必须整批都可处理，否则后端会整批拒绝。 */
const manageableIssueIds = computed(
  () => new Set(issues.value.filter((issue) => canManageIssue(issue)).map((issue) => issue.id)),
)
const canBatchManage = computed(
  () => selectedIds.value.length > 0
    && selectedIds.value.every((id) => manageableIssueIds.value.has(id)),
)

const query = reactive({
  issueDomain: String(route.query.issueDomain || 'ALL'),
  dimension: String(route.query.dimension || ''),
  severity: String(route.query.severity || ''),
  status: String(route.query.status || ''),
  tableName: String(route.query.tableName || ''),
  assigneeId: Number(route.query.assigneeId) || undefined,
  page: Number(route.query.page) || 1,
  size: 20
})

/** 责任人筛选是否正落在「我自己」上，用于高亮快捷按钮 */
const isMyIssuesFilter = computed(() =>
  Boolean(currentUserId.value) && query.assigneeId === currentUserId.value)
const hasBusinessFilters = computed(() => Boolean(
  query.issueDomain !== 'ALL' || query.tableName || query.dimension || query.severity || query.status || query.assigneeId,
))
const canClearFilters = computed(() => hasBusinessFilters.value || query.page > 1)

/**
 * 「分配给我的」快捷筛选。
 *
 * 保持显式快捷筛选，首次进入不自动限制为当前责任人。
 */
function toggleMyIssues() {
  if (!currentUserId.value) return
  query.assigneeId = isMyIssuesFilter.value ? undefined : currentUserId.value
  applyFilters()
}

const dimensionOptions = [
  { label: '全部维度', value: '' },
  { label: '完整性', value: 'COMPLETENESS' },
  { label: '准确性', value: 'ACCURACY' },
  { label: '一致性', value: 'CONSISTENCY' },
  { label: '时效性', value: 'TIMELINESS' },
  { label: '可追溯性', value: 'TRACEABILITY' }
]
const severityOptions = [
  { label: '全部级别', value: '' },
  { label: '高', value: 'HIGH' },
  { label: '中', value: 'MEDIUM' },
  { label: '低', value: 'LOW' }
]
const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '待处理', value: 'OPEN' },
  { label: '已确认', value: 'CONFIRMED' },
  { label: '已解决', value: 'RESOLVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已重新打开', value: 'REOPENED' },
  { label: '自动关闭', value: 'AUTO_CLOSED' }
]
const issueDomainOptions = [
  { label: '全部问题域', value: 'ALL' },
  { label: '来源质量', value: 'SOURCE' },
  { label: '字段解释', value: 'SEMANTIC' },
]

const sevType = (s: string) => s === 'HIGH' ? 'danger' : s === 'MEDIUM' ? 'warning' : 'info'
const assigneeOptions = computed(() => {
  const options = [...userOptions.value]
  const currentId = selectedIssue.value?.assigneeId
  // 当前责任人可能已被禁用、或不在前 100 名内，补进选项避免 el-select 显示裸 id。
  if (currentId && !options.some((option) => option.id === currentId)) {
    options.unshift({ id: currentId, label: selectedIssue.value?.assigneeName || `用户 ${currentId}` })
  }
  return options
})

const canAssign = computed(() => {
  const target = assigneeSelection.value
  return Boolean(target) && target !== selectedIssue.value?.assigneeId && !assignLoading.value
})

async function fetchIssues() {
  const currentRequest = ++issueRequestId
  const requestedSnapshot = snapshotId.value
  selectedIds.value = []
  if (!rangeReady.value || !iamS1.loaded || !canViewCurrentScope.value || !requestedSnapshot) {
    issues.value = []
    total.value = null
    resultLoaded.value = false
    errorMessage.value = ''
    loading.value = false
    return
  }

  loading.value = true
  issues.value = []
  total.value = null
  resultLoaded.value = false
  errorMessage.value = ''
  try {
    const res = await listQualityIssues(requestedSnapshot, {
      issueDomain: query.issueDomain as 'ALL' | 'SOURCE' | 'SEMANTIC',
      dimension: query.dimension || undefined,
      severity: query.severity || undefined,
      status: query.status || undefined,
      tableName: query.tableName || undefined,
      assigneeId: query.assigneeId,
      page: query.page,
      size: query.size
    })
    if (disposed || currentRequest !== issueRequestId || !rangeReady.value || requestedSnapshot !== snapshotId.value) return
    if (!res.data || !Number.isFinite(res.data.total) || res.data.total < 0 || !Array.isArray(res.data.records)) {
      throw new Error('问题列表响应缺少有效的总数或记录')
    }
    issues.value = res.data.records
    total.value = res.data.total
    resultLoaded.value = true
  } catch (error) {
    if (disposed || currentRequest !== issueRequestId || requestedSnapshot !== snapshotId.value) return
    issues.value = []
    total.value = null
    resultLoaded.value = false
    errorMessage.value = error instanceof Error ? error.message : '问题列表加载失败'
  } finally {
    if (!disposed && currentRequest === issueRequestId) loading.value = false
  }
}

async function loadDomainSummary(forSnapshotId: number) {
  const request = ++summaryRequestId
  domainSummary.value = undefined
  domainSummaryError.value = ''
  if (!canReadIssues.value) return
  domainSummaryLoading.value = true
  try {
    const result = await getSnapshotQualityIssueSummary(forSnapshotId)
    if (disposed || request !== summaryRequestId || forSnapshotId !== snapshotId.value) return
    domainSummary.value = result.data
  } catch (error) {
    if (!disposed && request === summaryRequestId) {
      domainSummaryError.value = error instanceof Error ? error.message : '问题域统计读取失败'
    }
  } finally {
    if (!disposed && request === summaryRequestId) domainSummaryLoading.value = false
  }
}

async function loadTableOptions(forSnapshotId: number): Promise<boolean> {
  const currentRequest = ++tableOptionsRequestId
  tableOptions.value = []
  tableOptionsError.value = ''
  tableOptionsLoading.value = true
  try {
    const result = await listSnapshotTables(forSnapshotId)
    if (disposed || currentRequest !== tableOptionsRequestId || !rangeReady.value || forSnapshotId !== snapshotId.value) return false
    tableOptions.value = result.data ?? []
    if (query.tableName && !tableOptions.value.some((item) => item.tableName === query.tableName)) {
      query.tableName = ''
      query.page = 1
      persistQuery()
      return true
    }
    return false
  } catch (error) {
    if (disposed || currentRequest !== tableOptionsRequestId || forSnapshotId !== snapshotId.value) return false
    tableOptionsError.value = error instanceof Error ? error.message : '数据表选项加载失败'
    return false
  } finally {
    if (!disposed && currentRequest === tableOptionsRequestId) tableOptionsLoading.value = false
  }
}

function retryTableOptions() {
  if (!canReadIssues.value || !snapshotId.value) return
  void loadTableOptions(snapshotId.value).then((filterCleared) => {
    if (filterCleared) fetchIssues()
  })
}

function applyFilters() {
  query.page = 1
  selectedIds.value = []
  persistQuery()
  fetchIssues()
}

function clearFilters() {
  query.issueDomain = 'ALL'
  query.tableName = ''
  query.dimension = ''
  query.severity = ''
  query.status = ''
  query.assigneeId = undefined
  query.page = 1
  selectedIds.value = []
  persistQuery()
  fetchIssues()
}

function handlePageChange(page: number) {
  query.page = page
  selectedIds.value = []
  persistQuery()
  fetchIssues()
}

function persistQuery() {
  const nextQuery = { ...route.query }
  const values: Record<string, string | undefined> = {
    issueDomain: query.issueDomain === 'ALL' ? undefined : query.issueDomain,
    dimension: query.dimension || undefined,
    severity: query.severity || undefined,
    status: query.status || undefined,
    tableName: query.tableName || undefined,
    assigneeId: query.assigneeId ? String(query.assigneeId) : undefined,
    page: query.page > 1 ? String(query.page) : undefined,
  }
  Object.entries(values).forEach(([key, value]) => {
    if (value == null) delete nextQuery[key]
    else nextQuery[key] = value
  })
  router.replace({ query: nextQuery })
}

async function doHandle(issue: QualityIssueItem, status: string) {
  if (!canManageIssue(issue)) {
    ElMessage.warning('没有“处理质量问题”能力：需要角色拥有该功能并负责该问题所属数据源。')
    return
  }
  const issueId = issue.id
  try {
    await handleIssue(issueId, { status })
    ElMessage.success('操作成功')
    if (selectedIssue.value?.id === issueId) selectedIssue.value = { ...selectedIssue.value, status }
    if (snapshotId.value) void loadDomainSummary(snapshotId.value)
    await fetchIssues()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function loadUserOptions() {
  if (userOptionsLoaded || userOptionsLoading.value) return
  userOptionsLoading.value = true
  assigneeError.value = ''
  try {
    const res = await listUsers({ page: 1, pageSize: USER_PAGE_SIZE })
    const records = res.data?.records ?? []
    userOptionsTotal.value = res.data?.total ?? records.length
    // 只允许分派给启用账号，与项目既有的用户下拉保持一致。
    userOptions.value = records
      .filter((user) => user.status === 1)
      .map((user) => ({ id: user.id, label: user.realName || user.username }))
    userOptionsLoaded = true
  } catch (e: any) {
    // 非 user:manage 账号可能 403。必须显式呈现，不能渲染成空下拉（开发指导 §16.5）。
    assigneeError.value = e?.response?.data?.message || '用户列表加载失败，暂时无法分派'
  } finally {
    userOptionsLoading.value = false
  }
}

async function submitAssignment() {
  const issue = selectedIssue.value
  const assigneeId = assigneeSelection.value
  if (!issue || !assigneeId || assigneeId === issue.assigneeId) return
  if (!canManageIssue(issue)) {
    ElMessage.warning('没有“处理质量问题”能力：不能分派负责人。')
    return
  }
  const issueId = issue.id
  const requestedAssigneeId = assigneeId
  const assigneeName = assigneeOptions.value.find((option) => option.id === requestedAssigneeId)?.label
  const requestedDatasourceId = datasourceId.value
  const requestedSnapshotId = snapshotId.value
  const requestedScopeKey = `${requestedDatasourceId}:${requestedSnapshotId}`
  const requestedDetailSession = detailSessionId
  const currentAssignmentRequest = ++assignmentRequestId
  assignLoading.value = true
  try {
    await assignIssue(issueId, requestedAssigneeId)
    const sameScope = !disposed
      && canReadIssues.value
      && datasourceId.value === requestedDatasourceId
      && snapshotId.value === requestedSnapshotId
      && activeScopeKey === requestedScopeKey
    const sameDetail = sameScope
      && detailVisible.value
      && detailSessionId === requestedDetailSession
      && selectedIssue.value?.id === issueId
    if (sameDetail) {
      // 分派接口返回 Void；仅仍打开同一问题详情时才补充本次责任人结果。
      selectedIssue.value = { ...selectedIssue.value!, assigneeId: requestedAssigneeId, assigneeName }
      ElMessage.success(`已分派给 ${assigneeName || '所选用户'}`)
    }
    // 请求成功后只刷新仍然有效的范围。详情已切换时不改当前对象，也不报告失败。
    if (sameScope) await fetchIssues()
  } catch (e: any) {
    const sameScope = !disposed
      && datasourceId.value === requestedDatasourceId
      && snapshotId.value === requestedSnapshotId
      && activeScopeKey === requestedScopeKey
    const sameDetail = sameScope
      && detailVisible.value
      && detailSessionId === requestedDetailSession
      && selectedIssue.value?.id === issueId
    if (currentAssignmentRequest === assignmentRequestId && sameDetail) {
      ElMessage.error(e?.response?.data?.message || '分派失败')
    }
  } finally {
    if (currentAssignmentRequest === assignmentRequestId
      && detailVisible.value
      && detailSessionId === requestedDetailSession
      && selectedIssue.value?.id === issueId
      && activeScopeKey === requestedScopeKey) {
      assignLoading.value = false
    }
  }
}

async function doBatchHandle(status: string) {
  if (!selectedIds.value.length) { ElMessage.warning('请选择问题'); return }
  if (!canBatchManage.value) {
    // 后端对批量是“权限与归属预校验整批原子”：只要有一条无权就整批拒绝、零修改。
    // 与其发一个必然失败的请求，不如先说明哪些选中项不在可处理范围内。
    ElMessage.warning('所选问题中包含没有处理权限的条目，后端会整批拒绝。请只选择你负责的数据源下的问题。')
    return
  }
  const requested = selectedIds.value.length
  try {
    const res = await batchHandleIssues({ issueIds: selectedIds.value, status })
    const result = res.data
    if (result && typeof result.updated === 'number') {
      // 后端对状态不允许流转的条目会跳过，2026-09-12 起返回跳过明细与原因。
      // 被跳过的原因不是临时故障，重试不会成功，因此文案引导用户单独处理而不是重试。
      const skipped = typeof result.skipped === 'number' ? result.skipped : requested - result.updated
      if (skipped <= 0) {
        ElMessage.success(`已处理 ${result.updated} 条`)
      } else {
        const exampleReason = result.skippedIssues?.[0]?.reason
        ElMessage.warning(
          `已处理 ${result.updated} 条，${skipped} 条因当前状态不允许流转被跳过`
          + (exampleReason ? `（例如：${exampleReason}）` : '')
          + '。列表已刷新，请对剩余问题单独处理。',
        )
      }
    } else {
      // 响应结构异常时不能把字段缺失当成「全部被跳过」，那是假失败。
      ElMessage.success('批量操作已完成')
    }
    selectedIds.value = []
    if (snapshotId.value) void loadDomainSummary(snapshotId.value)
    await fetchIssues()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '批量操作失败')
  }
}

function onSelectionChange(rows: QualityIssueItem[]) {
  selectedIds.value = rows.map(r => r.id)
}

async function reopenIssue(issue: QualityIssueItem) {
  if (!canManageIssue(issue)) {
    ElMessage.warning('没有“处理质量问题”能力：需要角色拥有该功能并负责该问题所属数据源。')
    return
  }
  try {
    await ElMessageBox.confirm('重新打开后，该问题会重新进入治理流程，需再次确认后才能解决。确认继续吗？', '重新打开问题', {
      type: 'warning',
      confirmButtonText: '确认重新打开',
      cancelButtonText: '取消',
    })
    await handleIssue(issue.id, { status: 'REOPENED' })
    ElMessage.success('问题已重新打开')
    if (selectedIssue.value?.id === issue.id) selectedIssue.value = { ...selectedIssue.value, status: 'REOPENED' }
    if (snapshotId.value) void loadDomainSummary(snapshotId.value)
    await fetchIssues()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '重新打开失败')
    }
  }
}

function invalidateDetailSession() {
  detailSessionId++
  detailRequestId++
  assignmentRequestId++
  detailLoading.value = false
  assignLoading.value = false
  selectedIssue.value = null
  reviewRecords.value = []
  assigneeSelection.value = undefined
}

function handleDetailVisibilityChange(visible: boolean) {
  if (!visible) {
    invalidateDetailSession()
    detailVisible.value = false
    return
  }
  detailVisible.value = true
}

async function openIssueDetail(issue: QualityIssueItem) {
  invalidateDetailSession()
  const currentSession = detailSessionId
  const currentRequest = ++detailRequestId
  const currentDatasourceId = datasourceId.value
  const currentSnapshotId = snapshotId.value
  const currentScopeKey = `${datasourceId.value}:${snapshotId.value}`
  selectedIssue.value = issue
  reviewRecords.value = []
  detailVisible.value = true
  assigneeSelection.value = issue.assigneeId
  loadUserOptions()
  // 同表治理记录来自发布域的 `metadata:release:view`，治理查看权不自动包含它。
  // 无能力时不要发这个必然 403 的请求，改为在抽屉里说明原因。
  if (!canViewReleaseRecords(issue)) {
    detailLoading.value = false
    return
  }
  detailLoading.value = true
  try {
    const result = await listReviewRecords(issue.snapshotId, { tableName: issue.tableName, page: 1, size: 20 })
    if (disposed
      || currentRequest !== detailRequestId
      || currentSession !== detailSessionId
      || selectedIssue.value?.id !== issue.id
      || !detailVisible.value
      || currentScopeKey !== activeScopeKey
      || datasourceId.value !== currentDatasourceId
      || snapshotId.value !== currentSnapshotId
      || issue.snapshotId !== currentSnapshotId) return
    reviewRecords.value = result.data?.records ?? []
  } catch (error) {
    if (disposed || currentRequest !== detailRequestId || currentSession !== detailSessionId) return
    ElMessage.error(error instanceof Error ? error.message : '治理记录加载失败')
  } finally {
    if (!disposed && currentRequest === detailRequestId && currentSession === detailSessionId) detailLoading.value = false
  }
}

function syncFiltersFromRoute(values: unknown[]) {
  const [issueDomain, tableName, dimension, severity, status, assigneeIdValue, pageValue] = values
  const next = {
    issueDomain: String(issueDomain || 'ALL'),
    tableName: String(tableName || ''),
    dimension: String(dimension || ''),
    severity: String(severity || ''),
    status: String(status || ''),
    assigneeId: Number(assigneeIdValue) || undefined,
    page: Number(pageValue) || 1,
  }
  if (next.issueDomain === query.issueDomain
    && next.tableName === query.tableName
    && next.dimension === query.dimension
    && next.severity === query.severity
    && next.status === query.status
    && next.assigneeId === query.assigneeId
    && next.page === query.page) return
  Object.assign(query, next)
  selectedIds.value = []
  fetchIssues()
}

watch(
  () => [
    route.query.issueDomain,
    route.query.tableName,
    route.query.dimension,
    route.query.severity,
    route.query.status,
    route.query.assigneeId,
    route.query.page,
  ],
  syncFiltersFromRoute,
)

watch(
  () => [
    adminContext.initialized,
    adminContext.loading,
    adminContext.routeSyncing,
    adminContext.routeSyncError,
    datasourceId.value,
    snapshotId.value,
    requestedDatasourceId.value,
    requestedSnapshotId.value,
    iamS1.loaded,
    canViewCurrentScope.value,
  ],
  async () => {
    if (disposed) return
    if (!rangeReady.value || !iamS1.loaded || !canViewCurrentScope.value) {
      issueRequestId++
      summaryRequestId++
      loading.value = false
      issues.value = []
      total.value = null
      resultLoaded.value = false
      errorMessage.value = ''
      domainSummary.value = undefined
      domainSummaryError.value = ''
      domainSummaryLoading.value = false
      selectedIds.value = []
      if (!rangeReady.value || !canViewCurrentScope.value) {
        invalidateDetailSession()
        detailVisible.value = false
      }
      return
    }

    const scopeKey = `${datasourceId.value}:${snapshotId.value}`
    const scopeChanged = scopeKey !== activeScopeKey
    if (scopeChanged) {
      const hadPreviousScope = Boolean(activeScopeKey)
      activeScopeKey = scopeKey
      selectedIds.value = []
      invalidateDetailSession()
      detailVisible.value = false
      tableOptionsRequestId++
      tableOptions.value = []
      tableOptionsError.value = ''
      if (hadPreviousScope) {
        query.page = 1
        persistQuery()
      }
      await loadTableOptions(snapshotId.value!)
      if (disposed || activeScopeKey !== scopeKey || !canReadIssues.value) return
      void loadDomainSummary(snapshotId.value!)
    }
    fetchIssues()
  },
  { immediate: true, flush: 'post' },
)

onMounted(() => {
  // IAM 与负责人选项属于页面能力/分派数据；范围只由 AdminShell 中的公共 ScopeBar 初始化。
  void iamS1.load()
  loadUserOptions()
})

onBeforeUnmount(() => {
  disposed = true
  issueRequestId++
  summaryRequestId++
  tableOptionsRequestId++
  invalidateDetailSession()
})
</script>

<template>
  <main class="issue-page post-login-page">
    <TaskPageHeader title="问题中心" description="查找、分配并处理当前范围的治理问题。">
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" :disabled="!canReadIssues || loading" @click="fetchIssues">刷新问题</el-button>
      </template>
    </TaskPageHeader>

    <section class="issue-workbench" aria-label="问题工作区">
      <div class="issue-workbench__filters" aria-label="问题筛选">
        <el-select v-model="query.issueDomain" class="issue-filter" placeholder="全部问题域" aria-label="按问题域筛选" :disabled="!canReadIssues" @change="applyFilters">
          <el-option v-for="option in issueDomainOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select
          v-model="query.tableName"
          class="issue-filter issue-filter--table"
          placeholder="全部表"
          clearable
          filterable
          :loading="tableOptionsLoading"
          :disabled="!canReadIssues || tableOptionsLoading || Boolean(tableOptionsError)"
          aria-label="按表筛选"
          @change="applyFilters"
        >
          <el-option label="全部表" value="" />
          <el-option v-for="item in tableOptions" :key="item.tableName" :label="item.tableComment ? `${item.tableName} / ${item.tableComment}` : item.tableName" :value="item.tableName" />
        </el-select>
        <el-select v-model="query.dimension" class="issue-filter" placeholder="全部维度" aria-label="按维度筛选" :disabled="!canReadIssues" @change="applyFilters">
          <el-option v-for="option in dimensionOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="query.severity" class="issue-filter" placeholder="全部级别" aria-label="按级别筛选" :disabled="!canReadIssues" @change="applyFilters">
          <el-option v-for="option in severityOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="query.status" class="issue-filter" placeholder="全部状态" aria-label="按状态筛选" :disabled="!canReadIssues" @change="applyFilters">
          <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select
          v-model="query.assigneeId"
          class="issue-filter issue-filter--assignee"
          placeholder="全部责任人"
          clearable
          filterable
          :loading="userOptionsLoading"
          :disabled="!canReadIssues || Boolean(assigneeError)"
          aria-label="按责任人筛选"
          @change="applyFilters"
        >
          <el-option v-for="option in userOptions" :key="option.id" :label="option.label" :value="option.id" />
        </el-select>
        <el-button
          class="issue-filter__my-issues"
          :type="isMyIssuesFilter ? 'primary' : 'default'"
          :disabled="!canReadIssues || !currentUserId"
          :aria-pressed="isMyIssuesFilter"
          @click="toggleMyIssues"
        >分配给我的</el-button>
        <el-button class="issue-filter__clear" :disabled="!canClearFilters" @click="clearFilters">清除筛选</el-button>
      </div>

      <div v-if="domainSummaryLoading" class="issue-domain-summary" role="status">正在读取当前快照的问题域统计…</div>
      <div v-else-if="domainSummary" class="issue-domain-summary" aria-label="当前快照未解决问题统计">
        未解决问题：全部 <strong>{{ domainSummary.totalUnresolved }}</strong>
        <span>来源质量 {{ domainSummary.domainCounts?.SOURCE ?? 0 }}</span>
        <span>字段解释 {{ domainSummary.domainCounts?.SEMANTIC ?? 0 }}</span>
      </div>
      <p v-else-if="domainSummaryError" class="issue-workbench__notice issue-workbench__notice--error" role="alert">问题域统计读取失败：{{ domainSummaryError }}</p>

      <div v-if="tableOptionsError || assigneeError || userOptionsTotal > USER_PAGE_SIZE" class="issue-workbench__notices">
        <p v-if="tableOptionsError" class="issue-workbench__notice issue-workbench__notice--error" role="alert">
          数据表选项读取失败：{{ tableOptionsError }}
          <el-button link type="primary" @click="retryTableOptions">重试</el-button>
        </p>
        <p v-if="assigneeError" class="issue-workbench__notice issue-workbench__notice--error" role="alert">{{ assigneeError }}；暂时无法使用责任人筛选或分派。</p>
        <p v-else-if="userOptionsTotal > USER_PAGE_SIZE" class="issue-workbench__notice" role="note">
          共 {{ userOptionsTotal }} 名用户，责任人下拉仅显示前 {{ USER_PAGE_SIZE }} 名。
        </p>
      </div>

      <div class="issue-workbench__results" aria-live="polite">
        <p v-if="rangeSynchronizing" class="issue-workbench__result-state">正在同步数据源和快照范围…</p>
        <p v-else-if="routeScopeUnavailable" class="issue-workbench__result-state issue-workbench__result-state--error">指定的数据源或快照无法恢复，请查看顶部范围提示并重新选择。</p>
        <p v-else-if="!rangeReady" class="issue-workbench__result-state">请先在顶部范围栏选择有效的数据源和快照。</p>
        <p v-else-if="iamS1.errorMessage" class="issue-workbench__result-state issue-workbench__result-state--error">查看权限读取失败。</p>
        <p v-else-if="!iamS1.loaded" class="issue-workbench__result-state">正在确认当前数据源的查看权限…</p>
        <p v-else-if="!canViewCurrentScope" class="issue-workbench__result-state issue-workbench__result-state--error">没有查看当前数据源质量问题的权限。</p>
        <p v-else-if="loading" class="issue-workbench__result-state">正在读取问题记录…</p>
        <p v-else-if="errorMessage" class="issue-workbench__result-state issue-workbench__result-state--error">读取失败，未取得筛选结果总数。</p>
        <p v-else-if="resultLoaded && total !== null" class="issue-workbench__result-count">筛选结果共 <strong>{{ total }}</strong> 条</p>
        <div v-if="selectedIds.length" class="issue-workbench__batch-actions" aria-label="批量操作">
          <span>已选 {{ selectedIds.length }} 项</span>
          <el-button size="small" :disabled="!canBatchManage" @click="doBatchHandle('CONFIRMED')">批量确认</el-button>
          <el-button size="small" type="danger" :disabled="!canBatchManage" @click="doBatchHandle('REJECTED')">批量驳回</el-button>
          <span v-if="!canBatchManage" class="issue-workbench__batch-note">
            所选条目中包含没有处理权限的问题：后端会整批拒绝。请只选择你负责的数据源下的问题。
          </span>
        </div>
      </div>

      <div class="issue-workbench__content" :aria-busy="loading || rangeSynchronizing">
        <div v-if="rangeSynchronizing" role="status" aria-live="polite">
          <LoadingState text="正在确认当前查看范围…" />
        </div>
        <ErrorState v-else-if="routeScopeUnavailable" message="指定的数据源或快照不可用。请在顶部范围栏重新选择；不会自动切换到其他快照。" :show-retry="false" />
        <EmptyState v-else-if="!rangeReady" message="请在顶部范围栏选择有效的数据源和快照。" />
        <ErrorState v-else-if="iamS1.errorMessage" :message="iamS1.errorMessage" @retry="iamS1.load()" />
        <LoadingState v-else-if="!iamS1.loaded" text="正在确认当前数据源的查看权限…" />
        <EmptyState v-else-if="!canViewCurrentScope" message="当前账号没有查看所选数据源质量问题的权限。" />
        <div v-else-if="loading" role="status" aria-live="polite">
          <LoadingState text="正在读取问题记录…" />
        </div>
        <ErrorState v-else-if="errorMessage" :message="errorMessage" @retry="fetchIssues" />
        <div v-else-if="issues.length" class="issue-table-scroll" role="region" aria-label="质量问题列表" tabindex="0">
          <el-table :data="issues" stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="tableName" label="表" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="table-code">{{ row.tableName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="columnName" label="字段" width="120">
          <template #default="{ row }">
            <span class="column-code">{{ row.columnName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="问题域" width="112">
          <template #default="{ row }">
            <el-tag v-if="row.issueDomain === 'SOURCE'" size="small" type="info">来源质量</el-tag>
            <el-tag v-else-if="row.issueDomain === 'SEMANTIC'" size="small" type="warning">字段解释</el-tag>
            <el-tag v-else size="small" type="danger">未知问题域</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dimension" label="维度" width="90">
          <template #default="{ row }">{{ qualityDimensionLabel(row.dimension) }}</template>
        </el-table-column>
        <el-table-column prop="severity" label="级别" width="70">
          <template #default="{ row }">
            <el-tag :type="sevType(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="issueDescription" label="问题描述" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="issue-description">{{ row.issueDescription }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="issueStatusType(row.status)" size="small">{{ issueStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="assigneeName" label="责任人" width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'muted-text': !row.assigneeName }">{{ row.assigneeName || '未分派' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <!-- 可处理：显示状态机允许的动作。无处理权限时只留「详情」，并说明原因，
                 而不是渲染一组点了必然被后端拒绝的按钮。 -->
            <template v-if="canManageIssue(row)">
              <template v-if="row.status === 'OPEN' || row.status === 'REOPENED'">
                <el-button link size="small" @click="openIssueDetail(row)">详情</el-button>
                <el-button link size="small" @click="doHandle(row, 'CONFIRMED')">确认</el-button>
                <el-button link size="small" type="danger" @click="doHandle(row, 'REJECTED')">驳回</el-button>
              </template>
              <template v-else-if="row.status === 'CONFIRMED'">
                <el-button link size="small" @click="openIssueDetail(row)">详情</el-button>
                <el-button link size="small" type="success" @click="doHandle(row, 'RESOLVED')">解决</el-button>
                <el-button link size="small" type="danger" @click="doHandle(row, 'REJECTED')">驳回</el-button>
              </template>
              <template v-else-if="row.status === 'RESOLVED' || row.status === 'REJECTED'">
                <el-button link size="small" @click="openIssueDetail(row)">详情</el-button>
                <el-button link size="small" type="warning" @click="reopenIssue(row)">重新打开</el-button>
              </template>
              <el-button v-else link size="small" @click="openIssueDetail(row)">详情</el-button>
            </template>
            <template v-else>
              <el-button link size="small" @click="openIssueDetail(row)">详情</el-button>
              <span class="muted-text" title="需要角色拥有“处理质量问题”并负责该数据源">无处理权限</span>
            </template>
            <RouterLink
              v-if="row.snapshotId && canViewReleaseRecords(row)"
              class="return-link"
              :to="{ path: '/admin/releases', query: { datasourceId: row.datasourceId ? String(row.datasourceId) : undefined, snapshotId: String(row.snapshotId), tab: 'candidates' } }"
            >返回发布流程</RouterLink>
          </template>
        </el-table-column>
          </el-table>
        </div>
        <EmptyState
          v-else-if="resultLoaded && total === 0 && hasBusinessFilters"
          message="没有符合筛选条件的问题。"
          action-text="清除筛选"
          @action="clearFilters"
        />
        <EmptyState
          v-else-if="resultLoaded && total === 0"
          message="当前快照暂无问题记录；无记录不代表数据质量已验证通过。"
        />
        <div v-else-if="resultLoaded && total !== null && total > 0" class="issue-workbench__empty-page" role="status">
          当前页没有返回问题记录，请刷新问题列表或调整页码。
          <el-button link type="primary" @click="fetchIssues">刷新问题</el-button>
        </div>
      </div>

      <footer v-if="resultLoaded && total !== null && total > 0" class="issue-workbench__pagination">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="query.size"
          :current-page="query.page"
          @current-change="handlePageChange"
        />
      </footer>
    </section>

    <el-drawer :model-value="detailVisible" title="治理问题详情" size="520px" @update:model-value="handleDetailVisibilityChange">
      <el-skeleton v-if="detailLoading" :rows="7" animated />
      <template v-else-if="selectedIssue">
        <div class="issue-detail">
          <div class="issue-detail__headline">
            <strong>{{ selectedIssue.tableName }}{{ selectedIssue.columnName ? `.${selectedIssue.columnName}` : '' }}</strong>
            <el-tag :type="sevType(selectedIssue.severity)" size="small">{{ severityLabel(selectedIssue.severity) }}</el-tag>
            <el-tag :type="issueStatusType(selectedIssue.status)" size="small">{{ issueStatusLabel(selectedIssue.status) }}</el-tag>
          </div>
          <dl>
            <dt>问题域</dt><dd>{{ selectedIssue.issueDomain === 'SOURCE' ? '来源质量' : selectedIssue.issueDomain === 'SEMANTIC' ? '字段解释' : '未知问题域' }}</dd>
            <dt>问题描述</dt><dd>{{ selectedIssue.issueDescription }}</dd>
            <dt>建议</dt><dd>{{ selectedIssue.suggestion || '后端未提供处理建议' }}</dd>
            <dt>维度</dt><dd>{{ qualityDimensionLabel(selectedIssue.dimension) }}</dd>
            <dt>责任人</dt><dd>{{ selectedIssue.assigneeName || '未分派' }}</dd>
            <dt>创建时间</dt><dd>{{ selectedIssue.createdAt }}</dd>
          </dl>
          <RouterLink
            v-if="selectedIssue.issueDomain === 'SEMANTIC' && selectedIssue.columnMetaId"
            class="return-link"
            :to="{ path: '/admin/governance/fields', query: { datasourceId: String(selectedIssue.datasourceId), snapshotId: String(selectedIssue.snapshotId), tab: 'semantics', tableName: selectedIssue.tableName, columnMetaId: String(selectedIssue.columnMetaId) } }"
          >打开字段业务解释</RouterLink>
          <section class="issue-detail__assign">
            <h3>分派责任人</h3>
            <p v-if="!canManageIssue(selectedIssue)" class="muted-text">
              没有“处理质量问题”能力：需要角色拥有该功能并负责该问题所属数据源。分派只写工作归属，不产生任何数据授权。
            </p>
            <template v-else>
              <div class="issue-detail__assign-controls">
                <el-select
                  v-model="assigneeSelection"
                  class="issue-detail__assign-select"
                  filterable
                  clearable
                  size="small"
                  :loading="userOptionsLoading"
                  :disabled="Boolean(assigneeError)"
                  placeholder="选择用户"
                >
                  <el-option v-for="option in assigneeOptions" :key="option.id" :label="option.label" :value="option.id" />
                </el-select>
                <el-button size="small" type="primary" :loading="assignLoading" :disabled="!canAssign" @click="submitAssignment">分派</el-button>
              </div>
              <p v-if="assigneeError" class="muted-text">{{ assigneeError }}</p>
              <p v-else-if="userOptionsTotal > USER_PAGE_SIZE" class="muted-text">
                共 {{ userOptionsTotal }} 名用户，下拉仅显示前 {{ USER_PAGE_SIZE }} 名，目标用户可能不在列表中。
              </p>
            </template>
          </section>
          <div class="issue-detail__actions">
            <RouterLink class="return-link" :to="{ path: '/admin/governance', query: { datasourceId: selectedIssue.datasourceId ? String(selectedIssue.datasourceId) : undefined, snapshotId: String(selectedIssue.snapshotId) } }">回治理复查</RouterLink>
            <RouterLink
              v-if="canViewReleaseRecords(selectedIssue)"
              class="return-link"
              :to="{ path: '/admin/releases', query: { datasourceId: selectedIssue.datasourceId ? String(selectedIssue.datasourceId) : undefined, snapshotId: String(selectedIssue.snapshotId), tab: 'candidates' } }"
            >回发布流程</RouterLink>
          </div>
          <h3>同表治理记录</h3>
          <template v-if="canViewReleaseRecords(selectedIssue)">
            <p class="muted-text">以下记录来自当前快照同一数据表，后端暂未提供单个问题的独立历史接口。</p>
            <el-table :data="reviewRecords" size="small" stripe>
              <el-table-column prop="action" label="动作" width="110" />
              <el-table-column label="状态" width="150"><template #default="{ row }">{{ row.oldStatus || '-' }} → {{ row.newStatus || '-' }}</template></el-table-column>
              <el-table-column prop="operatorName" label="操作人" width="100" />
              <el-table-column prop="createdAt" label="时间" width="160" />
            </el-table>
            <el-empty v-if="!reviewRecords.length" description="暂无同表治理记录" />
          </template>
          <p v-else class="muted-text">
            没有“查看元数据版本”能力：审核记录属于发布域（<code>metadata:release:view</code>），拥有治理查看权不会自动获得它。
          </p>
        </div>
      </template>
    </el-drawer>
  </main>
</template>

<style scoped>
.issue-page { display: grid; gap: 16px; min-width: 0; }
.issue-page :deep(.task-page-header) { align-items: center; margin-bottom: 0; }
.issue-page :deep(.task-page-header h1) { font-size: clamp(24px, 2vw, 28px); }
.issue-workbench {
  display: grid;
  gap: 0;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.issue-workbench__filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
  padding: 18px 20px;
}
.issue-domain-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 18px; padding: 0 20px 12px; color: var(--do-muted); font-size: 12px; }
.issue-domain-summary strong { color: var(--do-ink); font-variant-numeric: tabular-nums; }
.issue-filter { flex: 1 1 145px; min-width: min(145px, 100%); }
.issue-filter--table { flex: 2 1 240px; }
.issue-filter--assignee { flex-basis: 180px; }
.issue-filter__my-issues,
.issue-filter__clear { flex: 0 0 auto; }
.issue-workbench__notices { display: grid; gap: 5px; padding: 0 20px 12px; }
.issue-workbench__notice { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; margin: 0; color: var(--do-muted); font-size: 13px; line-height: 1.5; }
.issue-workbench__notice--error { color: var(--do-danger); }
.issue-workbench__results {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px 16px;
  min-width: 0;
  padding: 12px 20px;
  border-top: 1px solid var(--do-line);
  border-bottom: 1px solid var(--do-line);
  background: color-mix(in srgb, var(--do-bg) 40%, var(--do-surface));
}
.issue-workbench__result-state,
.issue-workbench__result-count { margin: 0; color: var(--do-muted); font-size: 14px; line-height: 1.45; }
.issue-workbench__result-state--error { color: var(--do-danger); }
.issue-workbench__result-count { color: var(--do-ink); font-weight: 600; }
.issue-workbench__result-count strong { color: var(--do-primary-strong); font-size: 17px; font-variant-numeric: tabular-nums; }
.issue-workbench__batch-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; }
.issue-workbench__batch-actions > span:first-child { color: var(--do-ink); font-size: 13px; font-weight: 600; }
.issue-workbench__batch-note { flex: 1 1 260px; color: var(--do-muted); font-size: 12px; line-height: 1.45; }
.issue-workbench__content { min-width: 0; }
.issue-table-scroll { width: 100%; min-width: 0; overflow-x: auto; overscroll-behavior-x: contain; }
.issue-table-scroll :deep(.el-table) { min-width: 1080px; }
.issue-workbench__empty-page { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-height: 64px; padding: 16px 20px; color: var(--do-muted); font-size: 14px; }
.issue-workbench__pagination { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 12px; min-width: 0; padding: 12px 20px; border-top: 1px solid var(--do-line); }
.issue-workbench__pagination :deep(.el-pagination) { justify-content: flex-end; }
.issue-description {
  color: var(--do-ink);
  font-size: 13px;
}
.return-link { margin-left: 8px; color: var(--do-primary-strong); font-size: 12px; }
.muted-text { color: var(--do-muted); font-size: 12px; }
.issue-detail { display: grid; gap: 14px; }
.issue-detail__headline, .issue-detail__actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.issue-detail dl { display: grid; grid-template-columns: 76px 1fr; gap: 10px; margin: 0; }
.issue-detail dt { color: var(--do-muted); font-size: 12px; }
.issue-detail dd { margin: 0; color: var(--do-ink); font-size: 13px; line-height: 1.6; }
.issue-detail h3 { margin: 4px 0 0; color: var(--do-ink); font-size: 15px; }
.issue-detail__assign { display: grid; gap: 8px; }
.issue-detail__assign-controls { display: flex; align-items: center; gap: 8px; }
.issue-detail__assign-select { flex: 1; min-width: 0; }
@media (max-width: 760px) {
  .issue-workbench__filters { padding: 14px; }
  .issue-filter { flex-basis: 100%; }
  .issue-filter__my-issues,
  .issue-filter__clear { flex: 1 1 auto; }
  .issue-workbench__notices { padding-right: 14px; padding-left: 14px; }
  .issue-workbench__results { align-items: flex-start; padding: 12px 14px; }
  .issue-workbench__pagination { padding: 12px 14px; }
  .issue-workbench__empty-page { padding-right: 14px; padding-left: 14px; }
}
</style>
