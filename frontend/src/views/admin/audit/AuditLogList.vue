<script setup lang="ts">
/**
 * 查询审计（开发指导 §7.16 的「查询审计」Tab）
 *
 * 三处补齐（对照 §7.16）：
 * 1. **数据源筛选**：`query.datasourceId` 原字段存在且真的参与查询，但**没有任何 UI 能设置它**
 *    ——值只能由全局 store 隐式决定，用户看不到也改不了。现补上可见的筛选控件。
 * 2. **详情**：`getAuditLogDetail` 已封装但全仓零引用；现补详情 Drawer。
 * 3. **提升为模板**：`promoteTemplate` 同样零引用；现补行内操作。
 *
 * 另修错误态：原 `fetchLogs` 只有 `try/finally` 没有 `catch`，`fetchStats` 连 `try` 都没有，
 * 接口失败会静默变成空列表 + 统计卡整块不渲染（§11.3、§18「接口失败不得降级为全零统计或空列表」）。
 *
 * 筛选项与分页写入 URL 查询参数，刷新/分享/前进后退都能恢复（§15、§8.3）。
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Sparkles } from 'lucide-vue-next'
import {
  getAuditLogDetail,
  getAuditStats,
  listAuditLogs,
  promoteTemplate,
  type AuditLogVO,
  type AuditStatsVO,
} from '../../../api/admin/audit'
import { listSimpleDatasources } from '../../../api/admin/datasource'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const adminContext = useAdminContextStore()
// 与同工作区的「性能分析」Tab 保持一致的进场动画
const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const logs = ref<AuditLogVO[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const stats = ref<AuditStatsVO | null>(null)
const statsError = ref('')
const datasources = ref<{ id: number; name: string }[]>([])

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<AuditLogVO | null>(null)

const query = reactive({
  datasourceId: Number(route.query.datasourceId) || undefined,
  isSuccess: route.query.isSuccess === undefined ? undefined : route.query.isSuccess === 'true',
  isSlow: route.query.isSlow === undefined ? undefined : route.query.isSlow === 'true',
  keyword: String(route.query.keyword || ''),
  pageNo: Number(route.query.page) || 1,
  pageSize: 20,
})

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

/** 筛选与分页进 URL，供刷新、分享与前进后退恢复 */
function persistQuery() {
  const next: Record<string, string> = { ...(route.query as Record<string, string>) }
  const values: Record<string, string | undefined> = {
    datasourceId: query.datasourceId ? String(query.datasourceId) : undefined,
    isSuccess: query.isSuccess === undefined ? undefined : String(query.isSuccess),
    isSlow: query.isSlow === undefined ? undefined : String(query.isSlow),
    keyword: query.keyword.trim() || undefined,
    page: query.pageNo > 1 ? String(query.pageNo) : undefined,
  }
  Object.entries(values).forEach(([key, value]) => {
    if (value === undefined) delete next[key]
    else next[key] = value
  })
  router.replace({ query: next })
}

