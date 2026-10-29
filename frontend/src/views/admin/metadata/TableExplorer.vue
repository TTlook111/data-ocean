<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RefreshCw, Table2, Key } from 'lucide-vue-next'
import {
  getSnapshotDetail,
  listSnapshots,
  type SnapshotItem,
  type TableMetaItem,
  type ColumnMetaItem,
} from '../../../api/admin/metadata'
import { governanceStatusLabel, snapshotStatusLabel, snapshotStatusType } from '../../../utils/enumLabels'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAuthStore } from '../../../stores/auth'
import { useAdminContextStore } from '../../../stores/adminContext'
import { getHttpErrorMessage } from '../../../utils/httpError'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'

const props = withDefaults(defineProps<{ fixedHeight?: boolean }>(), { fixedHeight: false })

const route = useRoute()
const router = useRouter()
const adminContext = useAdminContextStore()
const iam = useIamS1Store()
const auth = useAuthStore()
const canRead = computed(() => {
  const raw = route.query.datasourceId
  const id = raw == null ? undefined : typeof raw === 'string' ? Number(raw) : NaN
  const scopeMatches = id === undefined || (Number.isSafeInteger(id) && id! > 0 && id === adminContext.datasourceId)
  return scopeMatches && iam.loaded && !iam.errorMessage && iam.canOnDatasource('metadata:view', adminContext.datasourceId)
})
let disposed = false
const loading = ref(false)
const snapshotLoading = ref(false)
const snapshotError = ref('')
const detailError = ref('')
const snapshots = ref<SnapshotItem[]>([])
const selectedSnapshotId = ref<number>()
const tables = ref<TableMetaItem[]>([])
const columns = ref<ColumnMetaItem[]>([])
const selectedTable = ref<string>('')
const tableKeyword = ref('')

const selectedSnapshot = computed(() => snapshots.value.find((item) => item.id === selectedSnapshotId.value))
const selectedTableMeta = computed(() => tables.value.find((item) => item.tableName === selectedTable.value))
const filteredTables = computed(() => {
  const keyword = tableKeyword.value.trim().toLowerCase()
  if (!keyword) return tables.value
  return tables.value.filter((item) =>
    item.tableName.toLowerCase().includes(keyword) ||
    (item.tableComment || '').toLowerCase().includes(keyword),
  )
})

const filteredColumns = computed(() =>
  columns.value
    .filter((column) => column.tableName === selectedTable.value)
    .sort((a, b) => a.ordinalPosition - b.ordinalPosition)
)

let snapshotRequestId = 0
let detailRequestId = 0

function formatNumber(value?: number | null) {
  if (value == null) return '未采集统计'
  return value.toLocaleString('zh-CN')
}

function formatEstimatedRows(value?: number | null) {
  if (value == null) return '未采集统计'
  return `约 ${formatNumber(value)} 行`
}

function formatNullRate(value?: number | null) {
  if (value == null) return '未采集统计'
  return `${(Number(value) * 100).toFixed(1)}%`
}

function nullableLabel(value: number | boolean) {
  return value ? '允许为空' : '不能为空'
}

function tableTypeLabel(type?: string | null) {
  if (!type) return '未采集'
  return type === 'TABLE' || type === 'BASE TABLE' ? '数据表' : type === 'VIEW' ? '视图' : type === 'SYSTEM VIEW' ? '系统视图' : type
}

function snapshotLabel(snapshot: SnapshotItem) {
  return `v${snapshot.snapshotVersion}`
}

function governanceTone(status?: string | null) {
  if (status === 'BLOCKED' || status === 'DEPRECATED') return 'danger'
  if (status === 'SENSITIVE') return 'warning'
  return 'normal'
}

