<script setup lang="ts">
/**
 * IAM-SIMPLE-1 独立安全问数入口
 *
 * 本页面只调用 `/api/iam-s1/query` 新链路：提交、任务读取、SQL 查看、导出、反馈、SSE 全部使用新接口，
 * 单次请求不混用旧 `/api/query`。旧问数入口在 B5 正式切换前继续独立运行。
 *
 * 与旧入口的关键差异：S1 要求先声明本次查询要用的表和字段（“资源声明”），
 * 服务端据此生成权限快照，因此不会出现“先查询、后补权限”的不安全顺序。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '../../components/common/EmptyState.vue'
import TaskPageHeader from '../../components/admin/TaskPageHeader.vue'
import { useIamS1Store } from '../../stores/iamS1'
import {
  IAM_S1_DEFAULT_QUERY_USAGES,
  IAM_S1_EXTENDED_QUERY_USAGES,
  iamS1Ask,
  iamS1CancelTask,
  iamS1ExportCsv,
  iamS1GetTask,
  iamS1QueryHistory,
  iamS1StreamTask,
  iamS1SubmitFeedback,
  iamS1ViewSql,
  listIamS1QueryResourceColumns,
  listIamS1QueryResourceDatasources,
  listIamS1QueryResourceSnapshots,
  listIamS1QueryResourceTables,
  type IamS1ColumnOption,
  type IamS1ColumnUsage,
  type IamS1DatasourceRef,
  type IamS1TableDeclaration,
  type IamS1TableOption,
} from '../../api/iamS1'

const iamS1 = useIamS1Store()

const POLL_MAX = 60
const POLL_INTERVAL = 2000

const datasources = ref<IamS1DatasourceRef[]>([])
const datasourceId = ref<number>()
const snapshots = ref<Array<{ id: number; snapshotVersion?: number }>>([])
const snapshotId = ref<number>()
const tables = ref<IamS1TableOption[]>([])
const columns = ref<IamS1ColumnOption[]>([])
const declaredTables = ref<IamS1TableDeclaration[]>([])
const draftTable = ref<string>()
const draftColumns = ref<string[]>([])
/** 是否放开排序/分组/聚合/子查询位置；默认只放开 PROJECTION/FILTER/JOIN。 */
const allowExtendedUsage = ref(false)
/** 字段保护状态（按“表.字段”记录），用于生成合规的 usage。 */
const columnProtection = ref<Record<string, string>>({})
const question = ref('')
const submitting = ref(false)
const currentTaskId = ref<string>()
const abortController = ref<AbortController>()
const progressMessage = ref('')
const result = ref<Record<string, unknown> | null>(null)
const resultTab = ref<'table' | 'sql' | 'trust'>('table')
const sqlText = ref('')
const history = ref<Record<string, unknown>[]>([])
const loadingHistory = ref(false)

const canQuery = computed(() => iamS1.queryUse)
const canViewSql = computed(() => iamS1.viewSql)
const canExport = computed(() => iamS1.exportResult)
const resultColumns = computed<string[]>(() => {
  const rows = (result.value?.data as Record<string, unknown>[] | undefined) ?? []
  return rows.length ? Object.keys(rows[0]) : []
})

async function loadDatasources() {
  // 用户侧资源接口（scope=QUERY）：可见性来自“主体命中且当前有效的数据授权”，
  // 不要求后台负责源。系统管理员没有显式数据授权时同样为空，符合 S1 语义。
  const result = await listIamS1QueryResourceDatasources('QUERY')
  datasources.value = result.data ?? []
  datasourceId.value = datasources.value[0]?.id
}

async function loadSnapshots() {
  snapshotId.value = undefined
  tables.value = []
  columns.value = []
  declaredTables.value = []
  if (!datasourceId.value) return
  const result = await listIamS1QueryResourceSnapshots(datasourceId.value, 'QUERY')
  snapshots.value = result.data ?? []
  snapshotId.value = snapshots.value[0]?.id
  await loadTables()
}

async function loadTables() {
  draftTable.value = undefined
  draftColumns.value = []
  columns.value = []
  if (!datasourceId.value || !snapshotId.value) return
  const result = await listIamS1QueryResourceTables(datasourceId.value, snapshotId.value, 'QUERY')
  tables.value = result.data ?? []
}

async function loadColumns() {
  draftColumns.value = []
  columns.value = []
  if (!datasourceId.value || !snapshotId.value || !draftTable.value) return
  const result = await listIamS1QueryResourceColumns(datasourceId.value, snapshotId.value, draftTable.value, 'QUERY')
  columns.value = result.data ?? []
}

