<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Database, Info, RefreshCw } from 'lucide-vue-next'
import {
  triggerSync,
  listSyncTasks,
  type SyncTaskItem,
  type SyncTriggerPayload
} from '../../../api/admin/metadata'
import { listSimpleDatasources, type DatasourceSimpleItem } from '../../../api/admin/datasource'
import { syncStatusLabel, syncStatusType, syncTriggerLabel } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import ScopeBar from '../../../components/admin/ScopeBar.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'

const loading = ref(false)
const syncLoading = ref(false)
const tasks = ref<SyncTaskItem[]>([])
const total = ref(0)
const errorMessage = ref('')
const retryingTaskId = ref<number>()
const datasources = ref<DatasourceSimpleItem[]>([])
const adminContext = useAdminContextStore()
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const query = reactive({
  datasourceId: undefined as number | undefined,
  page: 1,
  size: 20
})

const syncForm = reactive<SyncTriggerPayload>({
  datasourceId: 0,
  includeStatistics: false
})
const syncDialogVisible = ref(false)
const runningTasks = computed(() => tasks.value.filter((task) => task.status === 'PENDING' || task.status === 'RUNNING'))
const selectedRunningTask = computed(() => runningTasks.value.find((task) => task.datasourceId === syncForm.datasourceId))
const emptyMessage = computed(() => query.datasourceId
  ? '当前数据源范围内暂无采集任务。'
  : '当前范围内暂无采集任务。')
let taskRequestId = 0

function apiError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (error instanceof Error ? error.message : fallback)
}

function progressLabel(task: SyncTaskItem) {
  if (task.status === 'PENDING') return '排队中'
  if (task.status === 'RUNNING') {
    return task.progressTotal ? `${task.progressCurrent || 0}/${task.progressTotal}` : '正在连接数据源…'
  }
  if (task.status === 'SUCCESS') return '已完成'
  if (task.status === 'FAILED' || task.status === 'TIMEOUT') return '执行失败'
  return '—'
}

function scheduleRefresh() {
  if (refreshTimer) clearTimeout(refreshTimer)
  if (!disposed && runningTasks.value.length) refreshTimer = setTimeout(fetchTasks, 3000)
}

async function fetchTasks() {
  const currentRequest = ++taskRequestId
  const params = {
    datasourceId: query.datasourceId,
    page: query.page,
    size: query.size,
  }

  if (refreshTimer) clearTimeout(refreshTimer)
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await listSyncTasks(params)
    if (disposed || currentRequest !== taskRequestId) return
    tasks.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch (error) {
    if (disposed || currentRequest !== taskRequestId) return
    tasks.value = []
    total.value = 0
    errorMessage.value = apiError(error, '采集任务加载失败，请检查后台服务')
  } finally {
    if (!disposed && currentRequest === taskRequestId) {
      loading.value = false
      scheduleRefresh()
    }
  }
}

async function fetchDatasources() {
  try {
    const res = await listSimpleDatasources()
    datasources.value = res.data ?? []
  } catch {
    ElMessage.error('数据源列表加载失败')
  }
}

function openSyncDialog() {
  syncForm.datasourceId = adminContext.datasourceId ?? 0
  syncForm.includeStatistics = false
  syncDialogVisible.value = true
}

async function handleSync() {
  if (!syncForm.datasourceId) {
    ElMessage.warning('请选择数据源')
    return
  }
  syncLoading.value = true
  try {
    await triggerSync(syncForm)
    ElMessage.success('采集任务已发起')
    syncDialogVisible.value = false
    await adminContext.selectDatasource(syncForm.datasourceId)
    await fetchTasks()
  } catch (error) {
    ElMessage.error(apiError(error, '发起采集失败'))
  } finally {
    syncLoading.value = false
  }
}

async function retryTask(task: SyncTaskItem) {
  if (!task.datasourceId || retryingTaskId.value) return
  retryingTaskId.value = task.id
  try {
    await triggerSync({ datasourceId: task.datasourceId, includeStatistics: false })
    ElMessage.success('已重新触发采集任务')
    await fetchTasks()
  } catch (error) {
    ElMessage.error(apiError(error, '重试采集失败'))
  } finally {
    retryingTaskId.value = undefined
  }
}