function clearDetail() { tables.value = []; columns.value = []; selectedTable.value = ''; detailError.value = ''; loading.value = false; detailRequestId++ }
function invalidate() { snapshotRequestId++; clearDetail(); snapshots.value = []; selectedSnapshotId.value = undefined; snapshotError.value = ''; snapshotLoading.value = false }
async function fetchSnapshots() {
  const request = ++snapshotRequestId, datasourceId = adminContext.datasourceId, token = auth.token
  clearDetail(); snapshots.value = []; selectedSnapshotId.value = undefined; snapshotError.value = ''
  if (!datasourceId || !canRead.value) { snapshotLoading.value = false; return false }
  const current = () => !disposed && request === snapshotRequestId && datasourceId === adminContext.datasourceId && token === auth.token && canRead.value
  snapshotLoading.value = true
  try {
    const res = await listSnapshots({ datasourceId, page: 1, size: 50 })
    if (!current()) return false
    const records = res.data?.records ?? []
    if (records.some(item => item.datasourceId !== datasourceId)) throw new Error('快照列表来源与当前数据源不一致。')
    snapshots.value = records
    const explicitId = route.query.snapshotId == null ? undefined : Number(route.query.snapshotId)
    if (explicitId !== undefined && (!Number.isSafeInteger(explicitId) || explicitId <= 0)) throw new Error('指定快照标识无效，未切换到其他快照。')
    let selected = snapshots.value.find(item => item.id === (explicitId || adminContext.snapshotId))
    if (explicitId && !selected) {
      selected = await adminContext.ensureSnapshot(explicitId)
      if (!current()) return false
      if (!selected || selected.id !== explicitId || selected.datasourceId !== datasourceId) throw new Error('指定的快照不属于当前数据源，未切换到其他快照。')
      snapshots.value = [...snapshots.value, selected]
    }
    selectedSnapshotId.value = selected?.id ?? snapshots.value[0]?.id
    adminContext.selectSnapshot(selectedSnapshotId.value)
    return true
  } catch (error) {
    if (current()) { snapshots.value = []; selectedSnapshotId.value = undefined; snapshotError.value = `快照列表无法加载：${getHttpErrorMessage(error, '快照不存在、无权访问或加载失败')}。未切换到其他快照。` }
    return false
  } finally { if (current()) snapshotLoading.value = false }
}
async function fetchDetail(snapshotId = selectedSnapshotId.value) {
  clearDetail()
  const request = detailRequestId, datasourceId = adminContext.datasourceId, token = auth.token
  if (!snapshotId || !canRead.value) return
  const current = () => !disposed && request === detailRequestId && snapshotId === selectedSnapshotId.value && datasourceId === adminContext.datasourceId && token === auth.token && canRead.value
  loading.value = true
  try {
    const res = await getSnapshotDetail(snapshotId)
    if (!current()) return
    if (!res.data?.snapshot || res.data.snapshot.id !== snapshotId || res.data.snapshot.datasourceId !== datasourceId) throw new Error('指定的快照不存在或不属于当前数据源。')
    tables.value = res.data.tables ?? []; columns.value = res.data.columns ?? []; selectedTable.value = tables.value[0]?.tableName || ''
  } catch (error) { if (current()) { clearDetail(); detailError.value = getHttpErrorMessage(error, '获取快照结构失败') } }
  finally { if (!disposed && request === detailRequestId) loading.value = false }
}
async function reloadScope() {
  const token = auth.token, source = adminContext.datasourceId
  if (await fetchSnapshots()) {
    if (!disposed && token === auth.token && source === adminContext.datasourceId) await fetchDetail()
  }
}
function handleSnapshotChange() {
  adminContext.selectSnapshot(selectedSnapshotId.value)
  router.replace({ query: { ...route.query, snapshotId: selectedSnapshotId.value } })
  fetchDetail()
}
onMounted(async () => {
  const token = auth.token
  try { await adminContext.initialize(); if (!disposed && token === auth.token) await reloadScope() }
  catch (error) { if (!disposed && token === auth.token) snapshotError.value = getHttpErrorMessage(error, '数据源范围加载失败') }
})
watch(() => route.query.snapshotId, value => {
  if ((Number(value) || undefined) !== selectedSnapshotId.value) reloadScope()
})
// 本页快照由 URL 与本页选择器驱动；公共初始化偏好只用于无显式版本时的默认值。
// 不把公共 store 在换账号/换源时的默认选择写回 URL，避免覆盖显式历史版本。
watch([() => route.query.datasourceId, () => adminContext.datasourceId, () => auth.token, () => iam.snapshot, () => iam.loaded, () => iam.errorMessage], () => { invalidate(); tableKeyword.value = ''; reloadScope() }, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; invalidate() })
</script>

