<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Activity, AlertTriangle, CheckCircle2, ChevronDown, ClipboardList, Clock3, RefreshCw, Search, X } from 'lucide-vue-next'
import { listOperationLogs, type OperationLogItem, type OperationLogQuery } from '../../../api/admin/operation-log'

const loading = ref(false)
const logs = ref<OperationLogItem[]>([])
const total = ref(0)
const detailVisible = ref(false)
const selectedLog = ref<OperationLogItem>()

const timeRange = ref<string[] | null>(null)
const showAdvanced = ref(false)

const query = reactive<OperationLogQuery>({
  page: 1,
  pageSize: 20,
  operatorName: '',
  operationType: '',
  isSuccess: undefined,
  ipAddress: '',
  requestPath: '',
  targetResource: '',
  targetId: '',
  keyword: '',
})

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
  } catch {
    logs.value = []
    total.value = 0
    ElMessage.error('操作日志加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
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
  fetchLogs()
}

function handlePageChange(page: number) {
  query.page = page
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
    <section class="command-header">
      <div class="command-title">
        <span>运营与安全 / 操作轨迹</span>
        <h2>操作日志</h2>
        <p>围绕目标资源追踪后台操作、请求路径、耗时与成功状态，用于审计追溯和异常定位。</p>
      </div>
      <div class="command-actions">
        <span class="trust-badge">审计留痕</span>
        <span class="trust-badge">按资源定位</span>
        <el-button :icon="RefreshCw" :loading="loading" @click="fetchLogs">刷新</el-button>
      </div>
    </section>

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

      <div class="log-table-wrap">
        <el-table
          v-loading="loading"
          class="log-table"
          :data="logs"
          border
          stripe
          row-key="id"
          empty-text="暂无操作日志"
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

      <div class="log-card-list" v-loading="loading">
        <article v-for="row in logs" :key="row.id" class="log-card" :class="{ 'is-failed': row.isSuccess === false }">
          <div class="log-card__head">
            <strong>#{{ row.id }} · {{ operationLabel(row.operationType) }}</strong>
            <el-tag :type="row.isSuccess ? 'success' : 'danger'" size="small">
              {{ row.isSuccess ? '成功' : '失败' }}
            </el-tag>
          </div>
          <div class="log-card__meta">
            <span><b>操作人</b>{{ row.operatorName || `用户 ${row.operatorId || '-'}` }}</span>
            <span><b>目标资源</b>{{ row.targetResource || '-' }}</span>
            <span><b>请求</b>{{ row.requestMethod || '-' }} {{ row.requestPath || '-' }}</span>
            <span><b>耗时</b><em :class="executionClass(row.executionMs)">{{ row.executionMs ?? '-' }}ms</em></span>
            <span><b>IP</b>{{ row.ipAddress || '-' }}</span>
            <span><b>时间</b>{{ formatTime(row.createdAt) }}</span>
          </div>
          <button type="button" class="log-card__action" @click="openDetail(row)">查看详情</button>
        </article>
        <el-empty v-if="!loading && logs.length === 0" description="暂无操作日志" :image-size="72" />
      </div>

      <el-pagination
        v-if="total > 0"
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
  background: rgba(77, 143, 220, 0.1);
}

.summary-icon.is-success {
  color: #3f9f72;
  background: rgba(63, 159, 114, 0.12);
}

.summary-icon.is-danger {
  color: #d95f5f;
  background: rgba(217, 95, 95, 0.12);
}

.summary-icon.is-time {
  color: #b88732;
  background: rgba(184, 135, 50, 0.14);
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

.log-card-list {
  display: none;
}

.log-card {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: #fff;
}

.log-card.is-failed {
  border-color: rgba(217, 95, 95, 0.28);
  background: #fffafa;
}

.log-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.log-card__head strong {
  min-width: 0;
  overflow: hidden;
  color: var(--do-ink);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-card__meta {
  display: grid;
  gap: 8px;
}

.log-card__meta span {
  min-width: 0;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 8px;
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.45;
}

.log-card__meta b {
  color: var(--do-muted);
  font-size: 12px;
}

.log-card__meta em {
  font-style: normal;
  font-variant-numeric: tabular-nums;
}

.log-card__action {
  justify-self: start;
  height: 32px;
  padding: 0 12px;
  border: 1px solid rgba(77, 143, 220, 0.26);
  border-radius: 8px;
  color: var(--do-primary-strong);
  background: rgba(77, 143, 220, 0.08);
  font-weight: 800;
  cursor: pointer;
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
  color: #b88732;
  font-weight: 700;
}

.execution-value.is-slow {
  color: #d95f5f;
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
  color: #f56c6c;
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

@media (max-width: 768px) {
  .operation-log-page {
    gap: 12px;
  }

  .content-panel {
    padding: 12px;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .filter-item,
  .filter-item--keyword,
  .filter-item--wide,
  .filter-item--time {
    width: 100%;
  }

  .filter-actions {
    justify-content: flex-start;
  }

  .log-table-wrap {
    display: none;
  }

  .log-card-list {
    display: grid;
    gap: 10px;
  }

  .panel-title {
    align-items: flex-start;
  }
}
</style>
