<script setup lang="ts">
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { computed, reactive, ref, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  updateTableGovernanceStatus,
  updateColumnGovernanceStatus,
  batchUpdateGovernanceStatus,
  listSnapshotTables,
  listSnapshotTableColumns,
  type TableMetaItem,
  type ColumnMetaItem,
} from '../../../api/admin/governance'
import { listSnapshots } from '../../../api/admin/metadata'
import { governanceStatusLabel } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'

const loading = ref(false)
const snapshots = ref<Array<{ id: number; snapshotVersion: number }>>([])
const selectedSnapshotId = ref<number | undefined>()
const tables = ref<TableMetaItem[]>([])
const selectedTable = ref<string>('')
const columns = ref<ColumnMetaItem[]>([])
const adminContext = useAdminContextStore()
const iamS1 = useIamS1Store()
const route = useRoute()
const router = useRouter()
const tableActionLoading = reactive<Record<string, boolean>>({})
const columnActionLoading = reactive<Record<number, boolean>>({})
const batchActionLoading = ref(false)
let datasourceRequestId = 0
let snapshotRequestId = 0
let columnRequestId = 0
let disposed = false

const statusOptions = ['DISCOVERED', 'NORMAL', 'RECOMMENDED', 'DEPRECATED', 'SENSITIVE', 'BLOCKED']

const RULE_MANAGE_FUNCTION = 'governance:rule:manage'
const METADATA_VIEW_FUNCTION = 'metadata:view'

/** 读取快照 / 表 / 字段：要求 `metadata:view` 与当前数据源在同一绑定上成立。 */
const canViewMetadata = computed(
  () => iamS1.canOnDatasource(METADATA_VIEW_FUNCTION, adminContext.datasourceId || undefined),
)

/** 修改表/列治理状态：要求 `governance:rule:manage` 与当前数据源在同一绑定上成立。 */
const canManageStatus = computed(
  () => iamS1.canOnDatasource(RULE_MANAGE_FUNCTION, adminContext.datasourceId || undefined),
)

const NO_STATUS_CAPABILITY_HINT =
  '没有“维护治理规则”能力：需要角色拥有该功能并负责当前数据源。'

async function fetchSnapshots() {
  const currentRequest = ++datasourceRequestId
  snapshotRequestId++
  columnRequestId++
  // 无 `metadata:view` 时不要发这个必然 403 的请求：直接清空并由模板说明原因。
  if (adminContext.datasourceId && !canViewMetadata.value) {
    snapshots.value = []
    selectedSnapshotId.value = undefined
    tables.value = []
    columns.value = []
    selectedTable.value = ''
    return
  }
  if (!adminContext.datasourceId) {
    snapshots.value = []
    selectedSnapshotId.value = undefined
    tables.value = []
    columns.value = []
    selectedTable.value = ''
    return
  }
  const datasourceId = adminContext.datasourceId
  try {
    const res = await listSnapshots({ datasourceId, page: 1, size: 50 })
    if (disposed || currentRequest !== datasourceRequestId || datasourceId !== adminContext.datasourceId) return
    snapshots.value = res.data?.records ?? []
    if (adminContext.snapshotId && snapshots.value.some((item) => item.id === adminContext.snapshotId)) {
      selectedSnapshotId.value = adminContext.snapshotId
    } else {
      selectedSnapshotId.value = snapshots.value[0]?.id
    }
    if (selectedSnapshotId.value) {
      adminContext.selectSnapshot(selectedSnapshotId.value)
      await fetchTables()
    }
  } catch {
    if (disposed || currentRequest !== datasourceRequestId) return
    snapshots.value = []
    selectedSnapshotId.value = undefined
    tables.value = []
    columns.value = []
    selectedTable.value = ''
    ElMessage.error('获取快照列表失败')
  }
}

async function fetchTables() {
  const currentRequest = ++snapshotRequestId
  columnRequestId++
  const snapshotId = selectedSnapshotId.value
  if (!snapshotId) {
    tables.value = []
    columns.value = []
    selectedTable.value = ''
    return
  }
  loading.value = true
  try {
    const res = await listSnapshotTables(snapshotId)
    if (disposed || currentRequest !== snapshotRequestId || snapshotId !== selectedSnapshotId.value) return
    tables.value = res.data ?? []
    columns.value = []
    selectedTable.value = ''
  } finally {
    if (!disposed && currentRequest === snapshotRequestId) loading.value = false
  }
}

