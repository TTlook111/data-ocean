<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import { listSlowQueries, type AuditLogVO } from '../../../api/admin/audit'

const loading = ref(false)
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const logs = ref<AuditLogVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

async function fetchData() {
  loading.value = true
  try {
    const res = await listSlowQueries({ page: page.value, pageSize: pageSize.value })
    logs.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  withContext(() => { reveal('.content-panel, .stats-row, .toolbar', { y: 14, stagger: 0.06 }) })
  fetchData()
})
</script>

<template>
  <main ref="pageRef" class="slow-query-page post-login-page">

    <section class="content-panel">
      <el-table :data="logs" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="sqlText" label="SQL" min-width="200" show-overflow-tooltip />
        <el-table-column prop="executionTimeMs" label="耗时(ms)" width="110" sortable />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
      <el-empty v-if="!logs.length && !loading" description="暂无慢查询" />
    </section>

    <el-pagination v-if="total > pageSize" class="pager" layout="total, prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="page" @current-change="(p: number) => { page = p; fetchData() }" />
  </main>
</template>

<style scoped>
.slow-query-page { padding: 24px; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
.pager { margin-top: 16px; justify-content: flex-end; }
</style>