async function fetchLogs() {
  loading.value = true
  error.value = ''
  try {
    const res = await listAuditLogs({
      datasourceId: query.datasourceId,
      isSuccess: query.isSuccess,
      isSlow: query.isSlow,
      keyword: query.keyword || undefined,
      pageNo: query.pageNo,
      pageSize: query.pageSize,
    })
    logs.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch (cause) {
    logs.value = []
    total.value = 0
    error.value = apiError(cause, '查询审计列表加载失败')
  } finally {
    loading.value = false
  }
}

async function fetchStats() {
  statsError.value = ''
  try {
    const res = await getAuditStats({ datasourceId: query.datasourceId, days: 30 })
    stats.value = res.data ?? null
  } catch (cause) {
    // 统计失败必须说明不可用：静默会让用户把「算不出来」读成「全都是 0」（§11.3）
    stats.value = null
    statsError.value = apiError(cause, '查询统计加载失败')
  }
}

function handlePageChange(p: number) {
  query.pageNo = p
  persistQuery()
  fetchLogs()
}

function handleSearch() {
  query.pageNo = 1
  persistQuery()
  fetchLogs()
}

function handleDatasourceChange() {
  query.pageNo = 1
  persistQuery()
  Promise.all([fetchLogs(), fetchStats()])
}

async function openDetail(row: AuditLogVO) {
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    detail.value = (await getAuditLogDetail(row.id)).data
  } catch (cause) {
    detailError.value = apiError(cause, '审计详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function handlePromote(row: AuditLogVO) {
  try {
    await ElMessageBox.confirm(
      `把审计记录 #${row.id} 提升为 Prompt 模板？提升后可在「语义中心 / Prompt 策略」中继续编辑并走审核流程。`,
      '提升为模板',
      { type: 'info', confirmButtonText: '确认提升', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await promoteTemplate(row.id)
    ElMessage.success('已提升为模板，请到 Prompt 策略中编辑并提交审核')
  } catch (cause) {
    ElMessage.error(apiError(cause, '提升为模板失败'))
  }
}

async function loadDatasources() {
  try {
    datasources.value = (await listSimpleDatasources()).data || []
  } catch {
    datasources.value = []
  }
}

onMounted(async () => {
  withContext(() => { reveal('.stats-row, .toolbar, .content-panel', { y: 14, stagger: 0.06 }) })
  await adminContext.initialize()
  // 未在 URL 指定数据源时，沿用全局数据源范围作为初始筛选
  if (!query.datasourceId) query.datasourceId = adminContext.datasourceId
  await loadDatasources()
  await Promise.all([fetchLogs(), fetchStats()])
})

watch(() => adminContext.datasourceId, (datasourceId) => {
  if (query.datasourceId === datasourceId) return
  query.datasourceId = datasourceId
  query.pageNo = 1
  persistQuery()
  Promise.all([fetchLogs(), fetchStats()])
})
</script>

<template>
  <section ref="pageRef" class="audit-log">
    <ErrorState v-if="statsError" :message="statsError" @retry="fetchStats" />
    <section v-else-if="stats" class="stats-row">
      <div class="stat-card"><span class="stat-value">{{ stats.totalQueries }}</span><span class="stat-label">总查询数</span></div>
      <div class="stat-card"><span class="stat-value">{{ stats.successRate?.toFixed(1) }}%</span><span class="stat-label">成功率</span></div>
      <div class="stat-card"><span class="stat-value">{{ stats.avgExecutionTimeMs?.toFixed(0) }}ms</span><span class="stat-label">平均耗时</span></div>
      <div class="stat-card"><span class="stat-value">{{ stats.slowQueryCount }}</span><span class="stat-label">慢查询数</span></div>
    </section>

    <section class="toolbar">
      <el-select
        v-model="query.datasourceId"
        placeholder="全部数据源"
        clearable
        filterable
        style="width: 200px"
        @change="handleDatasourceChange"
      >
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="搜索问题关键词" style="width: 220px" clearable @keyup.enter="handleSearch" />
      <el-select v-model="query.isSuccess" placeholder="状态" style="width: 120px" clearable @change="handleSearch">
        <el-option label="成功" :value="true" />
        <el-option label="失败" :value="false" />
      </el-select>
      <el-select v-model="query.isSlow" placeholder="慢查询" style="width: 120px" clearable @change="handleSearch">
        <el-option label="是" :value="true" />
        <el-option label="否" :value="false" />
      </el-select>
      <el-button type="primary" @click="handleSearch"><Search :size="16" class="btn-icon" />查询</el-button>
    </section>

    <section class="content-panel">
      <ErrorState v-if="error" :message="error" @retry="fetchLogs" />
      <LoadingState v-else-if="loading && !logs.length" variant="skeleton" :rows="5" />
      <EmptyState
        v-else-if="!logs.length"
        message="当前筛选条件下没有查询审计记录。调整数据源或状态筛选后再试。"
      />
      <el-table v-else :data="logs" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column label="用户" width="110">
          <template #default="{ row }">{{ row.username || `用户 #${row.userId}` }}</template>
        </el-table-column>
        <el-table-column label="数据源" width="140">
          <template #default="{ row }">{{ row.datasourceName || `数据源 #${row.datasourceId}` }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isSuccess ? 'success' : 'danger'" size="small">{{ row.isSuccess ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="executionTimeMs" label="耗时(ms)" width="100" />
        <el-table-column label="慢查询" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isSlow" type="warning" size="small">慢</el-tag>
            <span v-else class="muted-text">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" :icon="Sparkles" @click="handlePromote(row)">提升为模板</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination
      v-if="total > query.pageSize"
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.pageSize"
      :current-page="query.pageNo"
      @current-change="handlePageChange"
    />

    <!-- 审计详情 -->
    <el-drawer v-model="detailVisible" title="查询审计详情" size="560px">
      <LoadingState v-if="detailLoading" variant="skeleton" :rows="7" />
      <ErrorState v-else-if="detailError" :message="detailError" @retry="detail ? openDetail(detail) : undefined" />
      <div v-else-if="detail" class="audit-detail">
        <dl>
          <div><dt>记录 ID</dt><dd>{{ detail.id }}</dd></div>
          <div><dt>查询任务</dt><dd>{{ detail.queryTaskId }}</dd></div>
          <div><dt>用户</dt><dd>{{ detail.username || `用户 #${detail.userId}` }}</dd></div>
          <div><dt>数据源</dt><dd>{{ detail.datasourceName || `数据源 #${detail.datasourceId}` }}</dd></div>
          <div><dt>问题</dt><dd>{{ detail.question }}</dd></div>
          <div><dt>结果</dt><dd>{{ detail.isSuccess ? '成功' : '失败' }}</dd></div>
          <div v-if="detail.errorMessage"><dt>失败原因</dt><dd class="is-error">{{ detail.errorMessage }}</dd></div>
          <div><dt>耗时</dt><dd>{{ detail.executionTimeMs ?? '—' }} ms</dd></div>
          <div><dt>返回行数</dt><dd>{{ detail.rowCount ?? '—' }}</dd></div>
          <div><dt>慢查询</dt><dd>{{ detail.isSlow ? '是' : '否' }}</dd></div>
          <div><dt>用户反馈</dt><dd>{{ detail.userFeedback || '未反馈' }}</dd></div>
          <div><dt>使用表</dt><dd class="is-mono">{{ detail.usedTables || '—' }}</dd></div>
          <div><dt>使用字段</dt><dd class="is-mono">{{ detail.usedFields || '—' }}</dd></div>
          <div><dt>时间</dt><dd>{{ detail.createdAt }}</dd></div>
        </dl>
        <section class="audit-detail__section">
          <h3>SQL</h3>
          <pre v-if="detail.sqlText" class="audit-detail__sql">{{ detail.sqlText }}</pre>
          <p v-else class="muted-text">该记录没有保存 SQL（可能未通过生成阶段）。</p>
        </section>
        <div class="audit-detail__actions">
          <el-button :icon="Sparkles" @click="handlePromote(detail)">提升为模板</el-button>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.audit-log { display: grid; gap: 16px; }
.stats-row { display: flex; gap: 16px; flex-wrap: wrap; }
.stat-card { flex: 1; min-width: 140px; padding: 14px; border-radius: var(--do-radius-lg); border: 1px solid var(--do-line); background: var(--do-surface); text-align: center; box-shadow: var(--do-shadow); }
.stat-card .stat-value { display: block; font-size: 24px; font-weight: 600; color: var(--do-primary); }
.stat-card .stat-label { font-size: 12px; color: var(--do-muted); }
.toolbar { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); padding: 16px; box-shadow: var(--do-shadow); }
.pager { margin-top: 4px; justify-content: flex-end; }
.btn-icon { margin-right: 4px; }
.muted-text { color: var(--do-muted); font-size: 12px; }

.audit-detail { display: grid; gap: 16px; }
.audit-detail dl { display: grid; grid-template-columns: 88px 1fr; gap: 10px; margin: 0; }
.audit-detail dt { color: var(--do-muted); font-size: 12px; }
.audit-detail dd { margin: 0; color: var(--do-ink); font-size: 13px; line-height: 1.6; word-break: break-all; }
.audit-detail dd.is-error { color: var(--do-danger); }
.audit-detail dd.is-mono { font-family: var(--do-font-mono, monospace); font-size: 12px; }
.audit-detail__section { display: grid; gap: 6px; }
.audit-detail__section h3 { margin: 0; color: var(--do-ink); font-size: 14px; }
.audit-detail__sql {
  margin: 0;
  padding: 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-ink);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}
.audit-detail__actions { display: flex; gap: 10px; }
</style>
