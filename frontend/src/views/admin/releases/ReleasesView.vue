<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, GitCompareArrows, RefreshCw, Send, RotateCcw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useAdminContextStore } from '../../../stores/adminContext'
import { snapshotStatusLabel } from '../../../utils/enumLabels'
import { listSnapshots, getSnapshotDetail, type SnapshotDetail, type SnapshotItem } from '../../../api/admin/metadata'
import {
  changeSnapshotStatus,
  compareSnapshots,
  getPublishedSnapshot,
  listSnapshotAuditLogs,
  listVersionHistory,
  publishSnapshot,
  revokeSnapshot,
  type AuditLogItem,
  type VersionHistoryItem,
} from '../../../api/admin/versioning'
import { triggerQualityCheck } from '../../../api/admin/governance'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import ActivityTimeline from '../../../components/admin/ActivityTimeline.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const context = useAdminContextStore()
const activeTab = ref(String(route.query.tab || 'candidates'))
const history = ref<VersionHistoryItem[]>([])
const snapshots = ref<SnapshotItem[]>([])
const published = ref<VersionHistoryItem | null>(null)
const selected = ref<SnapshotDetail | null>(null)
const auditLogs = ref<AuditLogItem[]>([])
const oldId = ref<number | undefined>(Number(route.query.oldId) || undefined)
const newId = ref<number | undefined>(Number(route.query.newId) || undefined)
const diff = ref<any>(null)
const loading = ref(true)
const actionLoading = ref(false)
const error = ref('')
const total = ref(0)
const page = ref(1)
const auditVisible = ref(false)
const requestId = ref(0)

const datasourceId = computed(() => context.datasourceId)
const selectedId = computed(() => Number(route.query.snapshotId) || undefined)
const timelineItems = computed(() => history.value.map((item) => ({
  time: item.createdAt,
  action: '快照 v' + item.snapshotVersion + ' · ' + item.status,
  result: item.tableCount + ' 表 / ' + item.columnCount + ' 字段',
})))

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function selectTab(tab: string) {
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } })
}

async function load() {
  if (!datasourceId.value) {
    history.value = []
    snapshots.value = []
    published.value = null
    selected.value = null
    loading.value = false
    return
  }
  const currentRequest = ++requestId.value
  loading.value = true
  error.value = ''
  try {
    const [historyResult, snapshotResult, publishedResult] = await Promise.all([
      listVersionHistory(datasourceId.value, { page: page.value, size: 20 }),
      listSnapshots({ datasourceId: datasourceId.value, page: 1, size: 50 }),
      getPublishedSnapshot(datasourceId.value),
    ])
    if (currentRequest !== requestId.value) return
    history.value = historyResult.data.records || []
    total.value = historyResult.data.total || 0
    snapshots.value = snapshotResult.data.records || []
    published.value = publishedResult.data || null
    if (selectedId.value) await loadSelected(selectedId.value)
    if (activeTab.value === 'diff') await loadDiff()
  } catch (cause) {
    if (currentRequest !== requestId.value) return
    error.value = apiError(cause, '版本发布数据加载失败')
  } finally {
    if (currentRequest === requestId.value) loading.value = false
  }
}

async function loadSelected(id: number) {
  selected.value = (await getSnapshotDetail(id)).data
}

async function selectSnapshot(id: number) {
  await router.replace({ query: { ...route.query, snapshotId: String(id) } })
  try {
    await loadSelected(id)
  } catch (cause) {
    ElMessage.error(apiError(cause, '快照详情加载失败'))
  }
}

/**
 * ISSUE_FOUND 快照的主操作入口：进入问题中心。
 *
 * 开发指导 §7.6 规定该状态的主操作是「进入问题中心」，实施任务清单 §9 门禁表
 * 也要求「存在阻断治理问题」时突出该入口、不得把发布类操作显示为可执行。
 * 不带 severity 等额外筛选——§7.6 只要求进入问题中心，加筛选会隐藏中低危问题。
 */
function issueCenterTarget(row: VersionHistoryItem) {
  const scopedDatasourceId = row.datasourceId ?? datasourceId.value
  return {
    path: '/admin/governance/issues',
    query: {
      ...(scopedDatasourceId ? { datasourceId: String(scopedDatasourceId) } : {}),
      snapshotId: String(row.snapshotId),
    },
  }
}

