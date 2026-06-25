<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CheckCircle, Clock, Play, Send, RotateCcw } from 'lucide-vue-next'
import { listSimpleDatasources, type DatasourceSimpleItem } from '../../../api/admin/datasource'
import {
  listVersionHistory,
  listSnapshotAuditLogs,
  changeSnapshotStatus,
  publishSnapshot,
  revokeSnapshot,
  type VersionHistoryItem,
  type AuditLogItem
} from '../../../api/admin/versioning'
import {
  auditActionLabel,
  snapshotStatusLabel,
  snapshotStatusLabels,
  snapshotStatusType,
} from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'

const loading = ref(false)
const adminContext = useAdminContextStore()
const datasources = ref<DatasourceSimpleItem[]>([])
const selectedDatasourceId = ref<number | undefined>()
const history = ref<VersionHistoryItem[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 10 })

const auditDialogVisible = ref(false)
const auditLogs = ref<AuditLogItem[]>([])
const auditLoading = ref(false)
const auditSnapshotId = ref<number>(0)

const statusFlow = ['DRAFT', 'CHECKING', 'ISSUE_FOUND', 'APPROVED', 'PUBLISHED', 'EXPIRED']

async function fetchDatasources() {
  const res = await listSimpleDatasources()
  datasources.value = res.data ?? []
}

async function fetchHistory() {
  loading.value = true
  try {
    const res = await listVersionHistory(selectedDatasourceId.value, query)
    history.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function handlePublish(item: VersionHistoryItem) {
  await ElMessageBox.confirm(`确认发布快照版本 ${item.snapshotVersion}？发布后旧版本将自动过期。`, '确认发布')
  try {
    await publishSnapshot(item.snapshotId)
    ElMessage.success('发布成功')
    fetchHistory()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '发布失败')
  }
}

async function handleRevoke(item: VersionHistoryItem) {
  const { value: reason } = await ElMessageBox.prompt('请输入撤回原因', '紧急撤回', {
    confirmButtonText: '确认撤回',
    cancelButtonText: '取消',
    inputValidator: (v) => !!v?.trim() || '原因不能为空'
  })
  try {
    await revokeSnapshot(item.snapshotId, reason)
    ElMessage.success('撤回成功')
    fetchHistory()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '撤回失败')
  }
}

async function handleStatusChange(item: VersionHistoryItem, targetStatus: string) {
  try {
    await changeSnapshotStatus(item.snapshotId, { targetStatus })
    ElMessage.success('状态变更成功')
    fetchHistory()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '状态变更失败')
  }
}

async function showAuditLogs(snapshotId: number) {
  auditSnapshotId.value = snapshotId
  auditDialogVisible.value = true
  auditLoading.value = true
  try {
    const res = await listSnapshotAuditLogs(snapshotId, { page: 1, size: 50 })
    auditLogs.value = res.data?.records ?? []
  } finally {
    auditLoading.value = false
  }
}

function onDatasourceChange() {
  if (selectedDatasourceId.value) {
    adminContext.selectDatasource(selectedDatasourceId.value)
  }
  query.page = 1
  fetchHistory()
}

onMounted(async () => {
  await adminContext.initialize()
  selectedDatasourceId.value = adminContext.datasourceId
  fetchDatasources()
  fetchHistory()
})

watch(
  () => adminContext.datasourceId,
  (datasourceId) => {
    if (selectedDatasourceId.value === datasourceId) return
    selectedDatasourceId.value = datasourceId
    query.page = 1
    fetchHistory()
  },
)
</script>

