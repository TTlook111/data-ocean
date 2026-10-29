<script setup lang="ts">
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { computed, reactive, ref, onBeforeUnmount, onMounted, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { RefreshCw } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { useGovernanceScope } from '../../../composables/useGovernanceScope'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import {
  updateTableGovernanceStatus,
  updateColumnGovernanceStatus,
  batchUpdateGovernanceStatus,
  listSnapshotTables,
  listSnapshotTableColumns,
  type TableMetaItem,
  type ColumnMetaItem,
} from '../../../api/admin/governance'
import ErrorState from '../../../components/common/ErrorState.vue'
import { governanceStatusLabel } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'

const scope = useGovernanceScope()
const receipt = ref('')
const ownCancelClass = `status-confirm-${Math.random().toString(36).slice(2)}`
function cancelOwnedConfirmation() {
  document.querySelector<HTMLButtonElement>(`.${ownCancelClass}`)?.click()
}
const loading = ref(false)
const tableError = ref('')
const columnError = ref('')
const columnLoading = ref(false)
const tableSearch = ref('')
const visibleTables = computed(() =>
  tables.value.filter(
    (table) =>
      !tableSearch.value.trim() ||
      `${table.tableName} ${table.tableComment || ''}`
        .toLowerCase()
        .includes(tableSearch.value.trim().toLowerCase()),
  ),
)
let writeScopeVersion = 0
const selectedSnapshotId = ref<number | undefined>()
const tables = ref<TableMetaItem[]>([])
const selectedTable = ref<string>('')
const columns = ref<ColumnMetaItem[]>([])
const adminContext = useAdminContextStore()
const iamS1 = useIamS1Store()
const route = useRoute()
/** 从对象详情进入时用于定位表；只在首次加载生效，不覆盖用户后续选择。 */
const pendingTableName = ref(typeof route.query?.tableName === 'string' ? route.query.tableName : '')
const tableActionLoading = reactive<Record<string, boolean>>({})
const columnActionLoading = reactive<Record<number, boolean>>({})
const batchActionLoading = ref(false)
let datasourceRequestId = 0
let snapshotRequestId = 0
let columnRequestId = 0
let disposed = false

const statusOptions = ['DISCOVERED', 'NORMAL', 'RECOMMENDED', 'DEPRECATED', 'SENSITIVE', 'BLOCKED']

const RULE_MANAGE_FUNCTION = 'governance:rule:manage'
const METADATA_VIEW_FUNCTION = 'metadata:view'

/** 读取快照 / 表 / 字段：要求 `metadata:view` 与当前数据源在同一绑定上成立。 */
const canViewMetadata = computed(() => scope.can(METADATA_VIEW_FUNCTION))

/** 修改表/列治理状态：要求 `governance:rule:manage` 与当前数据源在同一绑定上成立。 */
const canManageStatus = computed(() => scope.can(RULE_MANAGE_FUNCTION))

async function fetchSnapshots() {
  cancelOwnedConfirmation()
  receipt.value = ''
  datasourceRequestId++
  snapshotRequestId++
  columnRequestId++
  writeScopeVersion++
  Object.keys(tableActionLoading).forEach((key) => delete tableActionLoading[key])
  Object.keys(columnActionLoading).forEach((key) => delete columnActionLoading[Number(key)])
  batchActionLoading.value = false
  selectedSnapshotId.value = adminContext.snapshotId
  tables.value = []
  columns.value = []
  selectedTable.value = ''
  tableError.value = ''
  loading.value = false
  columnError.value = ''
  columnLoading.value = false
  if (
    !canViewMetadata.value ||
    !adminContext.datasourceId ||
    !adminContext.snapshotId ||
    adminContext.routeSyncing ||
    adminContext.routeSyncError
  )
    return
  await fetchTables()
}

async function fetchTables() {
  const currentRequest = ++snapshotRequestId,
    epoch = scope.capture()
  columnRequestId++
  const snapshotId = selectedSnapshotId.value
  tableError.value = ''
  if (!snapshotId || !canViewMetadata.value) {
    tables.value = []
    columns.value = []
    selectedTable.value = ''
    return
  }
  loading.value = true
  try {
    const res = await listSnapshotTables(snapshotId)
    if (
      disposed ||
      currentRequest !== snapshotRequestId ||
      !scope.isCurrent(epoch) ||
      snapshotId !== selectedSnapshotId.value
    )
      return
    tables.value = res.data ?? []
    columns.value = []
    selectedTable.value = ''
    // 对象详情入口传入 tableName 时定位到该表；只消费一次，避免刷新覆盖用户选择。
    const targetTable = pendingTableName.value
    pendingTableName.value = ''
    if (targetTable && tables.value.some((item) => item.tableName === targetTable)) {
      await fetchColumns(targetTable)
    }
  } catch {
    if (!disposed && scope.isCurrent(epoch) && currentRequest === snapshotRequestId) {
      tableError.value = '表列表读取失败，请重试。'
      tables.value = []
      columns.value = []
    }
  } finally {
    if (!disposed && scope.isCurrent(epoch) && currentRequest === snapshotRequestId) loading.value = false
  }
}

async function fetchColumns(tableName: string) {
  const currentRequest = ++columnRequestId,
    epoch = scope.capture()
  const snapshotId = selectedSnapshotId.value
  if (!snapshotId || !canViewMetadata.value) return
  if (selectedTable.value !== tableName) {
    writeScopeVersion++
    Object.keys(tableActionLoading).forEach((key) => delete tableActionLoading[key])
    Object.keys(columnActionLoading).forEach((key) => delete columnActionLoading[Number(key)])
    batchActionLoading.value = false
  }
  selectedTable.value = tableName
  columns.value = []
  columnError.value = ''
  columnLoading.value = true
  try {
    const res = await listSnapshotTableColumns(snapshotId, tableName)
    if (
      disposed ||
      currentRequest !== columnRequestId ||
      !scope.isCurrent(epoch) ||
      snapshotId !== selectedSnapshotId.value ||
      tableName !== selectedTable.value
    )
      return
    columns.value = res.data ?? []
  } catch {
    if (!disposed && scope.isCurrent(epoch) && currentRequest === columnRequestId)
      columnError.value = '字段读取失败，请重试。'
  } finally {
    if (!disposed && scope.isCurrent(epoch) && currentRequest === columnRequestId) columnLoading.value = false
  }
}

/** Serialize the whole table: a batch and an individual column may overlap. */
async function mutateStatus(tableName: string, newStatus: string, columnId?: number, batch = false) {
  const snapshotId = selectedSnapshotId.value,
    epoch = scope.capture(),
    version = writeScopeVersion
  if (!snapshotId || !canManageStatus.value || !statusOptions.includes(newStatus)) return
  if (!tables.value.some((t) => t.tableName === tableName)) return
  if (
    columnId !== undefined &&
    (selectedTable.value !== tableName || !columns.value.some((c) => c.id === columnId))
  )
    return
  if (
    tableActionLoading[tableName] ||
    batchActionLoading.value ||
    Object.values(columnActionLoading).some(Boolean)
  )
    return
  const current = () =>
    !disposed &&
    scope.isCurrent(epoch) &&
    version === writeScopeVersion &&
    snapshotId === selectedSnapshotId.value &&
    canManageStatus.value
  tableActionLoading[tableName] = true
  if (columnId !== undefined) columnActionLoading[columnId] = true
  if (batch) batchActionLoading.value = true
  receipt.value = ''
  try {
    if (['DEPRECATED', 'BLOCKED'].includes(newStatus)) {
      await ElMessageBox.confirm(
        `确认将${batch ? '表「' + tableName + '」下全部字段' : columnId !== undefined ? '字段 #' + columnId : '表「' + tableName + '」'}设置为${governanceStatusLabel(newStatus)}？这会影响检索和问数；不会自动修复质量问题。`,
        batch ? '确认批量高影响状态' : '确认高影响状态',
        {
          type: 'warning',
          confirmButtonText: '确认修改',
          cancelButtonText: '取消',
          cancelButtonClass: ownCancelClass,
        },
      )
    }
    if (!current()) return
    const result = await trackGovernanceWrite<{ data: unknown }>(
      scope.auth.token,
      [`governance-status:${snapshotId}:${tableName}`],
      `${batch ? '批量字段状态' : columnId !== undefined ? '字段 #' + columnId : '表'} · ${tableName} / 快照 #${snapshotId}`,
      async () => {
        const response = await (batch
          ? batchUpdateGovernanceStatus(snapshotId, tableName, { governanceStatus: newStatus })
          : columnId !== undefined
            ? updateColumnGovernanceStatus(snapshotId, columnId, { governanceStatus: newStatus })
            : updateTableGovernanceStatus(snapshotId, tableName, { governanceStatus: newStatus }))
        if (batch) {
          const data = response.data as { updated?: number; excluded?: number }
          if (!Number.isFinite(data?.updated) || !Number.isFinite(data?.excluded))
            throw new Error(
              `批量回执不完整：更新 ${data?.updated ?? '未知'}，排除 ${data?.excluded ?? '未知'}；结果未知，请核对原目标，勿重复提交`,
            )
        }
        return response
      },
    )
    if (!current()) return
    if (batch) {
      const data = result.data as { updated?: number; excluded?: number } | undefined
      receipt.value = `批量回执：更新 ${data?.updated ?? '未知'} 个字段，排除 ${data?.excluded ?? '未知'} 个。排除项及具体原因以服务端记录为准。`
      await fetchColumns(tableName)
    } else {
      const target =
        columnId !== undefined
          ? columns.value.find((c) => c.id === columnId)
          : tables.value.find((t) => t.tableName === tableName)
      if (target) target.governanceStatus = newStatus
      receipt.value = `${columnId !== undefined ? '字段' : '表'}状态已更新为${governanceStatusLabel(newStatus)}；质量问题、审核和发布需分别核验。`
    }
  } catch (cause) {
    if (current() && cause !== 'cancel' && cause !== 'close')
      receipt.value =
        (cause as any)?.response?.data?.message ||
        (cause instanceof Error ? cause.message : '状态更新未确认完成，请先重读原快照对象，勿重复提交')
  } finally {
    if (current()) {
      tableActionLoading[tableName] = false
      if (columnId !== undefined) columnActionLoading[columnId] = false
      if (batch) batchActionLoading.value = false
    }
  }
}
function changeTableStatus(tableName: string, status: string) {
  return mutateStatus(tableName, status)
}
function changeColumnStatus(id: number, status: string) {
  return mutateStatus(selectedTable.value, status, id)
}
function batchSetColumns(status: string) {
  return mutateStatus(selectedTable.value, status, undefined, true)
}
async function refresh() {
  await Promise.all([adminContext.initialize(), iamS1.load(Boolean(iamS1.errorMessage))])
  await fetchSnapshots()
}

onMounted(async () => {
  await Promise.all([adminContext.initialize(), iamS1.load()])
  await fetchSnapshots()
})

watch(
  scope.generation,
  () => {
    void fetchSnapshots()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  cancelOwnedConfirmation()
  disposed = true
  datasourceRequestId++
  snapshotRequestId++
  columnRequestId++
  writeScopeVersion++
})
</script>

<template>
  <main class="status-page post-login-page governance-workspace governance-surface">
    <TaskPageHeader
      title="治理状态维护"
      eyebrow="数据治理 / 对象可用性"
      description-mode="inline"
      description="维护当前快照的对象可用状态。废弃或阻断会影响后续检索和查询；修改状态不等于修复质量问题。"
    >
      <template #actions><el-button :icon="RefreshCw" @click="refresh">刷新</el-button></template>
    </TaskPageHeader>
    <GovernanceWriteNotice />
    <section class="status-contract">
      <strong>先核对对象，再调整可用性</strong>
      <p>
        废弃 /
        阻断影响检索与问数；正常可用不等于质量通过、语义审核或知识已发布。敏感状态不替代独立授权与脱敏策略。
      </p>
    </section>
    <p v-if="scope.scopeIssue.value" role="status">{{ scope.scopeIssue.value }}</p>
    <p v-if="receipt" class="status-receipt" role="status">{{ receipt }}</p>
    <p v-if="!canViewMetadata" class="capability-hint">没有“查看资产结构”能力，无法读取表与字段。</p>
    <p v-else-if="!canManageStatus" class="capability-hint">
      可以查看表与字段，但没有“维护治理规则”能力，状态修改不可用。
    </p>
    <ErrorState v-if="tableError" :message="tableError" @retry="fetchTables" />
    <p v-if="!adminContext.snapshotId" class="governance-purpose">
      请在顶部公共范围栏选择快照，不自动切换到其他版本。
    </p>
    <div class="split-layout">
      <!-- 左侧：表列表 -->
      <section class="table-list-panel">
        <h3>
          表与治理状态
          <small>已加载 {{ tables.length }} 张表</small>
        </h3>
        <el-input v-model="tableSearch" clearable placeholder="搜索表名或说明" aria-label="搜索治理状态表" />
        <div v-loading="loading" class="table-items">
          <div
            v-for="t in visibleTables"
            :key="t.id"
            class="table-item"
            :class="{ active: selectedTable === t.tableName }"
          >
            <button
              type="button"
              class="table-name"
              :aria-pressed="selectedTable === t.tableName"
              @click="fetchColumns(t.tableName)"
            >
              {{ t.tableName }}
              <small class="table-comment">{{ t.tableComment || '未提供表说明' }}</small>
            </button>
            <el-select
              :model-value="t.governanceStatus"
              :loading="tableActionLoading[t.tableName]"
              size="small"
              style="width: 110px"
              :disabled="!canManageStatus || tableActionLoading[t.tableName]"
              @click.stop
              @change="(v: string) => changeTableStatus(t.tableName, v)"
            >
              <el-option v-for="s in statusOptions" :key="s" :value="s" :label="governanceStatusLabel(s)" />
            </el-select>
          </div>
          <el-empty
            v-if="
              !loading && !tableError && canViewMetadata && adminContext.snapshotId && !visibleTables.length
            "
            :description="tables.length ? '没有匹配的表' : '当前快照没有表'"
            :image-size="60"
          />
        </div>
      </section>

      <!-- 右侧：字段列表 -->
      <section class="column-panel" v-if="selectedTable">
        <div class="column-header">
          <h3>{{ selectedTable }} 字段</h3>
          <div class="batch-actions">
            <el-button
              size="small"
              :loading="batchActionLoading"
              :disabled="!canManageStatus || tableActionLoading[selectedTable] || columnLoading"
              @click="batchSetColumns('NORMAL')"
            >
              全部设为正常可用
            </el-button>
            <el-button
              size="small"
              type="warning"
              :loading="batchActionLoading"
              :disabled="!canManageStatus || tableActionLoading[selectedTable] || columnLoading"
              @click="batchSetColumns('DEPRECATED')"
            >
              全部废弃
            </el-button>
          </div>
        </div>
        <ErrorState v-if="columnError" :message="columnError" @retry="fetchColumns(selectedTable)" />
        <el-table v-else v-loading="columnLoading" :data="columns" stripe size="small">
          <el-table-column prop="columnName" label="字段名" width="160" />
          <el-table-column prop="dataType" label="类型" width="120" />
          <el-table-column prop="columnComment" label="字段说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="治理状态" width="140">
            <template #default="{ row }">
              <el-select
                :model-value="row.governanceStatus"
                :loading="columnActionLoading[row.id]"
                size="small"
                :disabled="!canManageStatus || tableActionLoading[selectedTable]"
                @change="(v: string) => changeColumnStatus(row.id, v)"
              >
                <el-option v-for="s in statusOptions" :key="s" :value="s" :label="governanceStatusLabel(s)" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <section v-if="!selectedTable" class="column-panel">
        <h3>选择一张表</h3>
        <p>查看字段及治理状态</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.status-contract,
.status-receipt {
  padding: 16px 20px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
  font-size: 13px;
}
.status-contract p {
  margin: 6px 0 0;
  color: var(--do-muted);
  line-height: 1.7;
}
.status-page .split-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 20px;
  align-items: stretch;
  padding: 20px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 14px;
  min-width: 0;
}
.status-page .table-list-panel {
  min-width: 0;
  padding-right: 22px;
  border-right: 1px solid var(--do-line);
}
.status-page h3 {
  margin: 0 0 14px;
  color: var(--do-ink);
  font-size: 15px;
}
.status-page h3 small {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: var(--do-muted);
}
.status-page .table-items {
  margin-top: 12px;
}
.status-page .table-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 72px;
  padding: 12px 8px;
  border-bottom: 1px solid var(--do-line);
}
.status-page .table-item.active {
  background: var(--do-primary-soft);
}
.status-page .table-name {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  text-align: left;
  background: none;
  font: inherit;
  font-size: 13px;
  color: var(--do-ink);
  cursor: pointer;
  overflow-wrap: anywhere;
}
.status-page .table-name:focus-visible {
  outline: 2px solid var(--do-primary);
}
.status-page .table-comment {
  display: block;
  margin-top: 4px;
  color: var(--do-muted);
  font-size: 12px;
}
.status-page .column-panel {
  min-width: 0;
}
.status-page .column-panel:has(> h3) {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  min-height: 300px;
  color: var(--do-muted);
}
.status-page .column-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.status-page .column-header h3 {
  margin: 0;
}
.status-page .batch-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.status-page .capability-hint {
  font-size: 13px;
  color: var(--do-muted);
  margin: 0;
}
@media (max-width: 900px) {
  .status-page .split-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .status-page .table-list-panel {
    border: 0;
    padding: 0;
  }
  .status-page .table-items {
    max-height: 210px;
    overflow-y: auto;
  }
}
</style>