async function changeStatus(item: VersionHistoryItem, targetStatus: string) {
  const labels: Record<string, string> = { CHECKING: '开始质量检查', APPROVED: '批准快照' }
  if (actionLoading.value) return
  // ISSUE_FOUND → APPROVED 不可逆：状态机没有回到 ISSUE_FOUND 的边，且未解决的高危
  // 治理问题仍会阻止后续发布。必须显式说明影响（开发指导 §16.5）。
  const irreversibleApproval = item.status === 'ISSUE_FOUND' && targetStatus === 'APPROVED'
  const confirmMessage = irreversibleApproval
    ? '快照 v' + item.snapshotVersion + ' 仍有高危未解决的治理问题。批准后仍无法发布，且该状态不可退回，请先到问题中心处理。确认继续批准？'
    : '确认对快照 v' + item.snapshotVersion + '执行“' + labels[targetStatus] + '”？'
  try {
    await ElMessageBox.confirm(confirmMessage, irreversibleApproval ? '批准存在治理问题的快照' : '确认操作', { type: 'warning' })
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
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '快照状态更新失败'))
  } finally {
    actionLoading.value = false
  }
}

async function publish(item: VersionHistoryItem) {
  try {
    await ElMessageBox.confirm('发布 v' + item.snapshotVersion + ' 后，旧已发布版本会自动过期。确认继续？', '发布快照', { type: 'warning' })
    actionLoading.value = true
    await publishSnapshot(item.snapshotId)
    ElMessage.success('快照已发布')
    await load()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '快照发布失败'))
  } finally {
    actionLoading.value = false
  }
}

async function revoke(item: VersionHistoryItem) {
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
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '快照撤回失败'))
  } finally {
    actionLoading.value = false
  }
}

async function showAudit(item: VersionHistoryItem) {
  auditVisible.value = true
  try {
    auditLogs.value = (await listSnapshotAuditLogs(item.snapshotId, { page: 1, size: 50 })).data.records || []
  } catch (cause) {
    ElMessage.error(apiError(cause, '审计记录加载失败'))
  }
}

async function openDiff() {
  if (!oldId.value || !newId.value || oldId.value === newId.value) {
    ElMessage.warning('请选择两个不同版本')
    return
  }
  const oldSnapshot = snapshots.value.find((item) => item.id === oldId.value)
  const newSnapshot = snapshots.value.find((item) => item.id === newId.value)
  if (!oldSnapshot || !newSnapshot || oldSnapshot.datasourceId !== newSnapshot.datasourceId) {
    ElMessage.error('两个快照必须属于同一个数据源')
    return
  }
  await router.push('/admin/releases/snapshots/' + oldId.value + '/diff/' + newId.value)
}

async function loadDiff() {
  if (!oldId.value || !newId.value) return
  const oldSnapshot = snapshots.value.find((item) => item.id === oldId.value)
  const newSnapshot = snapshots.value.find((item) => item.id === newId.value)
  if (!oldSnapshot || !newSnapshot || oldSnapshot.datasourceId !== newSnapshot.datasourceId) {
    diff.value = null
    ElMessage.error('两个快照必须属于同一个数据源')
    return
  }
  try {
    diff.value = (await compareSnapshots(oldId.value, newId.value)).data
  } catch (cause) {
    diff.value = null
    ElMessage.error(apiError(cause, '版本差异加载失败'))
  }
}

onMounted(async () => {
  try {
    await context.initialize()
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '数据源范围加载失败'
    loading.value = false
  }
})

watch(() => context.datasourceId, () => {
  // 换数据源必须回到第 1 页：否则停在高页码时，新数据源很可能不足该页，
  // 「版本历史」与复用同一份 history 的「候选快照」表都会显示成空。
  page.value = 1
  load()
})
watch(() => route.query.tab, (value) => { activeTab.value = String(value || 'candidates') })
watch(() => [route.query.oldId, route.query.newId, route.query.tab], async () => {
  oldId.value = Number(route.query.oldId) || undefined
  newId.value = Number(route.query.newId) || undefined
  activeTab.value = String(route.query.tab || activeTab.value || 'candidates')
  if (activeTab.value === 'diff') {
    await loadDiff()
  }
})
</script>

