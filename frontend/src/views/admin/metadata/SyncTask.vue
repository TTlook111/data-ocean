<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Database, Info, RefreshCw } from 'lucide-vue-next'
import { triggerSync, listSyncTasks, type SyncTaskItem, type SyncTriggerPayload } from '../../../api/admin/metadata'
import { syncStatusLabel, syncStatusType, syncTriggerLabel } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import ScopeBar from '../../../components/admin/ScopeBar.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'

const route = useRoute()
const router = useRouter()
const adminContext = useAdminContextStore()
const iam = useIamS1Store()
const auth = useAuthStore()
const loading = ref(false)
const syncLoading = ref(false)
const tasks = ref<SyncTaskItem[]>([])
const total = ref(0)
const errorMessage = ref('')
const retryingTaskId = ref<number>()
const query = reactive({ datasourceId: undefined as number | undefined, page: 1, size: 20 })
const syncForm = reactive<SyncTriggerPayload>({ datasourceId: 0, includeStatistics: false })
const syncDialogVisible = ref(false)
const runSources = computed(() => adminContext.datasources.filter(ds => can('metadata:collect:run', ds.id)))
const canRead = computed(() => iam.loaded && !iam.errorMessage && (query.datasourceId
  ? iam.canOnDatasource('metadata:collect:view', query.datasourceId)
  : iam.hasGlobal('metadata:collect:view') || iam.datasourceCapabilities.some(ds => ds.functionCodes.includes('metadata:collect:view'))))
const runningTasks = computed(() => tasks.value.filter(task => task.status === 'PENDING' || task.status === 'RUNNING'))
const selectedRunningTask = computed(() => runningTasks.value.find(task => task.datasourceId === syncForm.datasourceId))
const emptyMessage = computed(() => query.datasourceId ? '当前数据源范围内暂无采集任务。' : '当前范围内暂无采集任务。')
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let taskRequestId = 0
let actionGeneration = 0
useAdminScrollLock(() => syncDialogVisible.value)
function can(code: string, id?: number) { return iam.loaded && !iam.errorMessage && iam.canOnDatasource(code, id) }
function apiError(error: unknown, fallback: string) {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message || (error instanceof Error ? error.message : fallback)
}
function progressLabel(task: SyncTaskItem) {
  if (task.status === 'PENDING') return '等待执行'
  if (task.status === 'RUNNING') return task.progressTotal ? `${task.progressCurrent || 0} / ${task.progressTotal}` : '正在连接数据源…'
  if (task.status === 'SUCCESS') return task.snapshotId ? '采集已完成，请核对生成快照' : '采集已完成，生成快照身份未返回'
  if (task.status === 'FAILED' || task.status === 'TIMEOUT') return task.errorMessage || '采集未完成，请检查连接或重试'
  return '状态由后台返回'
}
function scheduleRefresh() {
  if (refreshTimer) clearTimeout(refreshTimer)
  if (!disposed && canRead.value && runningTasks.value.length) refreshTimer = setTimeout(fetchTasks, 3000)
}
async function fetchTasks() {
  const request = ++taskRequestId, token = auth.token
  if (refreshTimer) clearTimeout(refreshTimer)
  if (!canRead.value) { tasks.value = []; total.value = 0; loading.value = false; errorMessage.value = ''; return }
  const params = { ...query }
  const current = () => !disposed && request === taskRequestId && token === auth.token && canRead.value
  loading.value = true; errorMessage.value = ''
  try {
    const res = await listSyncTasks(params)
    if (!current()) return
    const records = res.data?.records ?? []
    if (params.datasourceId && records.some(row => row.datasourceId !== params.datasourceId)) throw new Error('任务来源与当前数据源不一致，请刷新重试。')
    tasks.value = records; total.value = res.data?.total ?? 0
  } catch (error) {
    if (!current()) return
    tasks.value = []; total.value = 0; errorMessage.value = apiError(error, '采集任务加载失败，请检查后台服务')
  } finally { if (current()) { loading.value = false; scheduleRefresh() } }
}
function openSyncDialog() {
  if (syncLoading.value || retryingTaskId.value || !runSources.value.length) return
  actionGeneration++
  syncForm.datasourceId = runSources.value.some(ds => ds.id === adminContext.datasourceId) ? adminContext.datasourceId! : 0
  syncForm.includeStatistics = false; syncDialogVisible.value = true
}
async function submitSync(payload: SyncTriggerPayload, retryId?: number) {
  if (syncLoading.value || retryingTaskId.value || !can('metadata:collect:run', payload.datasourceId)) return
  if (runningTasks.value.some(task => task.datasourceId === payload.datasourceId)) { ElMessage.warning('当前列表中该数据源已有任务执行中，请等待完成。'); return }
  const generation = actionGeneration, token = auth.token
  const current = () => !disposed && generation === actionGeneration && token === auth.token && can('metadata:collect:run', payload.datasourceId)
  if (retryId) retryingTaskId.value = retryId
  else syncLoading.value = true
  try {
    const result = await triggerSync({ ...payload })
    if (!current()) return
    syncDialogVisible.value = false
    const taskLabel = result.data?.taskId ? ` #${result.data.taskId}` : ''
    if (!can('metadata:collect:view', payload.datasourceId)) {
      ElMessage.success(`采集请求${taskLabel}已提交。当前账号没有该源任务查看权限，请联系负责人确认结果。`)
      return
    }
    ElMessage.success(`采集请求${taskLabel}已提交，请在该数据源任务列表确认结果。`)
    if (payload.datasourceId !== query.datasourceId) {
      await router.replace({ query: { ...route.query, datasourceId: String(payload.datasourceId) } })
    } else await fetchTasks()
  } catch (error) { if (current()) ElMessage.error(apiError(error, '发起采集失败')) }
  finally { if (!disposed && generation === actionGeneration && token === auth.token) { syncLoading.value = false; retryingTaskId.value = undefined } }
}
function handleSync() {
  if (!syncForm.datasourceId) { ElMessage.warning('请选择有执行权限的数据源'); return }
  submitSync({ ...syncForm })
}
function retryTask(task: SyncTaskItem) { submitSync({ datasourceId: task.datasourceId, includeStatistics: false }, task.id) }
function invalidate() {
  taskRequestId++; actionGeneration++
  if (refreshTimer) clearTimeout(refreshTimer)
  tasks.value = []; total.value = 0; syncDialogVisible.value = false; syncLoading.value = false; retryingTaskId.value = undefined
}
onMounted(async () => {
  const token = auth.token
  try { await adminContext.initialize() } catch (error) { if (!disposed && token === auth.token) errorMessage.value = apiError(error, '数据源范围加载失败') }
  if (disposed || token !== auth.token) return
  query.datasourceId = adminContext.datasourceId
  await fetchTasks()
})
watch(() => adminContext.datasourceId, id => {
  if (query.datasourceId === id) return
  invalidate(); query.datasourceId = id; query.page = 1; fetchTasks()
}, { flush: 'sync' })
watch(() => auth.token, () => { invalidate(); query.page = 1; fetchTasks() }, { flush: 'sync' })
watch(() => iam.snapshot, () => { invalidate(); fetchTasks() }, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; invalidate() })
defineExpose({ openSyncDialog })
</script>

