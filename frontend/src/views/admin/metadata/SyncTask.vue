<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshCw, Play, Database } from 'lucide-vue-next'
import {
  triggerSync,
  listSyncTasks,
  type SyncTaskItem,
  type SyncTriggerPayload
} from '../../../api/admin/metadata'
import { listSimpleDatasources, type DatasourceSimpleItem } from '../../../api/admin/datasource'
import { syncStatusLabel, syncStatusType, syncTriggerLabel } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

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

async function handleDatasourceChange(id?: number) {
  query.page = 1
  query.datasourceId = id
  await adminContext.selectDatasource(id)
  await fetchTasks()
}

async function handleSync() {
  if (!syncForm.datasourceId) {
    ElMessage.warning('请选择数据源')
    return
  }
  syncLoading.value = true
  try {
    await triggerSync(syncForm)
    ElMessage.success('同步任务已触发')
    syncDialogVisible.value = false
    await adminContext.selectDatasource(syncForm.datasourceId)
    await fetchTasks()
  } catch (error) {
    ElMessage.error(apiError(error, '触发同步失败'))
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
</script>

<template>
  <main class="sync-task-page post-login-page">
    <section class="page-actions">
      <el-button type="primary" @click="openSyncDialog">
        <Play :size="16" style="margin-right: 6px" />触发全量同步
      </el-button>
    </section>

    <section class="toolbar">
      <el-select v-model="query.datasourceId" placeholder="全部数据源" clearable
                 style="width: 200px" @change="handleDatasourceChange">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-button :icon="RefreshCw" @click="fetchTasks" />
    </section>

    <section class="table-shell">
      <ErrorState v-if="errorMessage" :message="errorMessage" @retry="fetchTasks" />
      <el-table v-else-if="tasks.length" :data="tasks" v-loading="loading" stripe>
        <el-table-column prop="datasourceName" label="数据源" width="160" />
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
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else-if="!loading" message="当前筛选条件下暂无采集任务。" />
    </section>

    <el-pagination v-if="!errorMessage && total > 0" class="pager" background layout="total, prev, pager, next"
                   :total="total" :page-size="query.size"
                   v-model:current-page="query.page" @current-change="fetchTasks" />

    <el-dialog v-model="syncDialogVisible" title="触发全量同步" width="440px">
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
        <el-button type="primary" :loading="syncLoading" :disabled="Boolean(selectedRunningTask)" @click="handleSync">开始同步</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.sync-task-page { display: grid; gap: 16px; }
.toolbar { display: flex; gap: 12px; }
.table-shell { border: 1px solid var(--do-line); border-radius: 8px; overflow: hidden; background: var(--do-surface); }
.pager { margin-top: 16px; justify-content: flex-end; }
.table-link { margin-left: 8px; color: var(--do-primary-strong); font-size: 12px; }
.muted { color: var(--do-muted); font-size: 12px; }
</style>
