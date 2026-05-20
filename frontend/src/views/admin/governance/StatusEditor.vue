<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import {
  updateTableGovernanceStatus,
  updateColumnGovernanceStatus,
  batchUpdateGovernanceStatus,
  listSnapshotTables,
  listSnapshotTableColumns,
  type TableMetaItem,
  type ColumnMetaItem
} from '../../../api/admin/governance'
import { listSnapshots } from '../../../api/admin/metadata'

const loading = ref(false)
const snapshots = ref<Array<{ id: number; snapshotVersion: number }>>([])
const selectedSnapshotId = ref<number | undefined>()
const tables = ref<TableMetaItem[]>([])
const selectedTable = ref<string>('')
const columns = ref<ColumnMetaItem[]>([])

const statusOptions = ['DISCOVERED', 'NORMAL', 'RECOMMENDED', 'DEPRECATED', 'SENSITIVE', 'BLOCKED']

async function fetchSnapshots() {
  try {
    const res = await listSnapshots({ page: 1, size: 50 })
    snapshots.value = res.data?.records ?? []
    if (snapshots.value.length && !selectedSnapshotId.value) {
      selectedSnapshotId.value = snapshots.value[0].id
      fetchTables()
    }
  } catch {
    ElMessage.error('获取快照列表失败')
  }
}

async function fetchTables() {
  if (!selectedSnapshotId.value) return
  loading.value = true
  try {
    const res = await listSnapshotTables(selectedSnapshotId.value)
    tables.value = res.data ?? []
  } finally {
    loading.value = false
  }
}

async function fetchColumns(tableName: string) {
  if (!selectedSnapshotId.value) return
  selectedTable.value = tableName
  const res = await listSnapshotTableColumns(selectedSnapshotId.value, tableName)
  columns.value = res.data ?? []
}

async function changeTableStatus(tableName: string, newStatus: string) {
  if (!selectedSnapshotId.value) return
  try {
    await updateTableGovernanceStatus(selectedSnapshotId.value, tableName, { governanceStatus: newStatus })
    ElMessage.success(`表 ${tableName} 状态已更新为 ${newStatus}`)
    fetchTables()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '更新失败')
  }
}

async function changeColumnStatus(columnId: number, newStatus: string) {
  if (!selectedSnapshotId.value) return
  try {
    await updateColumnGovernanceStatus(selectedSnapshotId.value, columnId, { governanceStatus: newStatus })
    ElMessage.success('字段状态已更新')
    fetchColumns(selectedTable.value)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '更新失败')
  }
}

async function batchSetColumns(newStatus: string) {
  if (!selectedSnapshotId.value || !selectedTable.value) return
  try {
    const res = await batchUpdateGovernanceStatus(selectedSnapshotId.value, selectedTable.value, { governanceStatus: newStatus })
    ElMessage.success(`已更新 ${res.data?.updated ?? 0} 个字段`)
    fetchColumns(selectedTable.value)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '批量更新失败')
  }
}

onMounted(fetchSnapshots)
</script>

<template>
  <main class="status-page post-login-page">
    <header class="page-header">
      <div>
        <p>元数据治理</p>
        <h1>治理状态</h1>
        <span class="header-subtitle">管理表和字段的治理状态，控制 RAG 准入</span>
      </div>
      <el-button :icon="RefreshCw" @click="fetchTables">刷新</el-button>
    </header>

    <section class="toolbar">
      <el-select v-model="selectedSnapshotId" placeholder="选择快照" style="width: 220px" @change="fetchTables">
        <el-option v-for="s in snapshots" :key="s.id" :value="s.id" :label="`快照 #${s.id} v${s.snapshotVersion}`" />
      </el-select>
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
            <el-select :model-value="t.governanceStatus" size="small" style="width: 110px"
                       @change="(v: string) => changeTableStatus(t.tableName, v)">
              <el-option v-for="s in statusOptions" :key="s" :value="s" :label="s" />
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
            <el-button size="small" @click="batchSetColumns('NORMAL')">全部设为 NORMAL</el-button>
            <el-button size="small" type="warning" @click="batchSetColumns('DEPRECATED')">全部废弃</el-button>
          </div>
        </div>
        <el-table :data="columns" stripe size="small">
          <el-table-column prop="columnName" label="字段名" width="160" />
          <el-table-column prop="dataType" label="类型" width="120" />
          <el-table-column label="治理状态" width="140">
            <template #default="{ row }">
              <el-select :model-value="row.governanceStatus" size="small"
                         @change="(v: string) => changeColumnStatus(row.id, v)">
                <el-option v-for="s in statusOptions" :key="s" :value="s" :label="s" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </main>
</template>

<style scoped>
.status-page { padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.toolbar { margin-bottom: 16px; }

.split-layout { display: flex; gap: 16px; }
.table-list-panel {
  width: 360px; flex-shrink: 0;
  background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px;
}
.table-list-panel h3 { margin: 0 0 12px; font-size: 14px; }
.table-items { max-height: 500px; overflow-y: auto; }
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