<template>
  <main class="lifecycle-page post-login-page">
    <section class="command-header">
      <div class="command-title">
        <span>治理中心 / 快照生命周期</span>
        <h2>快照发布轨道</h2>
        <p>把 DRAFT、CHECKING、APPROVED、PUBLISHED 等状态作为治理流程管理，发布前确认质量分、表字段规模与审计日志。</p>
      </div>
      <div class="command-actions">
        <span class="trust-badge">发布前校验</span>
        <span class="trust-badge">可回溯审计</span>
      </div>
    </section>

    <section class="toolbar">
      <el-select v-model="selectedDatasourceId" placeholder="全部数据源" clearable
                 @change="onDatasourceChange" style="width: 280px">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
    </section>

    <section class="status-flow">
      <div class="flow-steps">
        <div class="flow-step" v-for="s in statusFlow" :key="s">
          <div class="step-dot" :class="'dot-' + s.toLowerCase().replace('_','-')"></div>
          <span class="step-label">{{ snapshotStatusLabels[s] }}</span>
        </div>
      </div>
    </section>

    <section class="table-shell" v-loading="loading">
      <el-empty v-if="!loading && history.length === 0" description="暂无快照记录" />
      <el-table v-else :data="history" border stripe>
        <el-table-column label="数据源" prop="datasourceName" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.datasourceName || '-' }}</template>
        </el-table-column>
        <el-table-column label="版本" width="80" align="center">
          <template #default="{ row }">版本 {{ row.snapshotVersion }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="snapshotStatusType(row.status)" size="small">
              {{ snapshotStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="表数" prop="tableCount" width="80" align="center" />
        <el-table-column label="字段数" prop="columnCount" width="90" align="center" />
        <el-table-column label="质量分" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.qualityScore != null" :style="{ color: row.qualityScore >= 80 ? '#67c23a' : row.qualityScore >= 60 ? '#e6a23c' : '#f56c6c' }">
              {{ row.qualityScore }}
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="170" />
        <el-table-column label="发布时间" width="170">
          <template #default="{ row }">{{ row.publishedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="240">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" size="small" @click="handleStatusChange(row, 'CHECKING')">
              <Play :size="14" /> 开始校验
            </el-button>
            <el-button v-if="row.status === 'ISSUE_FOUND'" size="small" @click="handleStatusChange(row, 'APPROVED')">
              <CheckCircle :size="14" /> 审核通过
            </el-button>
            <el-button v-if="row.status === 'APPROVED'" size="small" type="primary" @click="handlePublish(row)">
              <Send :size="14" /> 发布
            </el-button>
            <el-button v-if="row.status === 'PUBLISHED'" size="small" type="danger" @click="handleRevoke(row)">
              <RotateCcw :size="14" /> 撤回
            </el-button>
            <el-button size="small" @click="showAuditLogs(row.snapshotId)">
              <Clock :size="14" /> 日志
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination v-if="total > query.size" class="pager"
      v-model:current-page="query.page" :page-size="query.size" :total="total"
      layout="total, prev, pager, next" @current-change="fetchHistory" />

    <el-dialog v-model="auditDialogVisible" title="操作日志" width="650px">
      <el-table :data="auditLogs" v-loading="auditLoading" border size="small">
        <el-table-column label="操作" width="140">
          <template #default="{ row }">{{ auditActionLabel(row.action) }}</template>
        </el-table-column>
        <el-table-column label="状态变更" width="180">
          <template #default="{ row }">
            <span v-if="row.oldStatus">{{ snapshotStatusLabel(row.oldStatus) }} → {{ snapshotStatusLabel(row.newStatus) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" prop="operatorName" width="100" />
        <el-table-column label="原因" prop="reason" show-overflow-tooltip />
        <el-table-column label="时间" prop="createdAt" width="170" />
      </el-table>
    </el-dialog>
  </main>
</template>

<style scoped>
.lifecycle-page { display: grid; gap: 16px; }

.toolbar {
  margin-bottom: 0;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.status-flow { margin-bottom: 0; }

.flow-steps {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.flow-step {
  position: relative;
  min-height: 72px;
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 8px;
  padding: 12px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: linear-gradient(180deg, #fff, #f8fafc);
}

.flow-step:not(:last-child)::after {
  position: absolute;
  left: calc(100% + 1px);
  top: 50%;
  z-index: 1;
  width: 10px;
  height: 2px;
  background: var(--do-line-strong);
  content: '';
}

.step-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.dot-draft { background: #909399; }
.dot-checking { background: #e6a23c; }
.dot-issue-found { background: #f56c6c; }
.dot-approved { background: #409eff; }
.dot-published { background: #67c23a; }
.dot-expired { background: #c0c4cc; }

.step-label {
  color: var(--do-ink);
  font-size: 12px;
  font-weight: 800;
  text-align: center;
}

.text-muted { color: var(--do-muted); }
.pager { margin-top: 16px; justify-content: flex-end; }

@media (max-width: 900px) {
  .flow-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .flow-step:not(:last-child)::after {
    display: none;
  }
}
</style>