<template>
  <div class="admin-page releases-page">
    <ObjectContextSummary v-if="context.currentDatasource" :title="context.currentDatasource.name" description="当前数据源的快照版本轨道" back-to="/admin/data-sources" source-label="数据资产 / 版本发布" />
    <TaskPageHeader
      eyebrow="数据资产"
      title="版本发布"
      description="区分采集、检查、问题处理、批准、发布和撤回。只有服务端允许的状态动作才会显示。"
    >
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新版本</el-button></template>
    </TaskPageHeader>

    <section v-if="published" class="releases-page__published">
      <div><span>当前正式发布</span><strong>v{{ published.snapshotVersion }}</strong><small>{{ published.tableCount }} 表 · {{ published.columnCount }} 字段</small></div>
      <div class="releases-page__published-actions">
        <BusinessStatusBadge status="PUBLISHED" />
        <RouterLink :to="{ path: '/admin/semantics/knowledge', query: { datasourceId: String(datasourceId) } }">
          生成语义知识 →
        </RouterLink>
      </div>
    </section>

    <ErrorState v-if="error" :message="error" @retry="load" />
    <LoadingState v-else-if="loading" variant="skeleton" :rows="7" />
    <EmptyState v-else-if="!datasourceId" message="请先选择数据源，再管理其快照版本。" action-text="去数据源接入" @action="router.push('/admin/data-sources')" />
    <el-tabs v-else :model-value="activeTab" class="releases-page__tabs" @update:model-value="selectTab">
      <el-tab-pane label="候选快照" name="candidates">
        <section class="releases-page__card">
          <el-table v-if="history.length" v-loading="actionLoading" :data="history" stripe>
            <el-table-column label="版本" width="90"><template #default="{ row }">v{{ row.snapshotVersion }}</template></el-table-column>
            <el-table-column label="状态" width="130"><template #default="{ row }"><BusinessStatusBadge :status="row.status" :label="snapshotStatusLabel(row.status)" /></template></el-table-column>
            <el-table-column prop="tableCount" label="表" width="70" />
            <el-table-column prop="columnCount" label="字段" width="80" />
            <el-table-column prop="qualityScore" label="质量分" width="90" />
            <el-table-column prop="createdAt" label="采集时间" width="175" />
            <el-table-column label="操作" min-width="300" fixed="right">
              <template #default="{ row }">
                <el-button link @click="selectSnapshot(row.snapshotId)">详情</el-button>
                <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="changeStatus(row, 'CHECKING')">开始检查</el-button>
                <RouterLink
                  v-if="row.status === 'ISSUE_FOUND'"
                  class="releases-page__primary-link"
                  :to="issueCenterTarget(row)"
                >进入问题中心</RouterLink>
                <el-button v-if="row.status === 'ISSUE_FOUND'" link @click="changeStatus(row, 'APPROVED')">批准</el-button>
                <el-button v-if="row.status === 'APPROVED'" link type="primary" @click="publish(row)"><Send :size="14" />发布</el-button>
                <el-button v-if="row.status === 'PUBLISHED'" link type="danger" @click="revoke(row)"><RotateCcw :size="14" />撤回</el-button>
                <el-button link @click="showAudit(row)"><Clock :size="14" />日志</el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState v-else message="当前数据源暂无快照记录，请先完成元数据采集。" action-text="去采集任务" @action="router.push({ path: '/admin/collections', query: { datasourceId: String(datasourceId) } })" />
        </section>
        <section v-if="selected" class="releases-page__detail">
          <div class="section-heading"><div><h2>快照 v{{ selected.snapshot.snapshotVersion }} 详情</h2><p>查看对象规模后，再进入治理或执行允许的发布动作。</p></div><BusinessStatusBadge :status="selected.snapshot.status" :label="snapshotStatusLabel(selected.snapshot.status)" /></div>
          <div class="detail-metrics"><span>{{ selected.tables.length }} 张表</span><span>{{ selected.columns.length }} 个字段</span><span>质量分：{{ selected.snapshot.qualityScore ?? '待检查' }}</span></div>
          <div class="detail-links">
            <RouterLink :to="'/admin/releases/snapshots/' + selected.snapshot.id">打开可分享详情</RouterLink>
            <RouterLink :to="{ path: '/admin/governance', query: { datasourceId: String(selected.snapshot.datasourceId), snapshotId: String(selected.snapshot.id) } }">进入治理检查</RouterLink>
          </div>
        </section>
      </el-tab-pane>
      <el-tab-pane label="版本历史" name="history">
        <section class="releases-page__card">
          <ActivityTimeline :items="timelineItems" />
          <el-pagination
            v-if="total > 20"
            background
            layout="total, prev, pager, next"
            :total="total"
            :page-size="20"
            v-model:current-page="page"
            @current-change="load"
          />
        </section>
      </el-tab-pane>
      <el-tab-pane label="版本差异" name="diff">
        <section class="releases-page__card diff-panel">
          <div class="diff-selector"><el-select v-model="oldId" placeholder="旧版本"><el-option v-for="item in history" :key="item.snapshotId" :label="'v' + item.snapshotVersion" :value="item.snapshotId" /></el-select><GitCompareArrows :size="18" /><el-select v-model="newId" placeholder="新版本"><el-option v-for="item in history" :key="item.snapshotId" :label="'v' + item.snapshotVersion" :value="item.snapshotId" /></el-select><el-button type="primary" @click="openDiff">打开差异 URL</el-button></div>
          <div v-if="diff" class="diff-summary"><p>新增表：{{ diff.addedTables?.length || 0 }} 个</p><p>删除表：{{ diff.removedTables?.length || 0 }} 个</p><p>新增字段：{{ diff.addedColumns?.length || 0 }} 个</p><p>变更字段：{{ diff.modifiedColumns?.length || 0 }} 个</p></div>
          <EmptyState v-else message="选择两个版本后打开可分享差异 URL。" />
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="auditVisible" title="快照审计记录" width="720px">
      <el-table :data="auditLogs" size="small"><el-table-column prop="action" label="动作" width="150" /><el-table-column prop="operatorName" label="操作人" width="110" /><el-table-column prop="reason" label="原因" /><el-table-column prop="createdAt" label="时间" width="170" /></el-table>
      <EmptyState v-if="!auditLogs.length" message="暂无审计记录" />
    </el-dialog>
  </div>
