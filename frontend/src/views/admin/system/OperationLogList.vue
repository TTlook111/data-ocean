<script setup lang="ts">
/**
 * 操作日志（开发指导 §7.19）
 *
 * 独立工作区，与查询审计语义不同（前者记录后台管理动作）。保留既有筛选与详情能力。
 *
 * 三处补齐：
 * 1. **标题区改用 `TaskPageHeader`**：原为自建的 `command-header`，
 *    违反 §10.1「不能每个页面重复实现一套状态卡片和标题区」。
 * 2. **筛选条件与分页进 URL**：原先全部是组件内状态，刷新与前进后退会丢失全部筛选（§15）。
 * 3. **错误态**：原实现把日志清空再弹 toast，页面最终显示「暂无操作日志」——
 *    把失败说成了没有数据（§11.3、§18）。
 *
 * 统计口径已明确标注：总数取自服务端，其余三项基于当前页记录计算，
 * 避免四个并列指标口径不一致而让人误读。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, AlertTriangle, CheckCircle2, ChevronDown, ClipboardList, Clock3, RefreshCw, Search, X } from 'lucide-vue-next'
import { listOperationLogs, type OperationLogItem, type OperationLogQuery } from '../../../api/admin/operation-log'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const logs = ref<OperationLogItem[]>([])
const total = ref(0)
const detailVisible = ref(false)
const selectedLog = ref<OperationLogItem>()

const timeRange = ref<string[] | null>(
  route.query.start && route.query.end
    ? [String(route.query.start), String(route.query.end)]
    : null,
)
const showAdvanced = ref(false)

const query = reactive<OperationLogQuery>({
  page: Number(route.query.page) || 1,
  pageSize: 20,
  operatorName: String(route.query.operatorName || ''),
  operationType: String(route.query.operationType || ''),
  isSuccess: route.query.isSuccess === undefined ? undefined : route.query.isSuccess === 'true',
  ipAddress: String(route.query.ipAddress || ''),
  requestPath: String(route.query.requestPath || ''),
  targetResource: String(route.query.targetResource || ''),
  targetId: String(route.query.targetId || ''),
  keyword: String(route.query.keyword || ''),
})

/** 筛选与分页写入 URL，供刷新、分享与前进后退恢复（§15、§8.3） */
function persistQuery() {
  const [startTime, endTime] = timeRange.value?.length === 2 ? timeRange.value : [undefined, undefined]
  const next: Record<string, string> = {}
  const values: Record<string, string | undefined> = {
    operatorName: query.operatorName?.trim() || undefined,
    operationType: query.operationType || undefined,
    isSuccess: typeof query.isSuccess === 'boolean' ? String(query.isSuccess) : undefined,
    ipAddress: query.ipAddress?.trim() || undefined,
    requestPath: query.requestPath?.trim() || undefined,
    targetResource: query.targetResource?.trim() || undefined,
    targetId: query.targetId?.trim() || undefined,
    keyword: query.keyword?.trim() || undefined,
    start: startTime,
    end: endTime,
    page: (query.page ?? 1) > 1 ? String(query.page) : undefined,
  }
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined) next[key] = value
  })
  router.replace({ query: next })
}

const hasFilter = computed(() => {
  const q = query
  return Boolean(
    q.operatorName?.trim() ||
      q.operationType ||
      typeof q.isSuccess === 'boolean' ||
      q.ipAddress?.trim() ||
      q.requestPath?.trim() ||
      q.targetResource?.trim() ||
      q.targetId?.trim() ||
      q.keyword?.trim() ||
      timeRange.value?.length === 2,
  )
})
const successCount = computed(() => logs.value.filter((item) => item.isSuccess).length)
const failedCount = computed(() => logs.value.filter((item) => item.isSuccess === false).length)
const avgExecutionMs = computed(() => {
  const values = logs.value
    .map((item) => item.executionMs)
    .filter((value): value is number => typeof value === 'number')
  if (!values.length) return 0
  return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length)
})

