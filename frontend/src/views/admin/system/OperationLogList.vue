<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ClipboardList, RefreshCw, Search, X } from 'lucide-vue-next'
import { listOperationLogs, type OperationLogItem, type OperationLogQuery } from '../../../api/admin/operation-log'

const loading = ref(false)
const logs = ref<OperationLogItem[]>([])
const total = ref(0)
const detailVisible = ref(false)
const selectedLog = ref<OperationLogItem>()

const query = reactive<OperationLogQuery>({
  page: 1,
  pageSize: 20,
  targetResource: '',
})

const hasFilter = computed(() => Boolean(query.targetResource?.trim()))

watch(
  () => query.targetResource,
  () => {
    if (!query.targetResource) {
      query.page = 1
      fetchLogs()
    }
  },
)

async function fetchLogs() {
  loading.value = true
  try {
    const params: OperationLogQuery = {
      page: query.page,
      pageSize: query.pageSize,
      targetResource: query.targetResource?.trim() || undefined,
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
  query.targetResource = ''
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

onMounted(fetchLogs)
</script>

<template>
  <main class="operation-log-page post-login-page">
    <section class="page-actions">
      <el-input
        v-model="query.targetResource"
        class="resource-filter"
        clearable
        placeholder="目标资源"
        @keyup.enter="handleSearch"
      >
        <template #prefix>
          <ClipboardList :size="16" />
        </template>
      </el-input>
      <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      <el-button v-if="hasFilter" :icon="X" @click="handleReset">重置</el-button>
      <el-button :icon="RefreshCw" :loading="loading" @click="fetchLogs">刷新</el-button>
    </section>

    <section class="content-panel">
      <el-table
        v-loading="loading"
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
            {{ row.executionMs ?? '-' }}ms
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

      <div v-if="selectedLog" class="detail-block">
        <h3>请求参数</h3>
        <pre>{{ formatParams(selectedLog.requestParams) }}</pre>
      </div>

      <div v-if="selectedLog?.errorMessage" class="detail-block">
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

.page-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.resource-filter {
  width: min(280px, 100%);
}

.content-panel {
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
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

.detail-block pre {
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
</style>
