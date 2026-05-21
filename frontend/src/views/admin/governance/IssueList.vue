<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import {
  listQualityIssues,
  handleIssue,
  batchHandleIssues,
  type QualityIssueItem
} from '../../../api/admin/governance'
import { listSnapshots } from '../../../api/admin/metadata'

const loading = ref(false)
const issues = ref<QualityIssueItem[]>([])
const total = ref(0)
const selectedIds = ref<number[]>([])
const snapshots = ref<Array<{ id: number; snapshotVersion: number }>>([])

const query = reactive({
  snapshotId: undefined as number | undefined,
  dimension: '',
  severity: '',
  status: '',
  tableName: '',
  page: 1,
  size: 20
})

const dimensionOptions = [
  { label: '全部维度', value: '' },
  { label: '完整性', value: 'COMPLETENESS' },
  { label: '准确性', value: 'ACCURACY' },
  { label: '一致性', value: 'CONSISTENCY' },
  { label: '时效性', value: 'TIMELINESS' },
  { label: '可追溯性', value: 'TRACEABILITY' }
]
const severityOptions = [
  { label: '全部级别', value: '' },
  { label: '高', value: 'HIGH' },
  { label: '中', value: 'MEDIUM' },
  { label: '低', value: 'LOW' }
]
const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '待处理', value: 'OPEN' },
  { label: '已确认', value: 'CONFIRMED' },
  { label: '已解决', value: 'RESOLVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '自动关闭', value: 'AUTO_CLOSED' }
]

const statusType = (s: string) => {
  const map: Record<string, string> = { OPEN: 'warning', CONFIRMED: '', RESOLVED: 'success', REJECTED: 'info', AUTO_CLOSED: 'info' }
  return map[s] || ''
}
const statusLabel = (s: string) => statusOptions.find(o => o.value === s)?.label || s
const dimLabel = (d: string) => dimensionOptions.find(o => o.value === d)?.label || d
const sevType = (s: string) => s === 'HIGH' ? 'danger' : s === 'MEDIUM' ? 'warning' : 'info'

async function fetchSnapshots() {
  const res = await listSnapshots({ page: 1, size: 50 })
  snapshots.value = res.data?.records ?? []
  if (snapshots.value.length && !query.snapshotId) {
    query.snapshotId = snapshots.value[0].id
  }
}

async function fetchIssues() {
  if (!query.snapshotId) return
  loading.value = true
  try {
    const res = await listQualityIssues(query.snapshotId, {
      dimension: query.dimension || undefined,
      severity: query.severity || undefined,
      status: query.status || undefined,
      tableName: query.tableName || undefined,
      page: query.page,
      size: query.size
    })
    issues.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function doHandle(issueId: number, status: string) {
  try {
    await handleIssue(issueId, { status })
    ElMessage.success('操作成功')
    fetchIssues()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function doBatchHandle(status: string) {
  if (!selectedIds.value.length) { ElMessage.warning('请选择问题'); return }
  try {
    const res = await batchHandleIssues({ issueIds: selectedIds.value, status })
    ElMessage.success(`已处理 ${res.data?.updated ?? 0} 条`)
    selectedIds.value = []
    fetchIssues()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '批量操作失败')
  }
}

function onSelectionChange(rows: QualityIssueItem[]) {
  selectedIds.value = rows.map(r => r.id)
}

onMounted(async () => {
  await fetchSnapshots()
  fetchIssues()
})
</script>

<template>
  <main class="issue-page post-login-page">
    <header class="page-header">
      <div>
        <p>元数据治理</p>
        <h1>问题清单</h1>
        <span class="header-subtitle">查看和处理质量校验发现的问题</span>
      </div>
      <el-button :icon="RefreshCw" @click="fetchIssues">刷新</el-button>
    </header>

    <section class="toolbar">
      <el-select v-model="query.snapshotId" placeholder="选择快照" style="width: 180px" @change="fetchIssues">
        <el-option v-for="s in snapshots" :key="s.id" :value="s.id" :label="`快照 #${s.id} v${s.snapshotVersion}`" />
      </el-select>
      <el-select v-model="query.dimension" style="width: 120px" @change="fetchIssues">
        <el-option v-for="o in dimensionOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="query.severity" style="width: 100px" @change="fetchIssues">
        <el-option v-for="o in severityOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="query.status" style="width: 120px" @change="fetchIssues">
        <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-input v-model="query.tableName" placeholder="表名筛选" clearable style="width: 150px" @clear="fetchIssues" @keyup.enter="fetchIssues" />
    </section>

    <section class="batch-bar" v-if="selectedIds.length">
      <span>已选 {{ selectedIds.length }} 项</span>
      <el-button size="small" @click="doBatchHandle('CONFIRMED')">批量确认</el-button>
      <el-button size="small" type="danger" @click="doBatchHandle('REJECTED')">批量驳回</el-button>
    </section>

    <section class="table-shell">
      <el-table :data="issues" v-loading="loading" stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="40" />
        <el-table-column prop="tableName" label="表" width="140" />
        <el-table-column prop="columnName" label="字段" width="120">
          <template #default="{ row }">{{ row.columnName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="dimension" label="维度" width="90">
          <template #default="{ row }">{{ dimLabel(row.dimension) }}</template>
        </el-table-column>
        <el-table-column prop="severity" label="级别" width="70">
          <template #default="{ row }">
            <el-tag :type="sevType(row.severity)" size="small">{{ row.severity === 'HIGH' ? '高' : row.severity === 'MEDIUM' ? '中' : '低' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="issueDescription" label="问题描述" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'OPEN'">
              <el-button link size="small" @click="doHandle(row.id, 'CONFIRMED')">确认</el-button>
              <el-button link size="small" type="danger" @click="doHandle(row.id, 'REJECTED')">驳回</el-button>
            </template>
            <template v-else-if="row.status === 'CONFIRMED'">
              <el-button link size="small" type="success" @click="doHandle(row.id, 'RESOLVED')">解决</el-button>
              <el-button link size="small" type="danger" @click="doHandle(row.id, 'REJECTED')">驳回</el-button>
            </template>
            <span v-else class="muted-text">-</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination class="pager" background layout="total, prev, pager, next"
                   :total="total" :page-size="query.size"
                   v-model:current-page="query.page" @current-change="fetchIssues" />
  </main>
</template>

<style scoped>
.issue-page { display: grid; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; }
.page-header p { font-size: 12px; color: var(--do-muted); margin: 0 0 4px; }
.page-header h1 { font-size: 22px; margin: 0; color: var(--do-ink); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.toolbar { display: flex; gap: 10px; flex-wrap: wrap; }
.batch-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding: 8px 12px; background: var(--do-primary-soft); border-radius: 6px; font-size: 13px; }
.table-shell { border: 1px solid var(--do-line); border-radius: 8px; overflow: hidden; background: var(--do-surface); }
.pager { margin-top: 16px; justify-content: flex-end; }
.muted-text { color: var(--do-muted); font-size: 12px; }
</style>