</template>

<style scoped>
.releases-page { display: grid; gap: 16px; }
.releases-page__published,
.releases-page__card,
.releases-page__detail { padding: 18px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); box-shadow: var(--do-shadow); }
.releases-page__published { display: flex; align-items: center; justify-content: space-between; gap: 12px; background: var(--do-success-soft); border-color: rgba(22, 163, 74, .2); }
.releases-page__published div { display: grid; gap: 4px; }
.releases-page__published span, .releases-page__published small { color: var(--do-muted); font-size: 12px; }
.releases-page__published strong { color: var(--do-ink); font-size: 24px; }
.releases-page__published-actions { display: flex; align-items: center; gap: 14px; }
.releases-page__published-actions a { color: var(--do-primary-strong); font-size: 13px; font-weight: 800; }
.releases-page__tabs :deep(.el-tab-pane) { padding-top: 8px; }
.releases-page__detail { margin-top: 16px; }
.section-heading { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.section-heading h2 { margin: 0; color: var(--do-ink); font-size: 16px; }
.section-heading p { margin: 5px 0 0; color: var(--do-muted); font-size: 12px; }
.detail-metrics, .diff-summary { display: flex; flex-wrap: wrap; gap: 10px; color: var(--do-muted); font-size: 13px; }
.detail-metrics span, .diff-summary p { margin: 0; padding: 8px 10px; border-radius: var(--do-radius-md); background: var(--do-bg); }
.detail-links { display: flex; gap: 16px; margin-top: 14px; }
.detail-links a { color: var(--do-primary-strong); font-size: 13px; font-weight: 800; }
.releases-page__primary-link { margin-left: 8px; color: var(--do-primary-strong); font-size: 12px; font-weight: 700; }
.diff-panel { display: grid; gap: 18px; }
.diff-selector { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.diff-selector :deep(.el-select) { width: 180px; }
</style>
