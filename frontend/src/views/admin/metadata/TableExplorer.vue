<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { RefreshCw, Table2, Key } from 'lucide-vue-next'
import {
  getSnapshotDetail,
  listSnapshots,
  type SnapshotItem,
  type TableMetaItem,
  type ColumnMetaItem,
} from '../../../api/admin/metadata'
import { governanceStatusLabel, snapshotStatusLabel, snapshotStatusType } from '../../../utils/enumLabels'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const snapshotLoading = ref(false)
const snapshots = ref<SnapshotItem[]>([])
const selectedSnapshotId = ref<number>()
const tables = ref<TableMetaItem[]>([])
const columns = ref<ColumnMetaItem[]>([])
const selectedTable = ref<string>('')
const tableKeyword = ref('')

const selectedSnapshot = computed(() => snapshots.value.find((item) => item.id === selectedSnapshotId.value))
const selectedTableMeta = computed(() => tables.value.find((item) => item.tableName === selectedTable.value))
const filteredTables = computed(() => {
  const keyword = tableKeyword.value.trim().toLowerCase()
  if (!keyword) return tables.value
  return tables.value.filter((item) =>
    item.tableName.toLowerCase().includes(keyword) ||
    (item.tableComment || '').toLowerCase().includes(keyword),
  )
})

const filteredColumns = computed(() =>
  columns.value.filter(c => c.tableName === selectedTable.value)
)

function formatNumber(value?: number | null) {
  if (value == null) return '未采集统计'
  return value.toLocaleString('zh-CN')
}

function formatEstimatedRows(value?: number | null) {
  if (value == null) return '未采集统计'
  return `约 ${formatNumber(value)} 行`
}

function formatNullRate(value?: number | null) {
  if (value == null) return '未采集统计'
  return `${(Number(value) * 100).toFixed(1)}%`
}

function nullableLabel(value: number | boolean) {
  return value ? '允许为空' : '不能为空'
}

function tableTypeLabel(type?: string | null) {
  if (!type) return '未采集'
  return type === 'TABLE' ? '数据表' : type === 'VIEW' ? '视图' : type
}

function snapshotLabel(snapshot: SnapshotItem) {
  const score = snapshot.qualityScore != null ? ` / ${snapshot.qualityScore}分` : ''
  return `${snapshot.datasourceName} 版本 ${snapshot.snapshotVersion}${score}`
}

async function fetchSnapshots() {
  snapshotLoading.value = true
  try {
    const res = await listSnapshots({ page: 1, size: 50 })
    snapshots.value = res.data?.records ?? []
    const routeSnapshotId = Number(route.query.snapshotId) || undefined
    if (routeSnapshotId && snapshots.value.some((item) => item.id === routeSnapshotId)) {
      selectedSnapshotId.value = routeSnapshotId
    } else if (!selectedSnapshotId.value && snapshots.value.length) {
      selectedSnapshotId.value = snapshots.value[0].id
    }
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '获取快照列表失败')
  } finally {
    snapshotLoading.value = false
  }
}

async function fetchDetail() {
  if (!selectedSnapshotId.value) {
    tables.value = []
    columns.value = []
    selectedTable.value = ''
    return
  }
  loading.value = true
  try {
    const res = await getSnapshotDetail(selectedSnapshotId.value)
    tables.value = res.data?.tables ?? []
    columns.value = res.data?.columns ?? []
    if (tables.value.length > 0) {
      selectedTable.value = tables.value[0].tableName
    } else {
      selectedTable.value = ''
    }
  } catch (error: any) {
    tables.value = []
    columns.value = []
    selectedTable.value = ''
    ElMessage.error(error?.response?.data?.message || '获取快照详情失败')
  } finally {
    loading.value = false
  }
}

function handleSnapshotChange() {
  if (selectedSnapshotId.value) {
    router.replace({ query: { ...route.query, snapshotId: selectedSnapshotId.value } })
  }
  fetchDetail()
}

onMounted(async () => {
  await fetchSnapshots()
  await fetchDetail()
})

watch(
  () => route.query.snapshotId,
  (value) => {
    const nextId = Number(value) || undefined
    if (nextId && nextId !== selectedSnapshotId.value) {
      selectedSnapshotId.value = nextId
      fetchDetail()
    }
  },
)
</script>