async function fetchLogs() {
  loading.value = true
  error.value = ''
  try {
    const [startTime, endTime] = timeRange.value?.length === 2 ? timeRange.value : [undefined, undefined]
    const params: OperationLogQuery = {
      page: query.page,
      pageSize: query.pageSize,
      operatorName: query.operatorName?.trim() || undefined,
      operationType: query.operationType || undefined,
      isSuccess: typeof query.isSuccess === 'boolean' ? query.isSuccess : undefined,
      startTime,
      endTime,
      ipAddress: query.ipAddress?.trim() || undefined,
      requestPath: query.requestPath?.trim() || undefined,
      targetResource: query.targetResource?.trim() || undefined,
      targetId: query.targetId?.trim() || undefined,
      keyword: query.keyword?.trim() || undefined,
    }
    const result = await listOperationLogs(params)
    logs.value = result.data?.records ?? []
    total.value = result.data?.total ?? 0
  } catch (cause) {
    logs.value = []
    total.value = 0
    // 失败必须渲染错误态。原实现清空数据再弹一次 toast，页面最终显示
    // 「暂无操作日志」——把失败说成了没有数据（§11.3、§18）。
    const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
    error.value = message || (cause instanceof Error ? cause.message : '操作日志加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  persistQuery()
  fetchLogs()
}

function handleReset() {
  query.operatorName = ''
  query.operationType = ''
  query.isSuccess = undefined
  query.ipAddress = ''
  query.requestPath = ''
  query.targetResource = ''
  query.targetId = ''
  query.keyword = ''
  timeRange.value = null
  query.page = 1
  persistQuery()
  fetchLogs()
}

function handlePageChange(page: number) {
  query.page = page
  persistQuery()
  fetchLogs()
}

function handlePageSizeChange(pageSize: number) {
  query.pageSize = pageSize
  query.page = 1
  fetchLogs()
}

function openDetail(row: OperationLogItem) {
  selectedLog.value = row
  detailVisible.value = true
}

function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date)
}

function methodTagType(method?: string) {
  switch (method) {
    case 'GET': return 'info'
    case 'POST': return 'success'
    case 'PUT':
    case 'PATCH': return 'warning'
    case 'DELETE': return 'danger'
    default: return 'info'
  }
}

function operationLabel(value?: string) {
  if (!value) return '-'
  const labels: Record<string, string> = {
    CREATE: '新增',
    UPDATE: '更新',
    DELETE: '删除',
    QUERY: '查询',
    LOGIN: '登录',
    LOGOUT: '退出',
  }
  return labels[value] || value
}