function handleSnapshotChange(id?: number) {
  adminContext.selectSnapshot(id)
  router.replace({ query: { ...route.query, snapshotId: id ? String(id) : undefined } })
  fetchTables()
}

async function fetchColumns(tableName: string) {
  const currentRequest = ++columnRequestId
  const snapshotId = selectedSnapshotId.value
  if (!snapshotId) return
  selectedTable.value = tableName
  const res = await listSnapshotTableColumns(snapshotId, tableName)
  if (disposed || currentRequest !== columnRequestId || snapshotId !== selectedSnapshotId.value || tableName !== selectedTable.value) return
  columns.value = res.data ?? []
}

async function changeTableStatus(tableName: string, newStatus: string) {
  if (!selectedSnapshotId.value) return
  if (!canManageStatus.value) {
    ElMessage.warning(NO_STATUS_CAPABILITY_HINT)
    fetchTables()
    return
  }
  if (['DEPRECATED', 'BLOCKED'].includes(newStatus)) {
    try {
      await ElMessageBox.confirm(`确认将表「${tableName}」设置为${governanceStatusLabel(newStatus)}？这可能阻断后续问数和治理发布。`, '确认高影响状态', { type: 'warning' })
    } catch { return }
  }
  tableActionLoading[tableName] = true
  try {
    await updateTableGovernanceStatus(selectedSnapshotId.value, tableName, { governanceStatus: newStatus })
    ElMessage.success(`表 ${tableName} 状态已更新为${governanceStatusLabel(newStatus)}`)
    const table = tables.value.find((item) => item.tableName === tableName)
    if (table) table.governanceStatus = newStatus
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '更新失败')
  } finally {
    tableActionLoading[tableName] = false
  }
}

async function changeColumnStatus(columnId: number, newStatus: string) {
  if (!selectedSnapshotId.value) return
  if (!canManageStatus.value) {
    ElMessage.warning(NO_STATUS_CAPABILITY_HINT)
    fetchColumns(selectedTable.value)
    return
  }
  if (['DEPRECATED', 'BLOCKED'].includes(newStatus)) {
    try {
      await ElMessageBox.confirm(`确认将字段设置为${governanceStatusLabel(newStatus)}？这可能阻断后续问数和治理发布。`, '确认高影响状态', { type: 'warning' })
    } catch { return }
  }
  columnActionLoading[columnId] = true
  try {
    await updateColumnGovernanceStatus(selectedSnapshotId.value, columnId, { governanceStatus: newStatus })
    ElMessage.success('字段状态已更新')
    const column = columns.value.find((item) => item.id === columnId)
    if (column) column.governanceStatus = newStatus
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '更新失败')
  } finally {
    columnActionLoading[columnId] = false
  }
}

async function batchSetColumns(newStatus: string) {
  if (!selectedSnapshotId.value || !selectedTable.value) return
  if (!canManageStatus.value) {
    ElMessage.warning(NO_STATUS_CAPABILITY_HINT)
    return
  }
  if (['DEPRECATED', 'BLOCKED'].includes(newStatus)) {
    try {
      await ElMessageBox.confirm(`确认将表「${selectedTable.value}」下的全部字段设置为${governanceStatusLabel(newStatus)}？`, '确认批量高影响状态', { type: 'warning' })
    } catch { return }
  }
  batchActionLoading.value = true
  try {
    const res = await batchUpdateGovernanceStatus(selectedSnapshotId.value, selectedTable.value, { governanceStatus: newStatus })
    ElMessage.success(`已更新 ${res.data?.updated ?? 0} 个字段`)
    fetchColumns(selectedTable.value)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '批量更新失败')
  } finally {
    batchActionLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([adminContext.initialize(), iamS1.load()])
  await fetchSnapshots()
})

watch(
  () => adminContext.snapshotId,
  (snapshotId) => {
    if (!snapshotId || selectedSnapshotId.value === snapshotId) return
    selectedSnapshotId.value = snapshotId
    fetchTables()
  },
)

