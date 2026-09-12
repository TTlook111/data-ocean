<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  listQualityIssues,
  handleIssue,
  batchHandleIssues,
  assignIssue,
  listReviewRecords,
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
import ResourceScopeSelector from '../../../components/ResourceScopeSelector.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const loading = ref(false)
const issues = ref<QualityIssueItem[]>([])
const total = ref(0)
const selectedIds = ref<number[]>([])
const scopeDatasourceId = ref<number | undefined>()
const errorMessage = ref('')
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
let detailRequestId = 0
let disposed = false
const adminContext = useAdminContextStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 当前登录用户 ID：用于「分配给我的」快捷筛选 */
const currentUserId = computed(() => auth.currentUser?.id ?? auth.user?.userId)

const query = reactive({
  snapshotId: undefined as number | undefined,
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

/**
 * 「分配给我的」快捷筛选。
 *
 * 规范 §7.7 的原文是「**默认**优先展示高危、阻断和分配给当前用户的问题」。
 * 这里实现为**显式筛选**而非默认开启：
 * 1. 强制默认过滤会让多数管理员首次进入就看到空列表，反而不知道系统里存在问题；
 * 2. 该规范的意图是「优先」而不是「只显示」，要真正落实需要后端按责任人排序，
 *    那是独立的一件事，本次未做。
 *
 * ⚠️ 这是实现者的取舍，**未经产品确认**。若要求「默认开启」或「按责任人排序」，
 * 改动点在本函数与后端 `listIssues` 的 orderBy。
 */
function toggleMyIssues() {
  if (!currentUserId.value) return
  query.assigneeId = isMyIssuesFilter.value ? undefined : currentUserId.value
  query.page = 1
  persistQuery()
  fetchIssues()
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

const sevType = (s: string) => s === 'HIGH' ? 'danger' : s === 'MEDIUM' ? 'warning' : 'info'
const issueSummary = computed(() => ({
  high: issues.value.filter((item) => item.severity === 'HIGH').length,
  open: issues.value.filter((item) => item.status === 'OPEN').length,
  confirmed: issues.value.filter((item) => item.status === 'CONFIRMED').length,
  resolved: issues.value.filter((item) => item.status === 'RESOLVED').length,
}))

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
  const snapshotId = query.snapshotId
  if (!snapshotId) {
    issues.value = []
    total.value = 0
    errorMessage.value = ''
    loading.value = false
    return
  }

  loading.value = true
  errorMessage.value = ''
  try {
    const res = await listQualityIssues(snapshotId, {
      dimension: query.dimension || undefined,
      severity: query.severity || undefined,
      status: query.status || undefined,
      tableName: query.tableName || undefined,
      assigneeId: query.assigneeId,
      page: query.page,
      size: query.size
    })
    if (disposed || currentRequest !== issueRequestId || snapshotId !== query.snapshotId) return
    issues.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch (error) {
    if (disposed || currentRequest !== issueRequestId || snapshotId !== query.snapshotId) return
    issues.value = []
    total.value = 0
    errorMessage.value = error instanceof Error ? error.message : '问题列表加载失败'
  } finally {
    if (!disposed && currentRequest === issueRequestId) loading.value = false
  }
}

function handleScopeChange() {
  query.page = 1
  persistQuery()
  fetchIssues()
}

function persistQuery() {
  const nextQuery = { ...route.query }
  const values: Record<string, string | undefined> = {
    snapshotId: query.snapshotId ? String(query.snapshotId) : undefined,
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

async function doHandle(issueId: number, status: string) {
  try {
    await handleIssue(issueId, { status })
    ElMessage.success('操作成功')
    if (selectedIssue.value?.id === issueId) selectedIssue.value = { ...selectedIssue.value, status }
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
  assignLoading.value = true
  try {
    await assignIssue(issue.id, assigneeId)
    const assigneeName = assigneeOptions.value.find((option) => option.id === assigneeId)?.label
    // assignIssue 返回 Result<Void>，不回传更新后的对象；而 fetchIssues 会用新对象
    // 整体替换 issues.value，selectedIssue 仍指向旧引用，必须就地打补丁，
    // 否则抽屉里的「责任人」会一直是旧值。
    selectedIssue.value = { ...issue, assigneeId, assigneeName }
    ElMessage.success(`已分派给 ${assigneeName || '所选用户'}`)
    await fetchIssues()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '分派失败')
  } finally {
    assignLoading.value = false
  }
}

async function doBatchHandle(status: string) {
  if (!selectedIds.value.length) { ElMessage.warning('请选择问题'); return }
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
    await fetchIssues()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '批量操作失败')
  }
}

function onSelectionChange(rows: QualityIssueItem[]) {
  selectedIds.value = rows.map(r => r.id)
}

async function reopenIssue(issue: QualityIssueItem) {
  try {
    await ElMessageBox.confirm('重新打开后，该问题会重新进入治理流程，需再次确认后才能解决。确认继续吗？', '重新打开问题', {
      type: 'warning',
      confirmButtonText: '确认重新打开',
      cancelButtonText: '取消',
    })
    await handleIssue(issue.id, { status: 'REOPENED' })
    ElMessage.success('问题已重新打开')
    if (selectedIssue.value?.id === issue.id) selectedIssue.value = { ...selectedIssue.value, status: 'REOPENED' }
    await fetchIssues()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '重新打开失败')
    }
  }
}

async function openIssueDetail(issue: QualityIssueItem) {
  const currentRequest = ++detailRequestId
  selectedIssue.value = issue
  reviewRecords.value = []
  detailVisible.value = true
  detailLoading.value = true
  assigneeSelection.value = issue.assigneeId
  loadUserOptions()
  try {
    const result = await listReviewRecords(issue.snapshotId, { tableName: issue.tableName, page: 1, size: 20 })
    if (disposed || currentRequest !== detailRequestId || selectedIssue.value?.id !== issue.id) return
    reviewRecords.value = result.data?.records ?? []
  } catch (error) {
    if (disposed || currentRequest !== detailRequestId) return
    ElMessage.error(error instanceof Error ? error.message : '治理记录加载失败')
  } finally {
    if (!disposed && currentRequest === detailRequestId) detailLoading.value = false
  }
}

onMounted(async () => {
  await adminContext.initialize()
  scopeDatasourceId.value = adminContext.datasourceId
  query.snapshotId = adminContext.snapshotId
  if (route.query.snapshotId) query.snapshotId = Number(route.query.snapshotId) || query.snapshotId
  // 责任人筛选下拉需要用户列表，进页面就取一次（供筛选与详情分派共用）
  loadUserOptions()
  fetchIssues()
})

watch(
  () => adminContext.snapshotId,
  (snapshotId) => {
    if (query.snapshotId === snapshotId) return
    query.snapshotId = snapshotId
    query.page = 1
    persistQuery()
    fetchIssues()
  },
)

watch(
  () => adminContext.datasourceId,
  (datasourceId) => {
    scopeDatasourceId.value = datasourceId
    issueRequestId++
    detailRequestId++
    selectedIssue.value = null
    detailVisible.value = false
    query.snapshotId = undefined
    query.tableName = ''
    query.page = 1
    persistQuery()
    fetchIssues()
  },
)

onBeforeUnmount(() => {
  disposed = true
  issueRequestId++
  detailRequestId++
})
</script>

<template>
  <main class="issue-page post-login-page">
    <section class="page-actions">
      <el-button :icon="RefreshCw" @click="fetchIssues">刷新</el-button>
    </section>

    <section class="toolbar">
      <ResourceScopeSelector
        v-model:datasource-id="scopeDatasourceId"
        v-model:snapshot-id="query.snapshotId"
        v-model:table-name="query.tableName"
        mode="table"
        include-all-table-option
        all-table-label="全部表"
        @change="handleScopeChange"
      />
      <el-select v-model="query.dimension" placeholder="全部维度" style="width: 120px" @change="query.page = 1; persistQuery(); fetchIssues()">
        <el-option v-for="o in dimensionOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="query.severity" placeholder="全部级别" style="width: 100px" @change="query.page = 1; persistQuery(); fetchIssues()">
        <el-option v-for="o in severityOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="query.status" placeholder="全部状态" style="width: 120px" @change="query.page = 1; persistQuery(); fetchIssues()">
        <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select
        v-model="query.assigneeId"
        placeholder="全部责任人"
        style="width: 150px"
        clearable
        filterable
        :loading="userOptionsLoading"
        :disabled="Boolean(assigneeError)"
        @change="query.page = 1; persistQuery(); fetchIssues()"
      >
        <el-option v-for="o in userOptions" :key="o.id" :label="o.label" :value="o.id" />
      </el-select>
      <el-button
        size="small"
        :type="isMyIssuesFilter ? 'primary' : 'default'"
        :disabled="!currentUserId"
        @click="toggleMyIssues"
      >分配给我的</el-button>
      <span v-if="assigneeError" class="muted-text">{{ assigneeError }}</span>
      <span v-else-if="userOptionsTotal > USER_PAGE_SIZE" class="muted-text">
        共 {{ userOptionsTotal }} 名用户，责任人下拉仅显示前 {{ USER_PAGE_SIZE }} 名。
      </span>
    </section>

    <section class="status-strip issue-strip">
      <span class="metric-chip danger">高危 {{ issueSummary.high }}</span>
      <span class="metric-chip">待处理 {{ issueSummary.open }}</span>
      <span class="metric-chip">已确认 {{ issueSummary.confirmed }}</span>
      <span class="metric-chip success">已解决 {{ issueSummary.resolved }}</span>
    </section>

    <section class="batch-bar" v-if="selectedIds.length">
      <span>已选 {{ selectedIds.length }} 项</span>
      <el-button size="small" @click="doBatchHandle('CONFIRMED')">批量确认</el-button>
      <el-button size="small" type="danger" @click="doBatchHandle('REJECTED')">批量驳回</el-button>
    </section>

    <section class="table-shell">
      <ErrorState v-if="errorMessage" :message="errorMessage" @retry="fetchIssues" />
      <el-table v-else-if="issues.length" :data="issues" v-loading="loading" stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="datasourceName" label="数据源" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.datasourceName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="snapshotId" label="快照" width="86">
          <template #default="{ row }">#{{ row.snapshotId }}</template>
        </el-table-column>
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
            <template v-if="row.status === 'OPEN' || row.status === 'REOPENED'">
              <el-button link size="small" @click="openIssueDetail(row)">详情</el-button>
              <el-button link size="small" @click="doHandle(row.id, 'CONFIRMED')">确认</el-button>
              <el-button link size="small" type="danger" @click="doHandle(row.id, 'REJECTED')">驳回</el-button>
            </template>
            <template v-else-if="row.status === 'CONFIRMED'">
              <el-button link size="small" @click="openIssueDetail(row)">详情</el-button>
              <el-button link size="small" type="success" @click="doHandle(row.id, 'RESOLVED')">解决</el-button>
              <el-button link size="small" type="danger" @click="doHandle(row.id, 'REJECTED')">驳回</el-button>
            </template>
            <template v-else-if="row.status === 'RESOLVED' || row.status === 'REJECTED'">
              <el-button link size="small" @click="openIssueDetail(row)">详情</el-button>
              <el-button link size="small" type="warning" @click="reopenIssue(row)">重新打开</el-button>
            </template>
            <el-button v-else link size="small" @click="openIssueDetail(row)">详情</el-button>
            <RouterLink
              v-if="row.snapshotId"
              class="return-link"
              :to="{ path: '/admin/releases', query: { datasourceId: row.datasourceId ? String(row.datasourceId) : undefined, snapshotId: String(row.snapshotId), tab: 'candidates' } }"
            >返回发布流程</RouterLink>
            <span v-else class="muted-text">-</span>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else-if="!loading" message="当前范围和筛选条件下没有治理问题。" />
    </section>

    <el-pagination class="pager" background layout="total, prev, pager, next"
                   :total="total" :page-size="query.size"
                   v-model:current-page="query.page" @current-change="persistQuery(); fetchIssues()" />

    <el-drawer v-model="detailVisible" title="治理问题详情" size="520px">
      <el-skeleton v-if="detailLoading" :rows="7" animated />
      <template v-else-if="selectedIssue">
        <div class="issue-detail">
          <div class="issue-detail__headline">
            <strong>{{ selectedIssue.tableName }}{{ selectedIssue.columnName ? `.${selectedIssue.columnName}` : '' }}</strong>
            <el-tag :type="sevType(selectedIssue.severity)" size="small">{{ severityLabel(selectedIssue.severity) }}</el-tag>
            <el-tag :type="issueStatusType(selectedIssue.status)" size="small">{{ issueStatusLabel(selectedIssue.status) }}</el-tag>
          </div>
          <dl>
            <dt>问题描述</dt><dd>{{ selectedIssue.issueDescription }}</dd>
            <dt>建议</dt><dd>{{ selectedIssue.suggestion || '后端未提供处理建议' }}</dd>
            <dt>维度</dt><dd>{{ qualityDimensionLabel(selectedIssue.dimension) }}</dd>
            <dt>责任人</dt><dd>{{ selectedIssue.assigneeName || '未分派' }}</dd>
            <dt>创建时间</dt><dd>{{ selectedIssue.createdAt }}</dd>
          </dl>
          <section class="issue-detail__assign">
            <h3>分派责任人</h3>
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
          </section>
          <div class="issue-detail__actions">
            <RouterLink class="return-link" :to="{ path: '/admin/governance', query: { datasourceId: selectedIssue.datasourceId ? String(selectedIssue.datasourceId) : undefined, snapshotId: String(selectedIssue.snapshotId) } }">回治理复查</RouterLink>
            <RouterLink class="return-link" :to="{ path: '/admin/releases', query: { datasourceId: selectedIssue.datasourceId ? String(selectedIssue.datasourceId) : undefined, snapshotId: String(selectedIssue.snapshotId), tab: 'candidates' } }">回发布流程</RouterLink>
          </div>
          <h3>同表治理记录</h3>
          <p class="muted-text">以下记录来自当前快照同一数据表，后端暂未提供单个问题的独立历史接口。</p>
          <el-table :data="reviewRecords" size="small" stripe>
            <el-table-column prop="action" label="动作" width="110" />
            <el-table-column label="状态" width="150"><template #default="{ row }">{{ row.oldStatus || '-' }} → {{ row.newStatus || '-' }}</template></el-table-column>
            <el-table-column prop="operatorName" label="操作人" width="100" />
            <el-table-column prop="createdAt" label="时间" width="160" />
          </el-table>
          <el-empty v-if="!reviewRecords.length" description="暂无同表治理记录" />
        </div>
      </template>
    </el-drawer>
  </main>
</template>

<style scoped>
.issue-page { display: grid; gap: 16px; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.issue-strip {
  justify-content: flex-start;
  box-shadow: none;
}
.metric-chip.danger {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}
.metric-chip.success {
  color: #15803d;
  background: #f0fdf4;
  border-color: #bbf7d0;
}
.batch-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding: 8px 12px; background: var(--do-primary-soft); border-radius: 6px; font-size: 13px; }
.table-shell {
  border: 1px solid var(--do-line);
  border-radius: 8px;
  overflow: hidden;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.issue-description {
  color: #334155;
  font-size: 13px;
}
.pager { margin-top: 16px; justify-content: flex-end; }
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
</style>