<template>
  <main class="sync-task-page post-login-page">
    <section class="collection-purpose" aria-label="采集工作说明">
      <div><span class="collection-purpose__number">01</span><strong>读取数据库结构</strong><p>表、字段与关系；统计信息按需采集。</p></div>
      <div><span class="collection-purpose__number">02</span><strong>形成独立快照</strong><p>任务成功后查看本次生成的精确版本。</p></div>
      <div><span class="collection-purpose__number">03</span><strong>进入治理与审核</strong><p>发布、知识准备和索引构建各自确认。</p></div>
    </section>
    <section class="collections-task">
      <div class="collections-task__toolbar">
        <div class="collections-task__scope">
          <ScopeBar mode="datasource" refresh-label="刷新数据源列表" />
        </div>
        <el-button class="collections-task__refresh" :icon="RefreshCw" @click="fetchTasks">刷新任务</el-button>
      </div>

      <p class="collections-task__note"><Info :size="14" aria-hidden="true" />采集成功仅表示任务完成，快照仍需治理检查与审核发布。</p>

      <ErrorState v-if="!canRead" message="没有当前范围的采集任务查看权限。执行采集是独立能力。" @retry="iam.load(true)" />
      <ErrorState v-else-if="errorMessage" :message="errorMessage" @retry="fetchTasks" />
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
          <el-table-column label="执行结果与下一步" min-width="250">
            <template #default="{ row }"><span class="task-outcome" :class="{ 'task-outcome--error': row.status === 'FAILED' || row.status === 'TIMEOUT' }">{{ progressLabel(row) }}</span><span v-if="row.snapshotId" class="task-id">快照 #{{ row.snapshotId }}</span></template>
          </el-table-column>
          <el-table-column label="执行时间" width="175"><template #default="{ row }"><span>{{ row.startedAt || '尚未开始' }}</span><span class="task-id">{{ row.finishedAt ? `完成 ${row.finishedAt}` : '尚未结束' }}</span></template></el-table-column>
          <el-table-column label="后续操作" width="250" fixed="right">
            <template #default="{ row }">
              <div class="task-actions">
                <el-button
                  v-if="(row.status === 'FAILED' || row.status === 'TIMEOUT') && can('metadata:collect:run', row.datasourceId)"
                  link
                  type="warning"
                  :loading="retryingTaskId === row.id" :disabled="Boolean(retryingTaskId || syncLoading)"
                  @click="retryTask(row)"
                >重试</el-button>
                <RouterLink
                  v-if="(row.status === 'FAILED' || row.status === 'TIMEOUT') && can('datasource:view', row.datasourceId) && can('datasource:manage', row.datasourceId)"
                  class="table-link"
                  :to="`/admin/data-sources/${row.datasourceId}?action=test`"
                >检查连接</RouterLink>
                <RouterLink v-if="row.status === 'SUCCESS' && row.snapshotId && can('metadata:view', row.datasourceId)" class="table-link" :to="{ path: `/admin/releases/snapshots/${row.snapshotId}`, query: { datasourceId: String(row.datasourceId) } }">查看生成快照</RouterLink>
                <RouterLink v-else-if="row.status === 'SUCCESS' && can('metadata:release:view', row.datasourceId)" class="table-link" :to="{ path: '/admin/releases', query: { datasourceId: String(row.datasourceId) } }">查看该数据源版本</RouterLink>
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

    <el-dialog v-model="syncDialogVisible" title="发起全量采集" width="520px" class="collection-dialog" :close-on-click-modal="!syncLoading" :close-on-press-escape="!syncLoading" :show-close="!syncLoading">
      <p class="collection-dialog__intro">从只读数据源收集结构，生成新快照。不会发布快照或自动构建索引。</p>
      <el-form label-position="top" :disabled="syncLoading">
        <el-form-item label="数据源">
          <el-select aria-label="采集数据源" v-model="syncForm.datasourceId" placeholder="选择数据源" style="width: 100%">
            <el-option v-for="ds in runSources" :key="ds.id" :label="ds.name" :value="ds.id">
              <Database :size="14" style="margin-right: 6px; vertical-align: middle" />{{ ds.name }}
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="采集统计">
          <el-switch aria-label="包含采集统计" v-model="syncForm.includeStatistics" />
          <span style="margin-left: 8px; color: var(--do-muted); font-size: 12px">包含空值率、TopN等统计信息（耗时较长）</span>
        </el-form-item>
        <el-alert v-if="selectedRunningTask" title="该数据源已有采集任务运行中，请等待当前任务完成。" type="warning" :closable="false" show-icon />
      </el-form>
      <template #footer>
        <el-button :disabled="syncLoading" @click="syncDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="syncLoading" :disabled="Boolean(selectedRunningTask) || !can('metadata:collect:run', syncForm.datasourceId) || Boolean(retryingTaskId)" @click="handleSync">开始采集</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.sync-task-page { display: grid; gap: 20px; }
.collection-purpose { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); border-block: 1px solid var(--do-line); padding: 18px 0; }
.collection-purpose > div { padding: 0 20px; border-right: 1px solid var(--do-line); }
.collection-purpose > div:first-child { padding-left: 0; }
.collection-purpose > div:last-child { border-right: 0; }
.collection-purpose__number { font: 12px monospace; color: var(--do-primary-strong); margin-right: 12px; }
.collection-purpose strong { font-size: 14px; font-weight: 600; }
.collection-purpose p { margin: 6px 0 0 28px; font-size: 12px; color: var(--do-muted); line-height: 1.6; }
.task-id { display: block; margin-top: 5px; color: var(--do-muted); font-size: 11px; }
.task-outcome { line-height: 1.65; }
.task-outcome--error { color: var(--do-danger); }
.collection-dialog__intro { color: var(--do-muted); font-size: 13px; line-height: 1.7; margin: 0 0 20px; }
@media(max-width:640px) { .collection-purpose { grid-template-columns:1fr; gap:16px; } .collection-purpose > div { padding:0; border:0; } }
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
