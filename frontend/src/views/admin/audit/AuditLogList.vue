<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, RefreshCw } from 'lucide-vue-next'
import {
  getAuditLogDetail,
  getAuditStats,
  listAuditLogs,
  type AuditLogVO,
  type AuditStatsVO,
} from '../../../api/admin/audit'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { positiveId } from '../../../utils/lineageContext'
import { getHttpErrorMessage } from '../../../utils/httpError'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
const route = useRoute(),
  router = useRouter(),
  iam = useIamS1Store(),
  auth = useAuthStore()
const canViewAudit = computed(
  () =>
    iam.loaded &&
    !iam.errorMessage &&
    (iam.systemAdmin || iam.datasourcesWithFunction('audit:view').length > 0),
)
const sourceAllowed = (id?: number) =>
  id === undefined ? canViewAudit.value : canViewAudit.value && iam.canOnDatasource('audit:view', id)
const datasources = computed(() =>
  iam.datasourceCapabilities
    .filter((x) => iam.systemAdmin || x.functionCodes.includes('audit:view'))
    .map((x) => ({ id: x.datasourceId, name: x.datasourceName || `数据源 #${x.datasourceId}` })),
)
const query = reactive({
  datasourceId: undefined as number | undefined,
  isSuccess: undefined as boolean | undefined,
  isSlow: undefined as boolean | undefined,
  keyword: '',
  pageNo: 1,
  pageSize: 20,
})
const invalidSource = ref(false),
  logs = ref<AuditLogVO[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref(''),
  stats = ref<AuditStatsVO | null>(null),
  statsLoading = ref(false),
  statsError = ref('')
const detailVisible = ref(false),
  detailLoading = ref(false),
  detailError = ref(''),
  detail = ref<AuditLogVO | null>(null),
  detailTarget = ref<{ id: number; source: number } | null>(null)
const sqlText = ref<string | null>(null),
  sqlLoading = ref(false),
  sqlError = ref('')
const scope = computed(() => JSON.stringify([auth.token, iam.loaded, iam.snapshot, iam.errorMessage]))
const boundaryError = computed(() =>
  invalidSource.value
    ? '链接中的数据源 ID 无效，未替换为其他范围。'
    : !sourceAllowed(query.datasourceId)
      ? '没有所选范围的 audit:view 能力，或能力摘要未就绪。'
      : '',
)
let listTicket = 0,
  statsTicket = 0,
  detailTicket = 0,
  sqlTicket = 0,
  alive = true
function stateLabel(value: unknown) {
  return value === true ? '成功' : value === false ? '失败' : '未记录'
}
function readRoute() {
  const raw = route.query.datasourceId
  invalidSource.value = raw !== undefined && !positiveId(raw)
  return {
    datasourceId: positiveId(raw),
    isSuccess:
      route.query.isSuccess === 'true' ? true : route.query.isSuccess === 'false' ? false : undefined,
    isSlow: route.query.isSlow === 'true' ? true : route.query.isSlow === 'false' ? false : undefined,
    keyword: typeof route.query.keyword === 'string' ? route.query.keyword : '',
    pageNo: positiveId(route.query.page) || 1,
    pageSize: 20,
  }
}
function closeDetail() {
  detailTicket++
  sqlTicket++
  detailVisible.value = false
  detail.value = null
  detailTarget.value = null
  sqlText.value = null
  detailLoading.value = false
  sqlLoading.value = false
  detailError.value = ''
  sqlError.value = ''
}
function clear() {
  listTicket++
  statsTicket++
  logs.value = []
  total.value = 0
  stats.value = null
  loading.value = false
  statsLoading.value = false
  error.value = ''
  statsError.value = ''
  closeDetail()
}
function persistQuery() {
  const next = { ...route.query }
  const values = {
    datasourceId: query.datasourceId ? String(query.datasourceId) : undefined,
    isSuccess: typeof query.isSuccess === 'boolean' ? String(query.isSuccess) : undefined,
    isSlow: typeof query.isSlow === 'boolean' ? String(query.isSlow) : undefined,
    keyword: query.keyword.trim() || undefined,
    page: query.pageNo > 1 ? String(query.pageNo) : undefined,
  }
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined) delete next[key]
    else next[key] = value
  }
  void router.replace({ query: next })
}
async function fetchLogs() {
  const ticket = ++listTicket,
    key = scope.value,
    params = { ...query, keyword: query.keyword.trim() || undefined }
  closeDetail()
  logs.value = []
  total.value = 0
  error.value = ''
  if (boundaryError.value) {
    loading.value = false
    return
  }
  loading.value = true
  const current = () => alive && ticket === listTicket && key === scope.value && !boundaryError.value
  try {
    const r = await listAuditLogs(params)
    if (current()) {
      logs.value = r.data?.records || []
      total.value = r.data?.total || 0
    }
  } catch (cause) {
    if (current()) error.value = getHttpErrorMessage(cause, '查询审计列表读取失败')
  } finally {
    if (current()) loading.value = false
  }
}
async function fetchStats() {
  const ticket = ++statsTicket,
    key = scope.value,
    source = query.datasourceId
  stats.value = null
  statsError.value = ''
  if (boundaryError.value) {
    statsLoading.value = false
    return
  }
  statsLoading.value = true
  const current = () =>
    alive &&
    ticket === statsTicket &&
    key === scope.value &&
    source === query.datasourceId &&
    !boundaryError.value
  try {
    const r = await getAuditStats({ datasourceId: source, days: 30 })
    if (current()) stats.value = r.data
  } catch (cause) {
    if (current()) statsError.value = getHttpErrorMessage(cause, '近30天审计统计读取失败')
  } finally {
    if (current()) statsLoading.value = false
  }
}
function handleSearch() {
  if (invalidSource.value) return
  query.pageNo = 1
  persistQuery()
  void fetchLogs()
}
function handleDatasourceChange() {
  invalidSource.value = false
  query.pageNo = 1
  persistQuery()
  void fetchLogs()
  void fetchStats()
}
function handlePageChange(page: number) {
  query.pageNo = page
  persistQuery()
  void fetchLogs()
}
async function openDetail(row: { id: number; datasourceId: number }) {
  if (!sourceAllowed(row.datasourceId)) return
  const target = { id: row.id, source: row.datasourceId },
    ticket = ++detailTicket,
    key = scope.value
  sqlTicket++
  detailTarget.value = target
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  sqlText.value = null
  sqlError.value = ''
  sqlLoading.value = false
  const current = () =>
    alive &&
    ticket === detailTicket &&
    key === scope.value &&
    detailVisible.value &&
    detailTarget.value?.id === target.id &&
    sourceAllowed(target.source)
  try {
    const r = await getAuditLogDetail(target.id)
    if (!current()) return
    if (r.data.id !== target.id || r.data.datasourceId !== target.source)
      throw new Error('审计详情与选中记录或数据源不一致')
    detail.value = { ...r.data, sqlText: undefined }
  } catch (cause) {
    if (current()) detailError.value = getHttpErrorMessage(cause, '审计详情读取失败')
  } finally {
    if (current()) detailLoading.value = false
  }
}
function retryDetail() {
  if (detailTarget.value)
    void openDetail({ id: detailTarget.value.id, datasourceId: detailTarget.value.source })
}
async function readAuditSql() {
  const target = detailTarget.value
  if (!target || !detailVisible.value || sqlLoading.value || !sourceAllowed(target.source)) return
  const ticket = ++sqlTicket,
    key = scope.value
  sqlText.value = null
  sqlError.value = ''
  sqlLoading.value = true
  const current = () =>
    alive &&
    ticket === sqlTicket &&
    key === scope.value &&
    detailVisible.value &&
    detailTarget.value?.id === target.id &&
    sourceAllowed(target.source)
  try {
    const r = await getAuditLogDetail(target.id)
    if (!current()) return
    if (r.data.id !== target.id || r.data.datasourceId !== target.source)
      throw new Error('审计SQL与选中记录不一致')
    sqlText.value = r.data.sqlText || ''
  } catch (cause) {
    if (current()) sqlError.value = getHttpErrorMessage(cause, '当前审计SQL读取失败')
  } finally {
    if (current()) sqlLoading.value = false
  }
}
watch(
  () => route.query,
  () => {
    const next = readRoute()
    if (JSON.stringify(next) !== JSON.stringify(query)) {
      Object.assign(query, next)
      void fetchLogs()
      void fetchStats()
    }
  },
  { deep: true },
)
watch(
  scope,
  () => {
    clear()
    void fetchLogs()
    void fetchStats()
  },
  { flush: 'sync' },
)
watch(detailVisible, (value) => {
  if (!value) closeDetail()
})
Object.assign(query, readRoute())
void fetchLogs()
void fetchStats()
onBeforeUnmount(() => {
  alive = false
  clear()
})
</script>
<template>
  <section class="audit-log">
    <p class="workspace-description">
      围绕已落库的审计记录核对问题、使用对象与耗时。执行成功仍需核对业务口径；查询审计与追溯投影可能晚于结果完成。
    </p>
    <p v-if="boundaryError" class="boundary-note" role="status">{{ boundaryError }}</p>
    <template v-else>
      <div class="stats-heading">
        <span>
          近 30 天 · {{ query.datasourceId ? `数据源 #${query.datasourceId}` : '当前有权数据源合集' }}
        </span>
        <el-button text :icon="RefreshCw" :loading="statsLoading" @click="fetchStats">更新统计</el-button>
      </div>
      <ErrorState v-if="statsError" :message="statsError" @retry="fetchStats" />
      <LoadingState v-else-if="statsLoading" variant="skeleton" :rows="1" />
      <div v-else-if="stats" class="stats-row">
        <div class="stat-card">
          <span>{{ stats.totalQueries }}</span>
          <small>审计记录数</small>
        </div>
        <div class="stat-card">
          <span>{{ stats.successRate == null ? '—' : stats.successRate.toFixed(1) + '%' }}</span>
          <small>记录成功率</small>
        </div>
        <div class="stat-card">
          <span>
            {{ stats.avgExecutionTimeMs == null ? '—' : stats.avgExecutionTimeMs.toFixed(0) + ' ms' }}
          </span>
          <small>平均任务耗时</small>
        </div>
        <div class="stat-card">
          <span>{{ stats.slowQueryCount }}</span>
          <small>已标记慢查询</small>
        </div>
      </div>
      <p class="metric-note">
        统计仅按数据源与近 30 天计算，不随下方关键词、状态或慢查询筛选变化；不代表全部问数任务或业务正确率。
      </p>
    </template>
    <form class="toolbar" @submit.prevent="handleSearch">
      <label>
        审计数据源
        <el-select
          v-model="query.datasourceId"
          placeholder="当前有权源合集"
          clearable
          filterable
          @change="handleDatasourceChange"
        >
          <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
      </label>
      <label class="keyword-filter">
        问题关键词
        <el-input v-model="query.keyword" placeholder="仅搜索查询问题" clearable />
      </label>
      <label>
        记录状态
        <el-select v-model="query.isSuccess" placeholder="全部状态" clearable @change="handleSearch">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
      </label>
      <label>
        慢查询标记
        <el-select v-model="query.isSlow" placeholder="全部" clearable @change="handleSearch">
          <el-option label="是" :value="true" />
          <el-option label="否" :value="false" />
        </el-select>
      </label>
      <el-button native-type="submit" type="primary" :icon="Search">筛选记录</el-button>
    </form>
    <section v-if="!boundaryError" class="content-panel">
      <div class="panel-caption">
        <strong>审计记录</strong>
        <span>{{ loading ? '读取中…' : error ? '读取失败' : `共 ${total} 条` }}</span>
      </div>
      <ErrorState v-if="error" :message="error" @retry="fetchLogs" />
      <LoadingState v-else-if="loading" variant="skeleton" :rows="5" />
      <EmptyState v-else-if="!logs.length" message="当前筛选没有已落库审计记录；这不能证明没有发生查询。" />
      <el-table v-else :data="logs" row-key="id">
        <el-table-column prop="id" label="审计 ID" width="90" />
        <el-table-column prop="question" label="问题" min-width="240" show-overflow-tooltip />
        <el-table-column label="数据源 / 用户" min-width="180">
          <template #default="{ row }">
            <div>{{ row.datasourceName || `数据源 #${row.datasourceId}` }}</div>
            <small>{{ row.username || `用户 #${row.userId}` }}</small>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isSuccess === true ? 'success' : row.isSuccess === false ? 'danger' : 'info'">
              {{ stateLabel(row.isSuccess) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务耗时" width="120">
          <template #default="{ row }">
            {{ row.executionTimeMs ?? '—' }} ms
            <small v-if="row.isSlow">· 慢</small>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="记录时间" min-width="180" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="!loading && !error && total > query.pageSize"
        class="pager"
        layout="total,prev,pager,next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNo"
        @current-change="handlePageChange"
      />
    </section>
    <el-drawer v-model="detailVisible" title="查询审计记录" size="min(600px, 100vw)">
      <LoadingState v-if="detailLoading" variant="skeleton" :rows="7" />
      <ErrorState v-else-if="detailError" :message="detailError" @retry="retryDetail" />
      <div v-else-if="detail" class="audit-detail">
        <h2>{{ detail.question }}</h2>
        <p class="metric-note">
          审计 #{{ detail.id }} · 内部数字任务 #{{ detail.queryTaskId }}。该 ID 不是公开问数
          taskId，不用于拼接会话或 SQL 链接。
        </p>
        <dl>
          <div>
            <dt>数据源</dt>
            <dd>{{ detail.datasourceName || `#${detail.datasourceId}` }}</dd>
          </div>
          <div>
            <dt>用户</dt>
            <dd>{{ detail.username || `#${detail.userId}` }}</dd>
          </div>
          <div>
            <dt>记录状态</dt>
            <dd>{{ stateLabel(detail.isSuccess) }}</dd>
          </div>
          <div>
            <dt>任务耗时</dt>
            <dd>{{ detail.executionTimeMs ?? '—' }} ms</dd>
          </div>
          <div>
            <dt>返回行数</dt>
            <dd>{{ detail.rowCount ?? '—' }}</dd>
          </div>
          <div>
            <dt>慢标记</dt>
            <dd>{{ detail.isSlow === true ? '是' : detail.isSlow === false ? '否' : '未记录' }}</dd>
          </div>
          <div>
            <dt>使用表</dt>
            <dd>{{ detail.usedTables || '未提供' }}</dd>
          </div>
          <div>
            <dt>使用字段</dt>
            <dd>{{ detail.usedFields || '未提供' }}</dd>
          </div>
          <div>
            <dt>用户反馈</dt>
            <dd>{{ detail.userFeedback || '未记录' }}</dd>
          </div>
          <div>
            <dt>记录时间</dt>
            <dd>{{ detail.createdAt }}</dd>
          </div>
        </dl>
        <p v-if="detail.errorMessage" class="error-text">{{ detail.errorMessage }}</p>
        <section class="sql-section">
          <h3>审计保存的 SQL</h3>
          <p class="metric-note">
            按需再次读取审计详情，由服务端重验当前 audit:view 及该记录实际源。普通问数的 SQL / CSV /
            历史仍使用各自受保护接口；此处不复用数字任务 ID。
          </p>
          <el-button :loading="sqlLoading" @click="readAuditSql">按当前权限读取 SQL</el-button>
          <ErrorState v-if="sqlError" :message="sqlError" @retry="readAuditSql" />
          <pre v-if="sqlText">{{ sqlText }}</pre>
          <p v-else-if="sqlText !== null" class="metric-note">该记录未保存 SQL。</p>
        </section>
      </div>
    </el-drawer>
  </section>
</template>
<style scoped>
.audit-log {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.workspace-description,
.metric-note {
  font-size: 12px;
  color: var(--do-muted);
  line-height: 1.8;
  margin: 0;
}
.workspace-description {
  font-size: 14px;
  max-width: 900px;
}
.boundary-note {
  padding: 16px;
  border: 1px solid var(--do-line);
  background: var(--do-surface);
  border-radius: 10px;
  font-size: 13px;
}
.stats-heading,
.panel-caption {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}
.stats-heading {
  margin-bottom: -12px;
  color: var(--do-muted);
}
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
}
.stat-card {
  padding: 20px 24px;
  display: grid;
  gap: 8px;
  border-right: 1px solid var(--do-line);
}
.stat-card:last-child {
  border: 0;
}
.stat-card > span {
  font-size: 27px;
  font-weight: 700;
  letter-spacing: -0.04em;
}
.stat-card small {
  font-size: 12px;
  color: var(--do-muted);
}
.toolbar {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: end;
  background: var(--do-surface);
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
}
.toolbar label {
  display: grid;
  gap: 8px;
  font-size: 11px;
  font-weight: 700;
  width: 150px;
}
.toolbar .keyword-filter {
  flex: 1;
  min-width: 180px;
}
.toolbar label:first-child {
  width: 210px;
}
.content-panel {
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
  padding: 18px;
  min-width: 0;
}
.panel-caption {
  padding: 0 0 16px;
}
.panel-caption strong {
  font-size: 14px;
}
.panel-caption span,
small {
  color: var(--do-muted);
}
.pager {
  justify-content: flex-end;
  margin-top: 18px;
}
.audit-detail h2 {
  font-size: 20px;
  line-height: 1.6;
}
.audit-detail dl {
  display: grid;
  gap: 14px;
  margin: 24px 0;
}
.audit-detail dl > div {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 14px;
  font-size: 13px;
}
.audit-detail dt {
  color: var(--do-muted);
}
.audit-detail dd {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
}
.sql-section {
  border-top: 1px solid var(--do-line);
  padding-top: 18px;
  display: grid;
  gap: 12px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--do-bg);
  padding: 14px;
  font-size: 12px;
  line-height: 1.8;
}
.error-text {
  color: var(--do-danger);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 800px) {
  .stats-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .stat-card {
    padding: 16px;
    border-bottom: 1px solid var(--do-line);
  }
  .toolbar label,
  .toolbar label:first-child {
    width: 100%;
  }
  .pager {
    justify-content: flex-start;
    overflow: auto;
  }
}
</style>
