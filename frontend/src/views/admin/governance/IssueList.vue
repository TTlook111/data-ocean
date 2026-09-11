<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  listQualityIssues,
  handleIssue,
  batchHandleIssues,
  listReviewRecords,
  type ReviewRecord,
  type QualityIssueItem
} from '../../../api/admin/governance'
import {
  issueStatusLabel,
  issueStatusType,
  qualityDimensionLabel,
  severityLabel,
} from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
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
let issueRequestId = 0
let detailRequestId = 0
let disposed = false
const adminContext = useAdminContextStore()
const route = useRoute()
const router = useRouter()

const query = reactive({
  snapshotId: undefined as number | undefined,
  dimension: String(route.query.dimension || ''),
  severity: String(route.query.severity || ''),
  status: String(route.query.status || ''),
  tableName: String(route.query.tableName || ''),
  page: Number(route.query.page) || 1,
  size: 20
})

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

async function doBatchHandle(status: string) {
  if (!selectedIds.value.length) { ElMessage.warning('请选择问题'); return }
  try {
    const res = await batchHandleIssues({ issueIds: selectedIds.value, status })
    ElMessage.success(`已处理 ${res.data?.updated ?? 0} 条`)
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
</style>