/**
 * 按字段保护状态生成使用位置：
 * - 脱敏字段只能直接投影（统一 Resolver 强制，多声明 FILTER/JOIN 会被拒绝）；
 * - 隐藏字段不参与问数；
 * - 普通字段按默认/扩展位置声明。
 */
function usagesFor(tableName: string, columnName: string): IamS1ColumnUsage[] {
  const level = columnProtection.value[`${tableName}.${columnName}`]
  if (level === 'MASKED') return ['PROJECTION']
  if (level === 'HIDDEN') return []
  if (allowExtendedUsage.value) return [...IAM_S1_DEFAULT_QUERY_USAGES, ...IAM_S1_EXTENDED_QUERY_USAGES]
  return [...IAM_S1_DEFAULT_QUERY_USAGES]
}

/** 重算全部声明的 usage：后端对每个字段强制要求非空 usage，缺失即拒绝提交。 */
function refreshUsages() {
  for (const table of declaredTables.value) {
    const usages: Record<string, IamS1ColumnUsage[]> = {}
    for (const column of table.referencedColumns) {
      const usage = usagesFor(table.tableName, column)
      if (usage.length) usages[column] = usage
    }
    table.columnUsages = usages
  }
}

watch(allowExtendedUsage, refreshUsages)

function addDeclaration() {
  if (!draftTable.value || !draftColumns.value.length) {
    ElMessage.warning('请选择表和至少一个字段；空字段不表示全部字段')
    return
  }
  const tableName = draftTable.value
  // 记录字段保护状态：脱敏字段只能声明 PROJECTION，否则提交必被统一 Resolver 拒绝。
  for (const column of columns.value) {
    columnProtection.value[`${tableName}.${column.columnName}`] = column.protectionLevel ?? 'NORMAL'
  }
  const existing = declaredTables.value.find((item) => item.tableName === tableName)
  if (existing) {
    existing.referencedColumns = Array.from(new Set([...existing.referencedColumns, ...draftColumns.value]))
  } else {
    declaredTables.value.push({
      tableName,
      referencedColumns: [...draftColumns.value],
    })
  }
  refreshUsages()
  draftColumns.value = []
}

async function loadHistory() {
  if (!canQuery.value) return
  loadingHistory.value = true
  try {
    const result = await iamS1QueryHistory({ page: 1, pageSize: 10 })
    history.value = (result.data?.records as Record<string, unknown>[]) ?? []
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取历史任务失败')
  } finally {
    loadingHistory.value = false
  }
}

/** 优先使用 S1 SSE 推送进度；不可用时只用 S1 轮询兜底，不回退旧链路。 */
async function trackTask(taskId: string, signal: AbortSignal) {
  let streamDone = false
  const streamPromise = iamS1StreamTask(taskId, {
    signal,
    onEvent: (event) => {
      try {
        const payload = JSON.parse(event.data) as Record<string, unknown>
        if (event.type === 'progress') {
          progressMessage.value = String(payload.progressMessage ?? progressMessage.value)
        }
        if (event.type === 'error') {
          progressMessage.value = String(payload.message ?? '任务被拒绝')
        }
      } catch {
        /* 事件载荷无法解析时忽略，最终结果以任务读取为准 */
      }
    },
  })
    .then(() => {
      streamDone = true
    })
    .catch(() => {
      streamDone = false
    })

  for (let attempt = 0; attempt < POLL_MAX; attempt++) {
    if (signal.aborted) break
    const payload = await iamS1GetTask(taskId)
    const task = payload.data
    if (task.status !== 'PROCESSING') {
      await streamPromise.catch(() => undefined)
      return task
    }
    progressMessage.value = String(task.progressMessage ?? progressMessage.value)
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL))
    if (streamDone) break
  }
  await streamPromise.catch(() => undefined)
  const payload = await iamS1GetTask(taskId)
  return payload.data
}

