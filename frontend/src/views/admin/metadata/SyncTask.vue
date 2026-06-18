<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshCw, Play, Database } from 'lucide-vue-next'
import {
  triggerSync,
  listSyncTasks,
  type SyncTaskItem,
  type SyncTriggerPayload
} from '../../../api/admin/metadata'
import { listDatasources, type DatasourceItem } from '../../../api/admin/datasource'
import { syncStatusLabel, syncStatusType, syncTriggerLabel } from '../../../utils/enumLabels'

const loading = ref(false)
const syncLoading = ref(false)
const tasks = ref<SyncTaskItem[]>([])
const total = ref(0)
const datasources = ref<DatasourceItem[]>([])

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

async function fetchTasks() {
  loading.value = true
  try {
    const res = await listSyncTasks(query)
    tasks.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function fetchDatasources() {
  try {
    const res = await listDatasources({ page: 1, pageSize: 200 })
    datasources.value = res.data?.records ?? []
  } catch {
    ElMessage.error('数据源列表加载失败')
  }
}

function openSyncDialog() {
  syncForm.datasourceId = 0
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
    ElMessage.success('同步任务已触发')
    syncDialogVisible.value = false
    fetchTasks()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '触发同步失败')
  } finally {
    syncLoading.value = false
  }
}

onMounted(() => {
  fetchTasks()
  fetchDatasources()
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
                 style="width: 200px" @change="fetchTasks">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-button :icon="RefreshCw" @click="fetchTasks" />
    </section>

    <section class="table-shell">
      <el-table :data="tasks" v-loading="loading" stripe>
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
            <span v-if="row.progressTotal">{{ row.progressCurrent }}/{{ row.progressTotal }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="170" />
        <el-table-column prop="finishedAt" label="完成时间" width="170" />
        <el-table-column prop="errorMessage" label="错误信息" show-overflow-tooltip />
      </el-table>
    </section>

    <el-pagination class="pager" background layout="total, prev, pager, next"
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
      </el-form>
      <template #footer>
        <el-button @click="syncDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="syncLoading" @click="handleSync">开始同步</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.sync-task-page { display: grid; gap: 16px; }
.toolbar { display: flex; gap: 12px; }
.table-shell { border: 1px solid var(--do-line); border-radius: 8px; overflow: hidden; background: var(--do-surface); }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
