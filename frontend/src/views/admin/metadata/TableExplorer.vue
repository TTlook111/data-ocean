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

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const snapshotLoading = ref(false)
const snapshots = ref<SnapshotItem[]>([])
const selectedSnapshotId = ref<number>()
const tables = ref<TableMetaItem[]>([])
const columns = ref<ColumnMetaItem[]>([])
const selectedTable = ref<string>('')

const selectedSnapshot = computed(() => snapshots.value.find((item) => item.id === selectedSnapshotId.value))

const filteredColumns = computed(() =>
  columns.value.filter(c => c.tableName === selectedTable.value)
)

function statusType(status?: string) {
  const map: Record<string, string> = {
    DRAFT: 'info',
    CHECKING: 'warning',
    ISSUE_FOUND: 'danger',
    APPROVED: 'success',
    PUBLISHED: 'success',
    EXPIRED: 'info',
  }
  return map[status || ''] || 'info'
}

function snapshotLabel(snapshot: SnapshotItem) {
  const score = snapshot.qualityScore != null ? ` / ${snapshot.qualityScore}分` : ''
  return `${snapshot.datasourceName} v${snapshot.snapshotVersion}${score}`
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
          <span class="snapshot-option-main">{{ snapshot.datasourceName }} v{{ snapshot.snapshotVersion }}</span>
          <el-tag :type="statusType(snapshot.status)" size="small">{{ snapshot.status }}</el-tag>
        </el-option>
      </el-select>

      <div v-if="selectedSnapshot" class="snapshot-summary">
        <el-tag :type="statusType(selectedSnapshot.status)" size="small">{{ selectedSnapshot.status }}</el-tag>
        <span>{{ selectedSnapshot.tableCount }} 张表</span>
        <span>{{ selectedSnapshot.columnCount }} 个字段</span>
        <span>质量分 {{ selectedSnapshot.qualityScore ?? '-' }}</span>
      </div>
    </section>

    <div class="explorer-layout" v-loading="loading">
      <aside class="table-list">
        <div class="table-item" v-for="t in tables" :key="t.id"
             :class="{ active: t.tableName === selectedTable }"
             @click="selectedTable = t.tableName">
          <Table2 :size="14" />
          <span class="table-name">{{ t.tableName }}</span>
          <span class="row-count" v-if="t.rowCountEstimate">~{{ t.rowCountEstimate }}</span>
        </div>
        <el-empty
          v-if="!tables.length"
          :description="selectedSnapshotId ? '当前快照暂无表数据' : '请选择快照后查看表数据'"
          :image-size="60"
        />
      </aside>

      <section class="column-detail">
        <div class="detail-header" v-if="selectedTable">
          <h3>{{ selectedTable }}</h3>
          <span class="col-count">{{ filteredColumns.length }} 个字段</span>
        </div>
        <el-table :data="filteredColumns" stripe size="small" v-if="filteredColumns.length">
          <el-table-column prop="ordinalPosition" label="#" width="50" />
          <el-table-column prop="columnName" label="字段名" width="180">
            <template #default="{ row }">
              <span style="display: inline-flex; align-items: center; gap: 4px">
                <Key v-if="row.isPrimaryKey" :size="12" style="color: var(--do-accent)" />
                {{ row.columnName }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="dataType" label="类型" width="140" />
          <el-table-column prop="isNullable" label="可空" width="60">
            <template #default="{ row }">{{ row.isNullable ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column prop="columnComment" label="注释" show-overflow-tooltip />
          <el-table-column prop="nullRate" label="空值率" width="80">
            <template #default="{ row }">
              {{ row.nullRate != null ? (row.nullRate * 100).toFixed(1) + '%' : '-' }}
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

.explorer-layout { display: flex; gap: 0; border: 1px solid var(--do-line); border-radius: 8px; background: var(--do-surface); min-height: 600px; }
.table-list { width: 280px; border-right: 1px solid var(--do-line); padding: 12px; overflow-y: auto; max-height: calc(100vh - 220px); }
.table-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.table-item:hover { background: var(--do-primary-soft); }
.table-item.active { background: var(--do-primary-soft); color: var(--do-primary); font-weight: 500; }
.table-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-count { font-size: 11px; color: var(--do-muted); }
.column-detail { flex: 1; padding: 16px; overflow-x: auto; }
.detail-header { display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px; }
.detail-header h3 { margin: 0; font-size: 16px; }
.col-count { font-size: 12px; color: var(--do-muted); }
</style>
