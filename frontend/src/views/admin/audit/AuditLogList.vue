<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { Search } from 'lucide-vue-next'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import { useAdminContextStore } from '../../../stores/adminContext'
import { listAuditLogs, getAuditStats, type AuditLogVO, type AuditStatsVO } from '../../../api/admin/audit'

const loading = ref(false)
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)
const adminContext = useAdminContextStore()

const logs = ref<AuditLogVO[]>([])
const total = ref(0)
const stats = ref<AuditStatsVO | null>(null)

const query = reactive({
  userId: undefined as number | undefined,
  datasourceId: undefined as number | undefined,
  isSuccess: undefined as boolean | undefined,
  isSlow: undefined as boolean | undefined,
  keyword: '',
  startTime: '',
  endTime: '',
  pageNo: 1,
  pageSize: 20
})

async function fetchLogs() {
  loading.value = true
  try {
    const res = await listAuditLogs(query)
    logs.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function fetchStats() {
  const res = await getAuditStats({ datasourceId: adminContext.datasourceId, days: 30 })
  stats.value = res.data ?? null
}

function handlePageChange(p: number) {
  query.pageNo = p
  fetchLogs()
}

function handleSearch() {
  query.pageNo = 1
  fetchLogs()
}

onMounted(async () => {
  withContext(() => { reveal('.content-panel, .stats-row, .toolbar', { y: 14, stagger: 0.06 }) })
  await adminContext.initialize()
  query.datasourceId = adminContext.datasourceId
  await Promise.all([fetchLogs(), fetchStats()])
})

watch(
  () => adminContext.datasourceId,
  (datasourceId) => {
    query.datasourceId = datasourceId
    query.pageNo = 1
    Promise.all([fetchLogs(), fetchStats()])
  },
)
</script>

<template>
  <main ref="pageRef" class="audit-page post-login-page">

    <section v-if="stats" class="stats-row">
      <div class="stat-card"><span class="stat-value">{{ stats.totalQueries }}</span><span class="stat-label">总查询数</span></div>
      <div class="stat-card"><span class="stat-value">{{ stats.successRate?.toFixed(1) }}%</span><span class="stat-label">成功率</span></div>
      <div class="stat-card"><span class="stat-value">{{ stats.avgExecutionTimeMs?.toFixed(0) }}ms</span><span class="stat-label">平均耗时</span></div>
      <div class="stat-card"><span class="stat-value">{{ stats.slowQueryCount }}</span><span class="stat-label">慢查询数</span></div>
    </section>

    <section class="toolbar">
      <el-input v-model="query.keyword" placeholder="搜索问题关键词" style="width: 220px" clearable />
      <el-select v-model="query.isSuccess" placeholder="状态" style="width: 120px" clearable>
        <el-option label="成功" :value="true" />
        <el-option label="失败" :value="false" />
      </el-select>
      <el-select v-model="query.isSlow" placeholder="慢查询" style="width: 120px" clearable>
        <el-option label="是" :value="true" />
        <el-option label="否" :value="false" />
      </el-select>
      <el-button type="primary" @click="handleSearch"><Search :size="16" style="margin-right:4px" />查询</el-button>
    </section>

    <section class="content-panel">
      <el-table :data="logs" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="isSuccess" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isSuccess ? 'success' : 'danger'" size="small">{{ row.isSuccess ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="executionTimeMs" label="耗时(ms)" width="100" />
        <el-table-column prop="isSlow" label="慢查询" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isSlow" type="warning" size="small">慢</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="userFeedback" label="反馈" width="80" />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
    </section>

    <el-pagination v-if="total > query.pageSize" class="pager" layout="total, prev, pager, next"
      :total="total" :page-size="query.pageSize" :current-page="query.pageNo" @current-change="handlePageChange" />
  </main>
</template>

<style scoped>
.audit-page { padding: 24px; }
.stats-row { display: flex; gap: 16px; margin-bottom: 20px; }
.stat-card { flex: 1; padding: 14px; border-radius: 8px; border: 1px solid var(--do-line); background: var(--do-surface); text-align: center; }
.stat-card .stat-value { display: block; font-size: 24px; font-weight: 600; color: var(--do-primary); }
.stat-card .stat-label { font-size: 12px; color: var(--do-muted); }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
