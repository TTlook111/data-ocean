<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw, Eye } from 'lucide-vue-next'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import { listDatasources, type DatasourceItem } from '../../../api/admin/datasource'
import { snapshotStatusLabel, snapshotStatusType } from '../../../utils/enumLabels'

const router = useRouter()
const loading = ref(false)
const snapshots = ref<SnapshotItem[]>([])
const total = ref(0)
const datasources = ref<DatasourceItem[]>([])

const query = reactive({
  datasourceId: undefined as number | undefined,
  page: 1,
  size: 20
})

async function fetchSnapshots() {
  loading.value = true
  try {
    const res = await listSnapshots(query)
    snapshots.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function fetchDatasources() {
  const res = await listDatasources({ page: 1, pageSize: 200 })
  datasources.value = res.data?.records ?? []
}

function viewDetail(id: number) {
  router.push({ name: 'admin-metadata-tables', query: { snapshotId: id } })
}

onMounted(() => {
  fetchSnapshots()
  fetchDatasources()
})
</script>

<template>
  <main class="snapshot-list-page post-login-page">

    <section class="toolbar">
      <el-select v-model="query.datasourceId" placeholder="全部数据源" clearable
                 style="width: 200px" @change="fetchSnapshots">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-button :icon="RefreshCw" @click="fetchSnapshots" />
    </section>

    <section class="table-shell">
      <el-table :data="snapshots" v-loading="loading" stripe>
        <el-table-column prop="snapshotVersion" label="版本" width="80" />
        <el-table-column prop="datasourceName" label="数据源" width="160" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="snapshotStatusType(row.status)" size="small">{{ snapshotStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tableCount" label="表数量" width="90" />
        <el-table-column prop="columnCount" label="字段数" width="90" />
        <el-table-column prop="qualityScore" label="质量分" width="90">
          <template #default="{ row }">
            {{ row.qualityScore ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewDetail(row.id)">
              <Eye :size="16" />
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination class="pager" background layout="total, prev, pager, next"
                   :total="total" :page-size="query.size"
                   v-model:current-page="query.page" @current-change="fetchSnapshots" />
  </main>
</template>

<style scoped>
.snapshot-list-page { display: grid; gap: 16px; }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.table-shell { border: 1px solid var(--do-line); border-radius: 8px; overflow: hidden; background: var(--do-surface); }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