<template>
  <main class="table-explorer-page post-login-page">
    <section class="snapshot-toolbar">
      <div class="snapshot-toolbar__selectors">
        <slot name="datasource-scope" />
        <div class="snapshot-selector-control">
          <span class="snapshot-control-label">快照</span>
          <el-select
            v-model="selectedSnapshotId"
            class="snapshot-selector"
            placeholder="选择快照"
            filterable
            clearable
            :loading="snapshotLoading"
            aria-label="选择快照结构"
            @change="handleSnapshotChange"
          >
            <el-option v-for="snapshot in snapshots" :key="snapshot.id" :label="snapshotLabel(snapshot)" :value="snapshot.id">
              <span class="snapshot-option-main">v{{ snapshot.snapshotVersion }}</span>
              <el-tag :type="snapshotStatusType(snapshot.status)" size="small">{{ snapshotStatusLabel(snapshot.status) }}</el-tag>
            </el-option>
          </el-select>
        </div>
      </div>

      <el-button class="snapshot-refresh" :icon="RefreshCw" :loading="loading" :disabled="!selectedSnapshotId" aria-label="刷新当前快照表结构" @click="fetchDetail()">
        刷新结构
      </el-button>

      <p class="snapshot-contract">这里展示所选版本的采集结构；历史或未发布快照不代表当前正式对象目录。</p>
      <div v-if="selectedSnapshot" class="snapshot-summary" aria-label="快照概况">
        <el-tag :type="snapshotStatusType(selectedSnapshot.status)" size="small">
          {{ snapshotStatusLabel(selectedSnapshot.status) }}
        </el-tag>
        <el-tooltip content="当前快照采集到的数据表数量">
          <span>表数：{{ selectedSnapshot.tableCount }}</span>
        </el-tooltip>
        <el-tooltip content="当前快照采集到的字段总数">
          <span>字段数：{{ selectedSnapshot.columnCount }}</span>
        </el-tooltip>
        <el-tooltip content="质量分由元数据质量规则计算，满分 100 分">
          <span>质量分：{{ selectedSnapshot.qualityScore == null ? '未校验' : `${selectedSnapshot.qualityScore} 分` }}</span>
        </el-tooltip>
      </div>
    </section>

    <LoadingState v-if="!iam.loaded && !iam.errorMessage" variant="skeleton" :rows="5" />
    <ErrorState v-else-if="!canRead" message="没有当前数据源的元数据查看权限。" @retry="iam.load(true)" />
    <ErrorState v-else-if="snapshotError" class="table-explorer-state" :message="snapshotError" @retry="reloadScope" />
    <LoadingState v-else-if="snapshotLoading" class="table-explorer-state" variant="skeleton" :rows="5" />
    <EmptyState v-else-if="!snapshotLoading && !snapshots.length" class="table-explorer-state" message="当前数据源暂无可查看的快照。" />
    <EmptyState v-else-if="!selectedSnapshotId" class="table-explorer-state" message="请选择快照后查看表和字段结构。" />
    <ErrorState v-else-if="detailError" class="table-explorer-state" :message="detailError" @retry="fetchDetail()" />
    <LoadingState v-else-if="loading && !tables.length" class="table-explorer-state" variant="skeleton" :rows="6" />

    <div v-else class="explorer-layout" :class="{ 'explorer-layout--fixed': props.fixedHeight }" v-loading="loading">
      <aside class="table-list" aria-label="快照数据表">
        <div class="table-list-header">
          <strong>数据表 · {{ tables.length }}</strong>
          <el-input v-model="tableKeyword" size="small" clearable aria-label="搜索快照数据表" placeholder="搜索表名或说明" />
        </div>
        <button
          class="table-item"
          type="button"
          v-for="t in filteredTables"
          :key="t.id"
          :class="{ active: t.tableName === selectedTable }"
          :aria-pressed="t.tableName === selectedTable"
          @click="selectedTable = t.tableName"
        >
          <Table2 :size="14" aria-hidden="true" />
          <div class="table-main">
            <span class="table-name" :title="t.tableName">{{ t.tableName }}</span>
            <span class="table-comment" :title="t.tableComment || '暂无表说明'">{{ t.tableComment || '暂无表说明' }}</span>
          </div>
          <el-tooltip content="估算行数：采集快照时从 MySQL 元数据读取的估算行数，不是精确 COUNT(*)" placement="top">
            <span class="row-count">{{ formatEstimatedRows(t.rowCountEstimate) }}</span>
          </el-tooltip>
        </button>
        <el-empty
          v-if="!filteredTables.length"
          :description="tables.length ? '没有匹配的数据表' : '当前快照暂无表数据'"
          :image-size="60"
        />
      </aside>

      <section class="column-detail" aria-label="所选表的字段结构">
        <div class="detail-header" v-if="selectedTable">
          <div>
            <h3>{{ selectedTable }}</h3>
            <span class="table-description" tabindex="0" :title="selectedTableMeta?.tableComment || '暂无表说明'">
              {{ selectedTableMeta?.tableComment || '暂无表说明' }}
            </span>
          </div>
          <span class="col-count">{{ filteredColumns.length }} 个字段</span>
        </div>
        <div class="table-facts" v-if="selectedTableMeta">
          <div class="fact-item">
            <span>表类型</span>
            <strong>{{ tableTypeLabel(selectedTableMeta.tableType) }}</strong>
          </div>
          <el-tooltip content="估算行数：采集快照时从 MySQL 元数据读取的估算行数，不是精确 COUNT(*)">
            <div class="fact-item">
              <span>估算行数</span>
              <strong>{{ formatEstimatedRows(selectedTableMeta.rowCountEstimate) }}</strong>
            </div>
          </el-tooltip>
          <div class="fact-item">
            <span>治理状态</span>
            <strong :class="`governance-status governance-status--${governanceTone(selectedTableMeta.governanceStatus)}`">
              {{ governanceStatusLabel(selectedTableMeta.governanceStatus) }}
            </strong>
          </div>
          <div class="fact-item">
            <span>存储引擎</span>
            <strong>{{ selectedTableMeta.engine || '未采集' }}</strong>
          </div>
        </div>
        <div class="field-table-scroll">
          <el-table :data="filteredColumns" :height="props.fixedHeight ? '100%' : undefined" size="small" v-if="filteredColumns.length">
            <el-table-column label="序号" width="52" align="center">
              <template #default="{ $index }"><span class="column-index">{{ $index + 1 }}</span></template>
            </el-table-column>
            <el-table-column prop="columnName" label="字段名" min-width="168">
              <template #default="{ row }">
                <span class="column-name-cell">
                  <Key v-if="row.isPrimaryKey" :size="12" aria-label="主键" role="img" />
                  <span class="column-code" :title="row.columnName">{{ row.columnName }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="dataType" label="类型" width="156">
              <template #default="{ row }"><span class="column-code column-code--type" :title="row.dataType">{{ row.dataType }}</span></template>
            </el-table-column>
            <el-table-column prop="isNullable" label="可空性" width="92">
              <template #default="{ row }">{{ nullableLabel(row.isNullable) }}</template>
            </el-table-column>
            <el-table-column prop="columnComment" label="字段说明" min-width="230">
              <template #default="{ row }">
                <span class="column-comment" tabindex="0" :title="row.columnComment || '暂无字段说明'">
                  {{ row.columnComment || '暂无字段说明' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="governanceStatus" label="治理状态" width="98">
              <template #default="{ row }">
                <span :class="`governance-status governance-status--${governanceTone(row.governanceStatus)}`">
                  {{ governanceStatusLabel(row.governanceStatus) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="nullRate" width="104">
              <template #header>
                <el-tooltip content="空值率：采集统计时基于样本数据计算，未采集时显示“未采集统计”">
                  <span>空值率</span>
                </el-tooltip>
              </template>
              <template #default="{ row }">
                <span :class="{ 'stat-uncollected': row.nullRate == null }">{{ formatNullRate(row.nullRate) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else-if="selectedTable" description="当前数据表暂无字段信息" :image-size="60" />
          <el-empty v-else description="选择左侧表查看字段" :image-size="60" />
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.snapshot-contract { grid-area:contract; margin:0; color:var(--do-muted); font-size:12px; line-height:1.6; }
.table-explorer-page { min-width: 0; color: var(--do-ink); }
.snapshot-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas: "selectors refresh" "contract contract" "summary summary";
  align-items: center;
  gap: 8px 12px;
  padding: 14px 20px 10px;
  border-bottom: 1px solid var(--do-line);
}
.snapshot-toolbar__selectors {
  grid-area: selectors;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}
.snapshot-selector-control { display: flex; align-items: center; gap: 8px; flex: 0 1 244px; min-width: 204px; }
.snapshot-control-label { flex: 0 0 auto; color: var(--do-muted); font-size: 12px; white-space: nowrap; }
.snapshot-selector { flex: 1 1 180px; width: 180px; min-width: 160px; max-width: 200px; }
.snapshot-toolbar__selectors :deep(.el-select__wrapper),
.snapshot-refresh { min-height: 36px; }
.snapshot-refresh { grid-area: refresh; }
.snapshot-option-main {
  display: inline-block;
  min-width: 90px;
  color: var(--do-ink);
  font-size: 13px;
  font-weight: 600;
}
.snapshot-summary {
  grid-area: summary;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 18px;
  min-height: 28px;
  color: var(--do-muted);
  font-size: 12.5px;
  line-height: 1.5;
}
.table-explorer-state {
  margin: 18px 20px;
}

.explorer-layout {
  display: flex;
  min-width: 0;
  margin: 0 20px 16px;
  background: var(--do-glass-data-bg);
  overflow: hidden;
}
.table-list {
  width: 252px;
  flex: 0 0 252px;
  min-width: 0;
  border-right: 1px solid var(--do-line);
  padding: 12px 10px;
  overflow: visible;
}
.table-list-header {
  display: grid;
  gap: 9px;
  margin: 0 0 10px;
  padding: 0 2px 12px;
  border-bottom: 1px solid var(--do-line);
}
.table-list-header :deep(.el-input__wrapper) { min-height: 36px; }
.table-list-header :deep(.el-input__inner) { font-size: 13px; }
.table-list-header strong {
  color: var(--do-ink);
  font-size: 13px;
  font-weight: 650;
}
.table-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 54px;
  margin: 1px 0;
  padding: 7px 8px;
  border: 0;
  border-radius: var(--do-radius-sm);
  color: var(--do-ink);
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
}
.table-item:hover { background: var(--do-primary-soft); }
.table-item:focus-visible { outline: 2px solid color-mix(in srgb, var(--do-primary) 52%, transparent); outline-offset: 1px; }
.table-item.active { background: var(--do-primary-soft); }
.table-item > :deep(svg) { flex: 0 0 auto; color: var(--do-muted); }
.table-item.active > :deep(svg) { color: var(--do-primary-strong); }
.table-main {
  display: grid;
  min-width: 0;
  flex: 1 1 auto;
  gap: 3px;
}
.table-name {
  color: var(--do-ink);
  overflow: hidden;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-item.active .table-name { color: var(--do-primary-strong); }
.table-comment {
  overflow: hidden;
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-count {
  flex: 0 0 auto;
  color: var(--do-muted);
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
}
.column-detail { flex: 1 1 auto; min-width: 0; padding: 16px 18px; overflow: hidden; }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.detail-header h3 {
  margin: 0;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 16px;
  font-weight: 650;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
.detail-header > div { min-width: 0; }
.table-description {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 3px;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.45;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.table-description:focus-visible { border-radius: 3px; outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 2px; }
.col-count { flex: 0 0 auto; color: var(--do-muted); font-size: 12px; white-space: nowrap; }
.table-facts {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin: 0 0 12px;
  padding: 0 0 11px;
  border-bottom: 1px solid var(--do-line);
}
.fact-item {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.fact-item span {
  color: var(--do-muted);
  font-size: 12px;
}
.fact-item strong {
  overflow: hidden;
  color: var(--do-ink);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.governance-status { font-size: 13px; font-weight: 600; }
.governance-status--normal { color: var(--do-muted); font-weight: 500; }
.governance-status--warning { color: var(--do-warning); }
.governance-status--danger { color: var(--do-danger); }
.fact-item strong.governance-status--normal { color: var(--do-muted); font-weight: 500; }
.fact-item strong.governance-status--warning { color: var(--do-warning); }
.fact-item strong.governance-status--danger { color: var(--do-danger); }
.field-table-scroll { width: 100%; max-width: 100%; min-width: 0; overflow-x: auto; }
.column-detail :deep(.el-table) {
  border: 0;
  border-radius: 0;
  color: var(--do-ink);
  background: var(--do-glass-data-bg);
}
.column-detail :deep(.el-table::before),
.column-detail :deep(.el-table__inner-wrapper::before) { display: none; }
.column-detail :deep(.el-table th.el-table__cell) {
  height: 42px;
  padding: 0;
  color: var(--do-muted);
  background: var(--do-soft);
  font-size: 12px;
  font-weight: 600;
}
.column-detail :deep(.el-table th.el-table__cell .cell) { padding: 0 12px; line-height: 42px; }
.column-detail :deep(.el-table td.el-table__cell) {
  padding: 11px 0;
  border-bottom-color: var(--do-line);
  font-size: 14px;
  vertical-align: middle;
}
.column-detail :deep(.el-table td.el-table__cell .cell) { padding: 0 12px; line-height: 1.45; }
.column-detail :deep(.el-table tr:last-child td.el-table__cell) { border-bottom: 0; }
.column-detail :deep(.el-table th:first-child .cell),
.column-detail :deep(.el-table td:first-child .cell) { padding-left: 16px; }
.column-name-cell :deep(svg) { flex: 0 0 auto; color: var(--do-accent); }
.column-code { color: var(--do-ink); font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; font-size: 13.5px; font-weight: 500; }
.column-code--type { font-size: 13px; }
.column-index { color: var(--do-muted); font-size: 12.5px; font-weight: 400; }
.column-comment {
  display: block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  color: var(--do-ink);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.column-comment:focus-visible { border-radius: 3px; outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 2px; }
.stat-uncollected { color: var(--do-muted); font-size: 12px; }
.explorer-layout--fixed { flex: 1 1 auto; min-height: 0; }
.explorer-layout--fixed .table-list { overflow-y: auto; }
.explorer-layout--fixed .table-list-header { position: sticky; top: -12px; z-index: 1; padding-top: 12px; background: var(--do-glass-data-bg); }
.explorer-layout--fixed .column-detail { display: flex; flex-direction: column; min-height: 0; }
.explorer-layout--fixed .field-table-scroll { flex: 1 1 auto; min-height: 0; overflow: auto; }
.column-name-cell {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
}
@media (max-width: 960px) {
  .snapshot-toolbar { grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: "selectors refresh" "contract contract" "summary summary"; padding-inline: 14px; }
  .snapshot-toolbar__selectors { gap: 8px; }
  .snapshot-selector-control { flex: 1 1 244px; min-width: 204px; }
  .snapshot-selector { flex: 1 1 180px; min-width: 160px; max-width: 200px; }
  .explorer-layout { flex-direction: column; }
  .table-list { width: auto; flex: 0 0 auto; border-right: 0; border-bottom: 1px solid var(--do-line); }
  .table-list-header { position: static; }
  .column-detail { padding: 14px; }
}
@media (max-width: 640px) {
  .snapshot-toolbar { grid-template-columns: minmax(0, 1fr); grid-template-areas: "selectors" "refresh" "contract" "summary"; }
  .snapshot-toolbar__selectors { align-items: stretch; }
  .snapshot-selector-control { flex: 1 1 100%; min-width: 0; }
  .snapshot-selector { flex: 1 1 180px; min-width: 160px; max-width: 200px; }
  .snapshot-refresh { justify-self: end; }
  .snapshot-summary { gap: 6px 12px; }
  .table-explorer-state { margin-inline: 12px; }
  .explorer-layout { margin-inline: 12px; }
  .detail-header { align-items: flex-start; }
  .table-facts { gap: 7px 12px; }
}
</style>