onBeforeUnmount(() => {
  disposed = true
  datasourceRequestId++
  snapshotRequestId++
  columnRequestId++
})

watch(
  () => adminContext.datasourceId,
  async () => {
    await fetchSnapshots()
  },
)
</script>

<template>
  <main class="status-page post-login-page">
    <TaskPageHeader title="资产状态" description="维护当前快照中表和字段的治理状态。" />
    <section class="page-actions">
      <el-button :icon="RefreshCw" @click="fetchTables">刷新</el-button>
    </section>

        <section class="toolbar">
          <el-select v-model="selectedSnapshotId" :disabled="!adminContext.datasourceId || !canViewMetadata" placeholder="选择快照" style="width: 220px" @change="handleSnapshotChange">
            <el-option v-for="s in snapshots" :key="s.id" :value="s.id" :label="`快照 #${s.id} 版本 ${s.snapshotVersion}`" />
          </el-select>
          <span v-if="!canViewMetadata" class="capability-hint">
            没有“查看资产结构”能力（<code>metadata:view</code>）：需要角色拥有该功能并负责当前数据源。
          </span>
          <span v-else-if="!canManageStatus" class="capability-hint">
            可以查看表与字段，但没有“维护治理规则”能力，状态修改不可用。
          </span>
        </section>

        <div class="split-layout">
      <!-- 左侧：表列表 -->
      <section class="table-list-panel">
        <h3>表列表</h3>
        <div v-loading="loading" class="table-items">
          <div v-for="t in tables" :key="t.id" class="table-item"
               :class="{ active: selectedTable === t.tableName }"
               @click="fetchColumns(t.tableName)">
            <span class="table-name">{{ t.tableName }}</span>
            <el-select :model-value="t.governanceStatus" :loading="tableActionLoading[t.tableName]" size="small" style="width: 110px"
                       :disabled="!canManageStatus"
                       @change="(v: string) => changeTableStatus(t.tableName, v)">
              <el-option v-for="s in statusOptions" :key="s" :value="s" :label="governanceStatusLabel(s)" />
            </el-select>
          </div>
          <el-empty v-if="!tables.length" description="暂无数据" :image-size="60" />
        </div>
      </section>

      <!-- 右侧：字段列表 -->
      <section class="column-panel" v-if="selectedTable">
        <div class="column-header">
          <h3>{{ selectedTable }} 字段</h3>
          <div class="batch-actions">
            <el-button size="small" :loading="batchActionLoading" :disabled="!canManageStatus" @click="batchSetColumns('NORMAL')">全部设为正常可用</el-button>
            <el-button size="small" type="warning" :loading="batchActionLoading" :disabled="!canManageStatus" @click="batchSetColumns('DEPRECATED')">全部废弃</el-button>
          </div>
        </div>
        <el-table :data="columns" stripe size="small">
          <el-table-column prop="columnName" label="字段名" width="160" />
          <el-table-column prop="dataType" label="类型" width="120" />
          <el-table-column label="治理状态" width="140">
            <template #default="{ row }">
                <el-select :model-value="row.governanceStatus" :loading="columnActionLoading[row.id]" size="small"
                         :disabled="!canManageStatus"
                         @change="(v: string) => changeColumnStatus(row.id, v)">
                <el-option v-for="s in statusOptions" :key="s" :value="s" :label="governanceStatusLabel(s)" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </main>
</template>

<style scoped>
.status-page { display: grid; gap: 16px; }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.capability-hint { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
.capability-hint code { font-size: 12px; }

.split-layout { display: flex; gap: 16px; }
.table-list-panel {
  width: 360px; flex-shrink: 0;
  background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px;
}
.table-list-panel h3 { margin: 0 0 12px; font-size: 14px; }
.table-items { max-height: calc(100vh - 280px); overflow-y: auto; }
.table-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 10px; border-radius: 4px; cursor: pointer; margin-bottom: 4px;
}
.table-item:hover { background: var(--do-bg); }
.table-item.active { background: var(--do-primary-soft); }
.table-name { font-size: 13px; font-weight: 500; }

.column-panel {
  flex: 1; background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px;
}
.column-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.column-header h3 { margin: 0; font-size: 14px; }
.batch-actions { display: flex; gap: 8px; }
</style>