async function ask() {
  if (!datasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  if (!declaredTables.value.length) {
    ElMessage.warning('请先声明本次查询要使用的表和字段')
    return
  }
  if (!question.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  submitting.value = true
  result.value = null
  sqlText.value = ''
  progressMessage.value = '正在提交 IAM-SIMPLE-1 查询...'
  const controller = new AbortController()
  abortController.value = controller
  try {
    const asked = await iamS1Ask({
      datasourceId: datasourceId.value,
      question: question.value.trim(),
      tables: declaredTables.value,
    })
    const taskId = asked.data.taskId
    currentTaskId.value = taskId
    progressMessage.value = '已提交，等待执行...'
    const task = await trackTask(taskId, controller.signal)
    result.value = task
    progressMessage.value = String(task.progressMessage ?? '')
    if (task.status === 'COMPLETED') {
      ElMessage.success('查询完成')
      await loadHistory()
    } else {
      ElMessage.warning(String(task.errorMessage ?? '查询未完成'))
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'IAM-SIMPLE-1 查询失败')
  } finally {
    submitting.value = false
    currentTaskId.value = undefined
    abortController.value = undefined
  }
}

async function cancel() {
  if (!currentTaskId.value) return
  abortController.value?.abort()
  try {
    await iamS1CancelTask(currentTaskId.value)
    ElMessage.info('已请求取消')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消失败')
  }
}

async function viewSql() {
  if (!currentTaskId.value) return
  try {
    const payload = await iamS1ViewSql(currentTaskId.value)
    sqlText.value = payload.data.sql
    resultTab.value = 'sql'
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '没有查看 SQL 的能力')
  }
}

async function exportCsv() {
  if (!currentTaskId.value) return
  try {
    const blob = await iamS1ExportCsv(currentTaskId.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `iam-s1-${currentTaskId.value}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败')
  }
}

async function feedback(type: 'LIKE' | 'DISLIKE') {
  if (!currentTaskId.value) return
  try {
    await iamS1SubmitFeedback(currentTaskId.value, type)
    ElMessage.success('反馈已提交')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '反馈失败')
  }
}

onMounted(async () => {
  await iamS1.load()
  try {
    await loadDatasources()
    await loadSnapshots()
    await loadHistory()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '初始化 IAM-SIMPLE-1 问数入口失败')
  }
})
</script>

<template>
  <div class="iam-s1-query">
    <TaskPageHeader
      title="IAM-SIMPLE-1 安全问数"
      description="先声明本次查询要用的表和字段，服务端据此生成权限快照并复查当前权限。旧问数入口在正式切换前保持独立运行。"
    />

    <el-alert
      v-if="iamS1.errorMessage"
      type="error"
      show-icon
      :closable="false"
      :title="iamS1.errorMessage"
    />

    <EmptyState
      v-if="!canQuery"
      message="没有“使用问数”权限：需要 IAM-SIMPLE-1 角色包含“使用问数”。直接调用接口同样会被后端拒绝。"
    />

    <template v-else>
      <section class="panel">
        <h3 class="panel-title">第一步：声明查询资源</h3>
        <div class="inline">
          <el-select v-model="datasourceId" placeholder="数据源" class="w200" @change="loadSnapshots">
            <el-option v-for="item in datasources" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <el-select v-model="snapshotId" placeholder="已发布快照" class="w200" @change="loadTables">
            <el-option
              v-for="item in snapshots"
              :key="item.id"
              :label="`版本 ${item.snapshotVersion ?? item.id}`"
              :value="item.id"
            />
          </el-select>
          <el-select v-model="draftTable" placeholder="表" class="w200" @change="loadColumns">
            <el-option v-for="item in tables" :key="item.tableName" :label="item.tableName" :value="item.tableName" />
          </el-select>
          <el-select v-model="draftColumns" multiple filterable placeholder="字段" class="w260">
            <el-option
              v-for="item in columns"
              :key="item.columnMetaId"
              :label="`${item.columnComment || item.columnName}${item.selectable ? '' : '（不可查询）'}`"
              :value="item.columnName"
              :disabled="!item.selectable"
            />
          </el-select>
          <el-button @click="addDeclaration">加入声明</el-button>
          <el-checkbox v-model="allowExtendedUsage">允许这些字段用于排序、分组、聚合和子查询</el-checkbox>
        </div>
        <el-alert
          v-if="!datasources.length"
          type="warning"
          show-icon
          :closable="false"
          title="当前没有任何可用于问数的数据授权"
          description="安全问数只列出你已有“主体命中且当前有效”数据授权的数据源。没有可选项时请先到“访问申请与审批”提交申请，审批通过后再回来查询。"
        />
        <p class="hint">
          字段使用位置默认放开“投影 / 过滤 / 关联”；后端对每个字段强制要求 usage，缺失会直接拒绝提交。
          需要排序、分组、聚合或子查询时请显式勾选上方的扩展位置。
        </p>
        <EmptyState v-if="!declaredTables.length" message="还没有声明任何表和字段。未声明的资源不会进入权限快照。" />
        <el-table v-else :data="declaredTables" size="small" class="declared">
          <el-table-column prop="tableName" label="表" width="180" />
          <el-table-column label="字段" min-width="220">
            <template #default="{ row }">{{ row.referencedColumns.join('、') }}</template>
          </el-table-column>
          <el-table-column label="使用位置" min-width="200">
            <template #default="{ row }">
              {{ row.columnUsages?.[row.referencedColumns[0]]?.join('、') || '投影、过滤、关联' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ $index }">
              <el-button link type="danger" @click="declaredTables.splice($index, 1)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <h3 class="panel-title">第二步：提问</h3>
        <div class="inline">
          <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            placeholder="例如：统计最近 30 天订单金额趋势"
            class="flex1"
          />
          <div class="actions">
            <el-button type="primary" :loading="submitting" @click="ask">提交查询</el-button>
            <el-button :disabled="!submitting" @click="cancel">取消</el-button>
          </div>
        </div>
        <p v-if="progressMessage" class="hint">{{ progressMessage }}</p>
      </section>

      <section v-if="result" class="panel">
        <h3 class="panel-title">
          结果：{{ result.status }}
          <small class="muted">
            协议 {{ result.protocolVersion }}；快照 {{ result.activeMetadataSnapshotId }}；权限修订
            {{ result.permissionRevision }}；最终保护 {{ result.finalProtectionStatus }}
          </small>
        </h3>
        <div class="inline">
          <el-radio-group v-model="resultTab" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="sql">SQL</el-radio-button>
            <el-radio-button value="trust">可信依据</el-radio-button>
          </el-radio-group>
          <el-button
            size="small"
            :disabled="!canViewSql"
            @click="viewSql"
          >查看 SQL</el-button>
          <el-button size="small" :disabled="!canExport" @click="exportCsv">导出 CSV</el-button>
          <el-button size="small" @click="feedback('LIKE')">有帮助</el-button>
          <el-button size="small" @click="feedback('DISLIKE')">没帮助</el-button>
        </div>
        <p v-if="!canViewSql || !canExport" class="hint">
          查看 SQL 与导出结果都是独立功能：当前角色未勾选的能力会保持禁用，能力收紧后服务端也会再次拒绝。
        </p>

        <div v-if="resultTab === 'table'">
          <EmptyState v-if="!resultColumns.length" message="该任务没有可展示的表格数据。" />
          <el-table v-else :data="(result.data as Record<string, unknown>[]) ?? []" size="small" max-height="420">
            <el-table-column v-for="column in resultColumns" :key="column" :prop="column" :label="column" min-width="140" />
          </el-table>
        </div>

        <div v-else-if="resultTab === 'sql'">
          <pre v-if="sqlText" class="sql-block">{{ sqlText }}</pre>
          <EmptyState v-else message="未查看 SQL，或当前角色没有“查看 SQL”能力。" />
        </div>

        <div v-else>
          <ul class="trust-list">
            <li>使用表：{{ (result.usedTables as string[] | undefined)?.join('、') || '未返回' }}</li>
            <li>使用字段：{{ (result.usedColumns as string[] | undefined)?.join('、') || '未返回' }}</li>
            <li>
              脱敏字段：
              {{ result.maskedFields && Object.keys(result.maskedFields as object).length
                ? JSON.stringify(result.maskedFields)
                : '未标记脱敏字段' }}
            </li>
            <li>错误信息：{{ result.errorMessage || '无' }}</li>
          </ul>
        </div>
      </section>

      <section class="panel">
        <h3 class="panel-title">我的历史任务</h3>
        <el-button size="small" :loading="loadingHistory" @click="loadHistory">刷新</el-button>
        <EmptyState v-if="!history.length" message="还没有 IAM-SIMPLE-1 查询历史。" />
        <el-table v-else :data="history" size="small">
          <el-table-column prop="taskId" label="任务" width="220" />
          <el-table-column prop="question" label="问题" min-width="240" />
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column prop="permissionRevision" label="权限修订" width="120" />
        </el-table>
      </section>
    </template>
  </div>
</template>

<style scoped>
.iam-s1-query {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
}
.panel {
  border: 1px solid var(--do-line);
  border-radius: 10px;
  padding: 18px;
  background: var(--do-surface);
}
.panel-title {
  margin: 0 0 10px;
  font-size: 15px;
}
.inline {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.actions {
  display: flex;
  gap: 8px;
}
.declared {
  margin-top: 12px;
}
.sql-block {
  margin: 12px 0 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--do-surface-muted, #f6f7f9);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.trust-list {
  margin: 12px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
}
.hint {
  margin: 10px 0 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}
.muted {
  color: var(--do-muted);
  font-size: 12px;
  margin-left: 8px;
}
.flex1 {
  flex: 1;
}
.w200 {
  width: 200px;
}
.w260 {
  width: 260px;
}
</style>
