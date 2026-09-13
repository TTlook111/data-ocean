<script setup lang="ts">
/**
 * 性能分析（开发指导 §7.16 的「性能分析」Tab）
 *
 * **能力边界（§7.16、`实施任务清单` §10）**：后端慢查询接口只支持分页，
 * **不支持数据源筛选**，因此本 Tab 不显示数据源上下文，也不声称按数据源过滤。
 * 这一点在页面内显式标注，避免用户以为「没看到筛选器 = 系统漏了」。
 *
 * 另修错误态：原 `fetchData` 只有 `try/finally` 没有 `catch`，
 * 接口失败会静默变成空列表（§11.3、§18）。
 */
import { ref, onMounted } from 'vue'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import { listSlowQueries, type AuditLogVO } from '../../../api/admin/audit'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const loading = ref(false)
const error = ref('')
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const logs = ref<AuditLogVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

async function fetchData() {
  loading.value = true
  error.value = ''
  try {
    const res = await listSlowQueries({ page: page.value, pageSize: pageSize.value })
    logs.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch (cause) {
    logs.value = []
    total.value = 0
    const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
    error.value = message || (cause instanceof Error ? cause.message : '慢查询列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  withContext(() => { reveal('.boundary-note, .content-panel', { y: 14, stagger: 0.06 }) })
  fetchData()
})
</script>

<template>
  <main ref="pageRef" class="slow-query-page">
    <p class="boundary-note">
      当前只支持<strong>全局慢查询分页</strong>。后端慢查询接口没有数据源参数，
      因此这里不提供数据源筛选，也不按数据源过滤；需要按数据源定位问题时请到「查询分析」Tab。
    </p>

    <section class="content-panel">
      <ErrorState v-if="error" :message="error" @retry="fetchData" />
      <LoadingState v-else-if="loading && !logs.length" variant="skeleton" :rows="5" />
      <EmptyState
        v-else-if="!logs.length"
        message="没有慢查询记录。查询耗时超过慢查询阈值时才会进入这里。"
      />
      <el-table v-else :data="logs" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="sqlText" label="SQL" min-width="200" show-overflow-tooltip />
        <el-table-column prop="executionTimeMs" label="耗时(ms)" width="110" sortable />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>
    </section>

    <el-pagination v-if="total > pageSize" class="pager" layout="total, prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="page" @current-change="(p: number) => { page = p; fetchData() }" />
  </main>
</template>

<style scoped>
.slow-query-page { display: grid; gap: 16px; }
.boundary-note {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); padding: 16px; box-shadow: var(--do-shadow); }
.pager { margin-top: 4px; justify-content: flex-end; }
</style>