<template>
  <main class="table-explorer-page post-login-page">
    <header class="page-header">
      <div>
        <p>元数据管理</p>
        <h1>表浏览器</h1>
        <span class="header-subtitle">选择一个采集快照，查看其中的表结构、字段、统计信息和治理状态</span>
      </div>
      <el-button :icon="RefreshCw" :loading="loading || snapshotLoading" @click="fetchDetail">刷新</el-button>
    </header>

    <section class="snapshot-toolbar">
      <el-select
        v-model="selectedSnapshotId"
        placeholder="选择快照"
        filterable
        :loading="snapshotLoading"
        style="width: 360px"
        @change="handleSnapshotChange"
      >
        <el-option v-for="snapshot in snapshots" :key="snapshot.id" :label="snapshotLabel(snapshot)" :value="snapshot.id">
          <span class="snapshot-option-main">{{ snapshot.datasourceName }} 版本 {{ snapshot.snapshotVersion }}</span>
          <el-tag :type="snapshotStatusType(snapshot.status)" size="small">{{ snapshotStatusLabel(snapshot.status) }}</el-tag>
        </el-option>
      </el-select>

      <div v-if="selectedSnapshot" class="snapshot-summary">
        <el-tag :type="snapshotStatusType(selectedSnapshot.status)" size="small">
          {{ snapshotStatusLabel(selectedSnapshot.status) }}
        </el-tag>
        <el-tooltip content="当前快照采集到的数据表数量">
          <span>数据表：{{ selectedSnapshot.tableCount }} 张</span>
        </el-tooltip>
        <el-tooltip content="当前快照采集到的字段总数">
          <span>字段：{{ selectedSnapshot.columnCount }} 个</span>
        </el-tooltip>
        <el-tooltip content="质量分由元数据质量规则计算，满分 100 分">
          <span>质量分：{{ selectedSnapshot.qualityScore ?? '未校验' }}</span>
        </el-tooltip>
      </div>
    </section>

    <div class="explorer-layout" v-loading="loading">
      <aside class="table-list">
        <div class="table-list-header">
          <strong>数据表</strong>
          <el-input v-model="tableKeyword" size="small" clearable placeholder="搜索表名或注释" />
        </div>
        <div class="table-item" v-for="t in filteredTables" :key="t.id"
             :class="{ active: t.tableName === selectedTable }"
             @click="selectedTable = t.tableName">
          <Table2 :size="14" />
          <div class="table-main">
            <span class="table-name">{{ t.tableName }}</span>
            <span class="table-comment">{{ t.tableComment || '暂无表说明' }}</span>
          </div>
          <el-tooltip content="估算行数：采集快照时从 MySQL 元数据读取的估算行数，不是精确 COUNT(*)">
            <span class="row-count">{{ formatEstimatedRows(t.rowCountEstimate) }}</span>
          </el-tooltip>
        </div>
        <el-empty
          v-if="!filteredTables.length"
          :description="tables.length ? '没有匹配的数据表' : selectedSnapshotId ? '当前快照暂无表数据' : '请选择快照后查看表数据'"
          :image-size="60"
        />
      </aside>

      <section class="column-detail">
        <div class="detail-header" v-if="selectedTable">
          <div>
            <h3>{{ selectedTable }}</h3>
            <span class="table-description">{{ selectedTableMeta?.tableComment || '暂无表说明' }}</span>
          </div>
          <span class="col-count">{{ filteredColumns.length }} 个字段</span>
        </div>
        <div class="table-facts" v-if="selectedTableMeta">
          <div class="fact-item">
            <span>表类型</span>
            <strong>{{ tableTypeLabel(selectedTableMeta.tableType) }}</strong>
          </div>
          <el-tooltip content="估算行数：采集快照时从 MySQL 元数据读取的估算行数，不是精确 COUNT(*)">
            <div class="fact-item">
              <span>估算行数</span>
              <strong>{{ formatEstimatedRows(selectedTableMeta.rowCountEstimate) }}</strong>
            </div>
          </el-tooltip>
          <div class="fact-item">
            <span>治理状态</span>
            <strong>{{ governanceStatusLabel(selectedTableMeta.governanceStatus) }}</strong>
          </div>
          <div class="fact-item">
            <span>存储引擎</span>
            <strong>{{ selectedTableMeta.engine || '未采集' }}</strong>
          </div>
        </div>
        <el-table :data="filteredColumns" stripe size="small" v-if="filteredColumns.length">
          <el-table-column prop="ordinalPosition" label="#" width="50" />
          <el-table-column prop="columnName" label="字段名" width="180">
            <template #default="{ row }">
              <span class="column-name-cell">
                <Key v-if="row.isPrimaryKey" :size="12" style="color: var(--do-accent)" />
                <span class="column-code">{{ row.columnName }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="dataType" label="类型" width="140">
            <template #default="{ row }"><span class="column-code">{{ row.dataType }}</span></template>
          </el-table-column>
          <el-table-column prop="isNullable" label="是否允许为空" width="120">
            <template #default="{ row }">{{ nullableLabel(row.isNullable) }}</template>
          </el-table-column>
          <el-table-column prop="columnComment" label="字段说明" show-overflow-tooltip>
            <template #default="{ row }">{{ row.columnComment || '暂无字段说明' }}</template>
          </el-table-column>
          <el-table-column prop="governanceStatus" label="治理状态" width="120">
            <template #default="{ row }">{{ governanceStatusLabel(row.governanceStatus) }}</template>
          </el-table-column>
          <el-table-column prop="nullRate" width="120">
            <template #header>
              <el-tooltip content="空值率：采集统计时基于样本数据计算，未采集时显示“未采集统计”">
                <span>空值率</span>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              {{ formatNullRate(row.nullRate) }}
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="选择左侧表查看字段" :image-size="60" />
      </section>
    </div>
  </main>
</template>

<style scoped>
.table-explorer-page { display: grid; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.snapshot-toolbar {
  min-height: 48px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
}
.snapshot-option-main {
  display: inline-block;
  min-width: 190px;
}
.snapshot-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--do-muted);
  font-size: 13px;
}