function formatParams(value?: string) {
  if (!value) return '-'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function executionClass(value?: number) {
  if (typeof value !== 'number') return ''
  if (value >= 1000) return 'is-slow'
  if (value >= 500) return 'is-warn'
  return ''
}

onMounted(fetchLogs)
</script>

<template>
  <main class="operation-log-page post-login-page">
    <TaskPageHeader
      eyebrow="运营与平台"
      title="操作日志"
      description="围绕目标资源追踪后台操作、请求路径、耗时与成功状态，用于审计追溯和异常定位。"
    >
      <template #status>
        <span class="operation-log-page__scope">当前筛选 {{ total }} 条</span>
      </template>
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="fetchLogs">刷新</el-button>
      </template>
    </TaskPageHeader>

    <section class="summary-grid">
      <div class="summary-card">
        <span class="summary-icon"><ClipboardList :size="18" /></span>
        <div>
          <strong>{{ total }}</strong>
          <small>日志总数</small>
        </div>
      </div>
      <div class="summary-card">
        <span class="summary-icon is-success"><CheckCircle2 :size="18" /></span>
        <div>
          <strong>{{ successCount }}</strong>
          <small>当前页成功</small>
        </div>
      </div>
      <div class="summary-card">
        <span class="summary-icon is-danger"><AlertTriangle :size="18" /></span>
        <div>
          <strong>{{ failedCount }}</strong>
          <small>当前页失败</small>
        </div>
      </div>
      <div class="summary-card">
        <span class="summary-icon is-time"><Clock3 :size="18" /></span>
        <div>
          <strong>{{ avgExecutionMs }}ms</strong>
          <small>当前页平均耗时</small>
        </div>
      </div>
    </section>
    <p class="operation-log-page__stats-note">
      统计口径：<strong>日志总数</strong>取自服务端；<strong>成功 / 失败 / 平均耗时</strong>由当前页记录算出。
      后端没有操作日志的聚合统计接口，因此这三项随翻页变化，不代表全局。需要全局数据请导出后统计。
    </p>

    <section class="content-panel">
      <div class="panel-toolbar">
        <div class="panel-title">
          <Activity :size="18" />
          <div>
            <strong>操作日志</strong>
            <span>按目标资源定位后台操作轨迹</span>
          </div>
        </div>
        <div class="filter-area">
          <div class="filter-row">
            <el-input
              v-model="query.keyword"
              class="filter-item filter-item--keyword"
              clearable
              maxlength="100"
              placeholder="关键词（操作人 / 资源 / 路径）"
              @keyup.enter="handleSearch"
            >
              <template #prefix>
                <Search :size="16" />
              </template>
            </el-input>
            <el-input v-model="query.operatorName" class="filter-item" clearable placeholder="操作人" @keyup.enter="handleSearch" />
            <el-select v-model="query.operationType" class="filter-item" clearable placeholder="操作类型" @change="handleSearch">
              <el-option label="新增" value="CREATE" />
              <el-option label="更新" value="UPDATE" />
              <el-option label="删除" value="DELETE" />
              <el-option label="查询" value="QUERY" />
            </el-select>
            <el-select v-model="query.isSuccess" class="filter-item" clearable placeholder="状态" @change="handleSearch">
              <el-option label="成功" :value="true" />
              <el-option label="失败" :value="false" />
            </el-select>
            <el-date-picker
              v-model="timeRange"
              class="filter-item filter-item--time"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="YYYY-MM-DD HH:mm:ss"
              @change="handleSearch"
            />
          </div>
          <div v-show="showAdvanced" class="filter-row filter-row--advanced">
            <el-input v-model="query.ipAddress" class="filter-item" clearable placeholder="IP 地址" @keyup.enter="handleSearch" />
            <el-input v-model="query.requestPath" class="filter-item filter-item--wide" clearable placeholder="请求路径" @keyup.enter="handleSearch" />
            <el-input v-model="query.targetResource" class="filter-item" clearable placeholder="目标资源" @keyup.enter="handleSearch" />
            <el-input v-model="query.targetId" class="filter-item" clearable placeholder="目标 ID" @keyup.enter="handleSearch" />
          </div>
          <div class="filter-actions">
            <button type="button" class="advanced-toggle" @click="showAdvanced = !showAdvanced">
              {{ showAdvanced ? '收起高级筛选' : '高级筛选' }}
              <ChevronDown :size="14" :class="{ 'is-open': showAdvanced }" />
            </button>
            <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
            <el-button v-if="hasFilter" :icon="X" @click="handleReset">重置</el-button>
          </div>
        </div>
      </div>

      <ErrorState v-if="error" :message="error" @retry="fetchLogs" />
      <LoadingState v-else-if="loading && !logs.length" variant="skeleton" :rows="6" />
      <EmptyState
        v-else-if="!logs.length"
        :message="hasFilter ? '当前筛选条件下没有操作日志。尝试放宽筛选或重置条件。' : '还没有操作日志。后台管理动作会在执行后记录在这里。'"
        :action-text="hasFilter ? '重置筛选' : ''"
        @action="handleReset"
      />

      <!-- 宽屏表格与窄屏卡片由 CSS 媒体查询切换，因此共用同一个 v-else 分支 -->
      <template v-else>
      <div class="log-table-wrap">
        <el-table
          v-loading="loading"
          class="log-table"
          :data="logs"
          border
          stripe
          row-key="id"
        >
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.isSuccess ? 'success' : 'danger'" size="small">
                {{ row.isSuccess ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operatorName" label="操作人" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.operatorName || `用户 ${row.operatorId || '-'}` }}
            </template>
          </el-table-column>
          <el-table-column prop="operationType" label="类型" width="110">
            <template #default="{ row }">
              {{ operationLabel(row.operationType) }}
            </template>
          </el-table-column>
          <el-table-column prop="targetResource" label="目标资源" min-width="150" show-overflow-tooltip />
          <el-table-column prop="targetId" label="目标 ID" width="110" show-overflow-tooltip />
          <el-table-column label="请求" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="request-cell">
                <el-tag :type="methodTagType(row.requestMethod)" size="small">{{ row.requestMethod || '-' }}</el-tag>
                <span>{{ row.requestPath || '-' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="executionMs" label="耗时" width="100">
            <template #default="{ row }">
              <span class="execution-value" :class="executionClass(row.executionMs)">
                {{ row.executionMs ?? '-' }}ms
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="ipAddress" label="IP" width="140" show-overflow-tooltip />
          <el-table-column label="时间" width="180">
            <template #default="{ row }">
              {{ formatTime(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      </template>

      <el-pagination
        v-if="!error && total > 0"
        class="pager"
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.page"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="handlePageChange"
        @size-change="handlePageSizeChange"
      />
    </section>

    <el-drawer v-model="detailVisible" title="操作日志详情" size="520px">
      <el-descriptions v-if="selectedLog" :column="1" border>
        <el-descriptions-item label="日志 ID">{{ selectedLog.id }}</el-descriptions-item>
        <el-descriptions-item label="操作人">
          {{ selectedLog.operatorName || `用户 ${selectedLog.operatorId || '-'}` }}
        </el-descriptions-item>
        <el-descriptions-item label="操作类型">{{ operationLabel(selectedLog.operationType) }}</el-descriptions-item>
        <el-descriptions-item label="目标资源">{{ selectedLog.targetResource || '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标 ID">{{ selectedLog.targetId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="请求方法">{{ selectedLog.requestMethod || '-' }}</el-descriptions-item>
        <el-descriptions-item label="请求路径">{{ selectedLog.requestPath || '-' }}</el-descriptions-item>
        <el-descriptions-item label="执行耗时">{{ selectedLog.executionMs ?? '-' }}ms</el-descriptions-item>
        <el-descriptions-item label="IP 地址">{{ selectedLog.ipAddress || '-' }}</el-descriptions-item>
        <el-descriptions-item label="操作时间">{{ formatTime(selectedLog.createdAt) }}</el-descriptions-item>
      </el-descriptions>

      <div v-if="selectedLog" class="detail-block detail-block--code">
        <h3>请求参数</h3>
        <pre>{{ formatParams(selectedLog.requestParams) }}</pre>
      </div>

      <div v-if="selectedLog?.errorMessage" class="detail-block detail-block--code">
        <h3>错误信息</h3>
        <pre class="error-text">{{ selectedLog.errorMessage }}</pre>
      </div>
    </el-drawer>
  </main>
</template>

<style scoped>
.operation-log-page__scope {
  color: var(--do-muted);
  font-size: 13px;
}

.operation-log-page__stats-note {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}

.operation-log-page {
  display: grid;
  gap: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
}

.summary-card strong {
  display: block;
  color: var(--do-ink);
  font-size: 20px;
  line-height: 1.15;
}

.summary-card small {
  display: block;
  margin-top: 3px;
  color: var(--do-muted);
  font-size: 12px;
}

.summary-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 8px;
  color: var(--do-primary);
  background: var(--do-primary-soft);
}

.summary-icon.is-success {
  color: var(--do-success);
  background: var(--do-success-soft);
}

.summary-icon.is-danger {
  color: var(--do-danger);
  background: var(--do-danger-soft);
}

.summary-icon.is-time {
  color: var(--do-warning);
  background: var(--do-warning-soft);
}

.filter-area {
  min-width: 0;
  display: grid;
  gap: 8px;
}

.filter-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-row--advanced {
  padding-top: 4px;
}

.filter-item {
  width: 140px;
}

.filter-item--keyword {
  width: min(220px, 100%);
}

.filter-item--wide {
  width: 200px;
}

.filter-item--time {
  width: min(340px, 100%);
}

.filter-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.advanced-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  border: 0;
  color: var(--do-muted);
  background: transparent;
  font-size: 12px;
  cursor: pointer;
}

.advanced-toggle svg {
  transition: transform 0.2s ease;
}

.advanced-toggle svg.is-open {
  transform: rotate(180deg);
}

.advanced-toggle:hover {
  color: var(--do-primary);
}

.content-panel {
  min-width: 0;
  overflow: hidden;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.panel-title {
  min-width: 180px;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--do-primary);
}

.panel-title strong {
  display: block;
  color: var(--do-ink);
  font-size: 14px;
}

.panel-title span {
  display: block;
  margin-top: 2px;
  color: var(--do-muted);
  font-size: 12px;
}

.log-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.log-table {
  min-width: 1420px;
}

.request-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.request-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.execution-value {
  font-variant-numeric: tabular-nums;
}

.execution-value.is-warn {
  color: var(--do-warning);
  font-weight: 700;
}

.execution-value.is-slow {
  color: var(--do-danger);
  font-weight: 700;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.detail-block {
  margin-top: 18px;
}

.detail-block h3 {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--do-ink);
}

.detail-block--code pre {
  max-height: 260px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-bg);
  color: var(--do-ink);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.error-text {
  color: var(--do-danger);
}

@media (max-width: 1200px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .panel-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
}

</style>
