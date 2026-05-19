<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Table2, Key } from 'lucide-vue-next'
import { getSnapshotDetail, type TableMetaItem, type ColumnMetaItem } from '../../../api/admin/metadata'

const route = useRoute()
const loading = ref(false)
const tables = ref<TableMetaItem[]>([])
const columns = ref<ColumnMetaItem[]>([])
const selectedTable = ref<string>('')

const snapshotId = computed(() => Number(route.query.snapshotId) || 0)

const filteredColumns = computed(() =>
  columns.value.filter(c => c.tableName === selectedTable.value)
)

async function fetchDetail() {
  if (!snapshotId.value) return
  loading.value = true
  try {
    const res = await getSnapshotDetail(snapshotId.value)
    tables.value = res.data?.tables ?? []
    columns.value = res.data?.columns ?? []
    if (tables.value.length > 0) {
      selectedTable.value = tables.value[0].tableName
    }
  } finally {
    loading.value = false
  }
}

onMounted(fetchDetail)
</script>

<template>
  <main class="table-explorer-page post-login-page">
    <header class="page-header">
      <div>
        <p>元数据管理</p>
        <h1>表浏览器</h1>
        <span class="header-subtitle">查看快照中的表和字段详情</span>
      </div>
    </header>

    <div class="explorer-layout" v-loading="loading">
      <aside class="table-list">
        <div class="table-item" v-for="t in tables" :key="t.id"
             :class="{ active: t.tableName === selectedTable }"
             @click="selectedTable = t.tableName">
          <Table2 :size="14" />
          <span class="table-name">{{ t.tableName }}</span>
          <span class="row-count" v-if="t.rowCountEstimate">~{{ t.rowCountEstimate }}</span>
        </div>
        <el-empty v-if="!tables.length" description="暂无表数据" :image-size="60" />
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
.table-explorer-page { padding: 24px; }
.page-header { margin-bottom: 20px; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }

.explorer-layout { display: flex; gap: 16px; border: 1px solid var(--do-line); border-radius: 8px; background: var(--do-surface); min-height: 500px; }
.table-list { width: 240px; border-right: 1px solid var(--do-line); padding: 12px; overflow-y: auto; max-height: 600px; }
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