.explorer-layout {
  display: flex;
  gap: 0;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  min-height: 600px;
  box-shadow: var(--do-shadow);
  overflow: hidden;
}
.table-list { width: 304px; border-right: 1px solid var(--do-line); padding: 12px; overflow-y: auto; max-height: calc(100vh - 220px); }
.table-list-header {
  position: sticky;
  top: 0;
  z-index: 1;
  display: grid;
  gap: 8px;
  margin: -12px -12px 10px;
  padding: 12px;
  border-bottom: 1px solid var(--do-line);
  background: var(--do-surface);
}
.table-list-header strong {
  color: var(--do-ink);
  font-size: 13px;
}
.table-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.table-item:hover { background: var(--do-primary-soft); }
.table-item.active { background: var(--do-primary-soft); color: var(--do-primary); font-weight: 500; }
.table-main {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 2px;
}
.table-name {
  overflow: hidden;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-comment {
  overflow: hidden;
  color: var(--do-muted);
  font-size: 11px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-count {
  flex: 0 0 auto;
  color: var(--do-muted);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.column-detail { flex: 1; padding: 16px; overflow-x: auto; }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.detail-header h3 {
  margin: 0;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 16px;
}
.table-description {
  display: block;
  margin-top: 4px;
  color: var(--do-muted);
  font-size: 12px;
}
.col-count { font-size: 12px; color: var(--do-muted); }
.table-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}
.fact-item {
  display: grid;
  gap: 4px;
  min-height: 56px;
  padding: 9px 10px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  background: var(--do-bg);
}
.fact-item span {
  color: var(--do-muted);
  font-size: 11px;
}
.fact-item strong {
  overflow: hidden;
  color: var(--do-ink);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.column-name-cell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
}
@media (max-width: 1100px) {
  .explorer-layout { flex-direction: column; }
  .table-list { width: auto; max-height: 360px; border-right: 0; border-bottom: 1px solid var(--do-line); }
  .table-facts { grid-template-columns: repeat(2, minmax(120px, 1fr)); }
}
</style>