onMounted(async () => {
  try {
    await adminContext.initialize()
  } catch {
    ElMessage.error('数据源范围加载失败，请检查后台服务')
  }
  query.datasourceId = adminContext.datasourceId
  await Promise.all([fetchTasks(), fetchDatasources()])
})

watch(
  () => adminContext.datasourceId,
  (datasourceId) => {
    if (query.datasourceId === datasourceId) return
    query.datasourceId = datasourceId
    query.page = 1
    fetchTasks()
  },
)

onBeforeUnmount(() => {
  disposed = true
  taskRequestId++
  if (refreshTimer) clearTimeout(refreshTimer)
})

defineExpose({ openSyncDialog })
</script>

<template>
  <main class="sync-task-page post-login-page">
    <section class="collections-task do-glass-panel">
      <div class="collections-task__toolbar">
        <div class="collections-task__scope">
          <ScopeBar mode="datasource" refresh-label="刷新数据源列表" />
        </div>
        <el-button class="collections-task__refresh" :icon="RefreshCw" @click="fetchTasks">刷新任务</el-button>
      </div>

      <p class="collections-task__note"><Info :size="14" aria-hidden="true" />采集成功仅表示任务完成，快照仍需治理检查与审核发布。</p>

      <ErrorState v-if="errorMessage" :message="errorMessage" @retry="fetchTasks" />
      <LoadingState v-else-if="loading && !tasks.length" variant="skeleton" :rows="5" />
      <template v-else-if="tasks.length">
        <el-table :data="tasks" v-loading="loading">
          <el-table-column prop="datasourceName" label="数据源" width="160" show-overflow-tooltip />
          <el-table-column prop="triggerType" label="触发方式" width="100">
            <template #default="{ row }">
              {{ syncTriggerLabel(row.triggerType) }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="syncStatusType(row.status)" size="small">{{ syncStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="120">
            <template #default="{ row }">
              <span>{{ progressLabel(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="startedAt" label="开始时间" width="170" />
          <el-table-column prop="finishedAt" label="完成时间" width="170" />
          <el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip />
          <el-table-column label="后续操作" width="250" fixed="right">
            <template #default="{ row }">
              <div class="task-actions">
                <el-button
                  v-if="row.status === 'FAILED' || row.status === 'TIMEOUT'"
                  link
                  type="warning"
                  :loading="retryingTaskId === row.id"
                  @click="retryTask(row)"
                >重试</el-button>
                <RouterLink
                  v-if="(row.status === 'FAILED' || row.status === 'TIMEOUT') && row.datasourceId"
                  class="table-link"
                  :to="`/admin/data-sources/${row.datasourceId}?action=test`"
                >检查连接</RouterLink>
                <RouterLink v-if="row.status === 'SUCCESS' && row.snapshotId" class="table-link" :to="`/admin/releases/snapshots/${row.snapshotId}`">查看生成快照</RouterLink>
                <RouterLink v-else-if="row.status === 'SUCCESS' && row.datasourceId" class="table-link" :to="{ path: '/admin/releases', query: { datasourceId: String(row.datasourceId) } }">查看该数据源版本</RouterLink>
                <span v-if="row.status === 'PENDING' || row.status === 'RUNNING'" class="muted">任务执行中</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="total > 0" class="collections-task__pagination">
          <el-pagination background layout="total, prev, pager, next"
                         :total="total" :page-size="query.size"
                         v-model:current-page="query.page" @current-change="fetchTasks" />
        </div>
      </template>
      <EmptyState v-else :message="emptyMessage" />
    </section>

    <el-dialog v-model="syncDialogVisible" title="发起全量采集" width="440px">
      <el-form label-width="100px">
        <el-form-item label="数据源">
          <el-select v-model="syncForm.datasourceId" placeholder="选择数据源" style="width: 100%">
            <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id">
              <Database :size="14" style="margin-right: 6px; vertical-align: middle" />{{ ds.name }}
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="采集统计">
          <el-switch v-model="syncForm.includeStatistics" />
          <span style="margin-left: 8px; color: var(--do-muted); font-size: 12px">包含空值率、TopN等统计信息（耗时较长）</span>
        </el-form-item>
        <el-alert v-if="selectedRunningTask" title="该数据源已有采集任务运行中，请等待当前任务完成。" type="warning" :closable="false" show-icon />
      </el-form>
      <template #footer>
        <el-button @click="syncDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="syncLoading" :disabled="Boolean(selectedRunningTask)" @click="handleSync">开始采集</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.sync-task-page { display: block; }
.collections-task { min-width: 0; padding: 0; overflow: hidden; border-color: var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-glass-panel-bg); box-shadow: var(--do-shadow); }

.collections-task__toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 12px; padding: 12px 16px; border-bottom: 1px solid var(--do-line); }
.collections-task__scope { min-width: 0; flex: 1 1 auto; display: flex; }
.collections-task__scope :deep(.scope-bar) { display: block; width: 100%; margin: 0; padding: 0; border: 0; border-radius: 0; background: transparent; }
.collections-task__scope :deep(.scope-bar__intro) { display: none; }
.collections-task__scope :deep(.scope-bar__controls) { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.collections-task__scope :deep(.scope-bar__controls .el-select) { flex: 0 1 440px; min-width: 320px; max-width: 440px; }
.collections-task__scope :deep(.scope-bar__controls .el-select__wrapper),
.collections-task__scope :deep(.scope-bar__controls .el-button) { min-height: 36px; }
.collections-task__scope :deep(.scope-bar__warning) { margin-top: 6px; }
.collections-task__refresh.el-button { flex: 0 0 auto; height: 36px; margin-left: auto; }

.collections-task__note { display: flex; align-items: center; gap: 6px; margin: 0; padding: 9px 16px; border-bottom: 1px solid var(--do-line); color: var(--do-muted); background: color-mix(in srgb, var(--do-primary) 4%, white); font-size: 12px; line-height: 1.5; }
.collections-task__note svg { flex: 0 0 auto; color: var(--do-primary-strong); }

.collections-task :deep(.el-table) {
  --el-table-border-color: var(--do-line);
  --el-table-header-bg-color: var(--do-soft);
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--do-primary) 5%, var(--do-surface));
  color: var(--do-ink);
}
.collections-task :deep(.el-table::before),
.collections-task :deep(.el-table__inner-wrapper::before) { display: none; }
.collections-task :deep(.el-table th.el-table__cell) { height: 42px; padding: 0; background: var(--do-soft); color: var(--do-muted); font-size: 12px; font-weight: 600; }
.collections-task :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 42px; }
.collections-task :deep(.el-table td.el-table__cell) { padding: 14px 0; vertical-align: middle; border-bottom-color: var(--do-line); }
.collections-task :deep(.el-table td.el-table__cell .cell) { padding: 0 12px; line-height: 1.45; }
.collections-task :deep(.el-table th.el-table__cell:first-child .cell),
.collections-task :deep(.el-table td.el-table__cell:first-child .cell) { padding-left: 16px; }
.collections-task :deep(.el-table th.el-table__cell:last-child .cell),
.collections-task :deep(.el-table td.el-table__cell:last-child .cell) { padding-right: 16px; }
.collections-task :deep(.el-table__body tr:last-child td.el-table__cell) { border-bottom: 0; }

.collections-task__pagination { display: flex; justify-content: flex-end; flex-wrap: wrap; padding: 10px 16px 12px; border-top: 1px solid var(--do-line); }
.task-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.table-link { color: var(--do-primary-strong); font-size: 12px; white-space: nowrap; }
.muted { color: var(--do-muted); font-size: 12px; }

@media (max-width: 900px) {
  .collections-task__toolbar { padding: 10px 12px; gap: 8px; }
  .collections-task__scope { flex: 1 1 100%; }
  .collections-task__scope :deep(.scope-bar__controls .el-select) { flex: 1 1 100%; min-width: 0; max-width: none; }
  .collections-task__refresh { margin-left: 0; }
}
</style>
