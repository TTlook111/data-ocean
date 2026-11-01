<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, ChevronDown, Info, Table2 } from 'lucide-vue-next'
import {
  analyzeImpact,
  queryColumnLineage,
  queryTableLineage,
  type ImpactAnalysisVO,
  type LineageColumnVO,
  type LineageTableVO,
} from '../../../api/admin/audit'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { positiveId, queryEvidenceKind } from '../../../utils/lineageContext'
import { getHttpErrorMessage } from '../../../utils/httpError'
import ErrorState from '../../../components/common/ErrorState.vue'
const props = defineProps<{ datasourceId?: number | null; table?: string; column?: string }>()
const auth = useAuthStore(),
  iam = useIamS1Store()
const tableInput = ref(props.table || ''),
  columnInput = ref(props.column || '')
const target = ref<{ source: number; table: string; column?: string } | null>(null)
const impact = ref<ImpactAnalysisVO | null>(null),
  rows = ref<Array<LineageTableVO | LineageColumnVO>>([])
const impactError = ref(''),
  rowsError = ref(''),
  loading = ref(false)
/** 明细接口一次最多返回 100 行且没有服务端分页参数，所以在已取回的完整明细上做前端分页 + 表体滚动。 */
const page = ref(1),
  pageSize = ref(10),
  tableMaxHeight = ref(360)
const totalPages = computed(() => Math.max(1, Math.ceil(rows.value.length / pageSize.value)))
const pagedRows = computed(() =>
  rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
)
function syncTableHeight() {
  tableMaxHeight.value = Math.max(340, Math.min(560, window.innerHeight - 500))
}
let generation = 0,
  alive = true
const allowed = computed(
  () =>
    iam.loaded &&
    !iam.errorMessage &&
    !!positiveId(props.datasourceId) &&
    iam.canOnDatasource('lineage:view', props.datasourceId!),
)
function formatTime(value?: string) {
  return value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '—'
}
function formatCoverage(value?: string) {
  return value ? value.replace('T', ' ').replace(/\.\d+$/, '') : '未提供'
}
function kindClass(row: LineageTableVO | LineageColumnVO) {
  if (!('sourceColumn' in row) || row.sourceColumn === undefined) return 'kind-chip--table'
  return row.aliasName ? 'kind-chip--derived' : 'kind-chip--column'
}
function resetPage() {
  page.value = 1
}
function clear() {
  generation++
  impact.value = null
  rows.value = []
  impactError.value = ''
  rowsError.value = ''
  loading.value = false
  target.value = null
  resetPage()
}
async function load() {
  if (!allowed.value || loading.value || !tableInput.value.trim()) return
  const captured = {
    source: props.datasourceId!,
    table: tableInput.value.trim(),
    column: columnInput.value.trim() || undefined,
  }
  const token = auth.token,
    snapshot = iam.snapshot,
    ticket = ++generation
  target.value = captured
  loading.value = true
  impact.value = null
  rows.value = []
  impactError.value = ''
  rowsError.value = ''
  resetPage()
  const current = () =>
    alive &&
    ticket === generation &&
    token === auth.token &&
    snapshot === iam.snapshot &&
    allowed.value &&
    captured.source === props.datasourceId
  // Independent read failures remain visible; a failed summary does not erase successful detail evidence.
  await Promise.all([
    analyzeImpact(captured.source, captured.table, captured.column)
      .then((r) => {
        if (current()) impact.value = r.data
      })
      .catch((e) => {
        if (current()) impactError.value = getHttpErrorMessage(e, '影响范围读取失败')
      }),
    (captured.column
      ? queryColumnLineage(captured.source, captured.table, captured.column)
      : queryTableLineage(captured.source, captured.table)
    )
      .then((r) => {
        if (current()) rows.value = r.data || []
      })
      .catch((e) => {
        if (current()) rowsError.value = getHttpErrorMessage(e, '查询来源明细读取失败')
      }),
  ])
  if (current()) loading.value = false
}
watch(
  [
    () => props.datasourceId,
    () => props.table,
    () => props.column,
    () => auth.token,
    () => iam.snapshot,
    allowed,
  ],
  () => {
    clear()
    tableInput.value = props.table || ''
    columnInput.value = props.column || ''
    if (props.table && allowed.value) void load()
  },
  { immediate: true },
)
// 换一批明细或改每页条数时回到第一页；总页数变少时把当前页收回范围内。
watch([rows, pageSize], resetPage)
watch(totalPages, (total) => {
  if (page.value > total) page.value = total
})
onMounted(() => {
  syncTableHeight()
  window.addEventListener('resize', syncTableHeight)
})
onBeforeUnmount(() => {
  alive = false
  window.removeEventListener('resize', syncTableHeight)
  clear()
})
defineExpose({ load })
</script>
<template>
  <section class="query-lineage-evidence">
    <div class="evidence-intro">
      <span class="eyebrow">实际查询证据</span>
      <h2>谁使用了这个对象</h2>
      <p>
        这里只统计已完成追溯投影的成功受保护查询。表的查询使用与结果字段派生独立于关系图；不会从同时出现的表推断
        JOIN 或加工链路。
      </p>
    </div>
    <p v-if="!allowed" role="status">没有当前数据源的 lineage:view 能力，无法读取查询来源。</p>
    <div v-else class="evidence-body">
      <form class="evidence-form" @submit.prevent="load">
        <label>
          <span class="field-label">精确表名</span>
          <el-input v-model="tableInput" placeholder="例如 orders" :disabled="loading" />
        </label>
        <label>
          <span class="field-label">字段名（可选）</span>
          <el-input v-model="columnInput" placeholder="留空查看表级使用" :disabled="loading" />
        </label>
        <el-button
          class="evidence-submit"
          native-type="submit"
          type="primary"
          :loading="loading"
          :disabled="!tableInput.trim()"
        >
          读取查询证据
        </el-button>
      </form>
      <p class="evidence-note">数据源 #{{ datasourceId }} · 历史快照合并统计 · 接口不支持时间或快照筛选</p>
      <div v-if="loading" role="status" class="evidence-note evidence-loading">
        正在读取当前对象的影响范围与明细…
      </div>
      <section v-if="target" class="target-evidence">
        <div class="target-head">
          <span class="target-caption">
            <span class="target-label">当前证据</span>
            <code>#{{ target.source }} / {{ target.table }}{{ target.column ? '.' + target.column : '' }}</code>
          </span>
          <span v-if="impact" class="target-count-pill">
            去重查询 {{ impact.dependentQueryCount }} · 明细 {{ rows.length }} 条
          </span>
        </div>
        <ErrorState v-if="impactError" :message="impactError" @retry="load" />
        <div v-if="impact" class="evidence-metrics">
          <div class="metric-card">
            <strong>{{ impact.dependentQueryCount }}</strong>
            <span>去重依赖查询数</span>
          </div>
          <div class="metric-card">
            <strong>{{ impact.pendingDatasourceQueryCount ?? '未提供' }}</strong>
            <span>整个数据源待投影查询</span>
          </div>
          <div class="metric-card metric-card--coverage">
            <strong>{{ formatCoverage(impact.coverageStartAt) }}</strong>
            <span>整个数据源最早已投影记录</span>
          </div>
        </div>
        <div v-if="impact" class="coverage-strip" role="status">
          <Info :size="14" class="coverage-strip__icon" />
          <p>
            {{ impact.coverageNote || '只覆盖已记录的受保护查询；未记录不代表没有影响。' }}
            执行成功不等于追溯投影完成；此处没有重跑 SQL 或修复投影的操作。
          </p>
        </div>
        <div v-if="impact?.recentQueryTaskIds?.length" class="recent-tasks">
          <span class="recent-tasks__label">近期任务（最多 10 个，内部数字记录 ID）</span>
          <span class="recent-tasks__chips">
            <code v-for="id in impact.recentQueryTaskIds" :key="id" class="task-chip">#{{ id }}</code>
          </span>
        </div>
        <ErrorState v-if="rowsError" :message="rowsError" @retry="load" />
        <section v-else-if="rows.length" class="evidence-card">
          <header class="evidence-card__head">
            <div class="evidence-card__title">
              <span class="evidence-card__icon"><Table2 :size="15" /></span>
              <strong>查询证据明细</strong>
              <span class="count-pill">共 {{ rows.length }} 条</span>
            </div>
            <span class="evidence-card__hint">最多 100 条 · 总数按任务去重统计</span>
          </header>
          <el-table
            :data="pagedRows"
            :max-height="tableMaxHeight"
            row-key="queryTaskId"
            class="evidence-table"
          >
            <el-table-column label="内部任务 ID" width="118">
              <template #default="{ row }"><code class="row-id">#{{ row.queryTaskId }}</code></template>
            </el-table-column>
            <el-table-column label="查询问题" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="row-question">{{ row.question || '未记录问题文本' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="证据类型" width="128">
              <template #default="{ row }">
                <span class="kind-chip" :class="kindClass(row)">{{ queryEvidenceKind(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源 → 输出" min-width="220">
              <template #default="{ row }">
                <span class="source-cell">
                  <code>{{ row.sourceTable }}{{ row.sourceColumn ? '.' + row.sourceColumn : '' }}</code>
                  <template v-if="row.aliasName">
                    <ArrowRight :size="13" class="source-arrow" />
                    <span class="alias-chip">{{ row.aliasName }}</span>
                  </template>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="记录时间" width="172">
              <template #default="{ row }">
                <code class="time-cell">{{ formatTime(row.createdAt) }}</code>
              </template>
            </el-table-column>
          </el-table>
          <footer class="evidence-card__foot">
            <span class="pager-summary">共 {{ rows.length }} 条 · 每页 {{ pageSize }} 条</span>
            <div class="pager-controls">
              <el-select v-model="pageSize" size="small" class="pager-size">
                <el-option v-for="n in [10, 20, 50]" :key="n" :label="`${n} 条/页`" :value="n" />
              </el-select>
              <el-pagination
                v-model:current-page="page"
                :page-size="pageSize"
                :total="rows.length"
                :pager-count="5"
                size="small"
                background
                layout="prev, pager, next"
              />
            </div>
          </footer>
        </section>
        <p v-else-if="!loading && !rowsError" class="empty-evidence">
          当前对象没有已记录的查询明细。0 条记录不能证明无人使用；列表上限 100 行，总数单独去重统计。
        </p>
        <details v-if="target.column && rows.length" class="expression-details">
          <summary>
            <span>查看字段派生表达式</span>
            <ChevronDown :size="14" class="expression-details__chevron" />
          </summary>
          <p>表达式来自已记录的字段派生，不是受保护 SQL 读取入口。</p>
          <pre v-for="(row, i) in rows" :key="i">{{
            'expression' in row ? row.expression || '服务端未提供表达式' : ''
          }}</pre>
        </details>
      </section>
    </div>
  </section>
</template>
<style scoped>
.query-lineage-evidence {
  min-width: 0;
  padding: 22px 24px 24px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 14px;
  box-shadow:
    0 1px 2px rgba(36, 39, 36, 0.04),
    0 18px 36px -34px rgba(36, 39, 36, 0.35);
}
.eyebrow {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  color: var(--do-primary-strong);
}
h2 {
  margin: 7px 0 8px;
  font-size: 20px;
  line-height: 1.35;
  color: var(--do-ink);
}
.evidence-intro p {
  max-width: 880px;
  margin: 0;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.8;
}
/* ---------- 查询表单 ---------- */
.evidence-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
  margin-top: 18px;
}
.evidence-form label {
  display: grid;
  gap: 6px;
  min-width: 190px;
  font-size: 12px;
}
.field-label {
  font-weight: 600;
  color: var(--do-muted);
}
.evidence-form :deep(.el-input__wrapper) {
  min-height: 34px;
  border-radius: 8px;
}
.evidence-form :deep(.el-input__inner) {
  font-size: 13px;
}
.evidence-submit {
  height: 34px;
  padding: 0 16px;
  border-radius: 8px;
  font-size: 13px;
}
.evidence-note {
  margin: 10px 0 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}
.evidence-loading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding: 7px 12px;
  color: var(--do-muted);
  background: var(--do-soft);
  border: 1px solid var(--do-line);
  border-radius: 999px;
}
/* ---------- 目标与统计 ---------- */
.target-evidence {
  margin-top: 20px;
}
.target-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.target-caption {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 12.5px;
}
.target-label {
  padding: 2px 8px;
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.target-caption code {
  color: var(--do-ink);
  font-size: 12.5px;
  overflow-wrap: anywhere;
}
.target-count-pill {
  padding: 3px 10px;
  color: var(--do-muted);
  background: var(--do-soft);
  border: 1px solid var(--do-line);
  border-radius: 999px;
  font-size: 11.5px;
}
.evidence-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 14px 0;
}
.metric-card {
  display: grid;
  gap: 6px;
  padding: 14px 16px;
  background: color-mix(in srgb, var(--do-soft) 55%, var(--do-surface));
  border: 1px solid var(--do-line);
  border-radius: 12px;
}
.metric-card strong {
  color: var(--do-ink);
  font-size: 24px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}
.metric-card span {
  color: var(--do-muted);
  font-size: 12px;
}
.metric-card--coverage strong {
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 13.5px;
  font-weight: 600;
  padding-top: 7px;
  overflow-wrap: anywhere;
}
.coverage-strip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--do-soft);
  border: 1px solid var(--do-line);
  border-radius: 10px;
}
.coverage-strip__icon {
  flex: none;
  margin-top: 2px;
  color: var(--do-primary-strong);
}
.coverage-strip p {
  margin: 0;
  color: var(--do-muted);
  font-size: 12.5px;
  line-height: 1.7;
}
.recent-tasks {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
}
.recent-tasks__label {
  color: var(--do-muted);
  font-size: 11.5px;
}
.recent-tasks__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.task-chip {
  padding: 2px 8px;
  color: var(--do-muted);
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 999px;
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
}
/* ---------- 明细卡片与表格 ---------- */
.evidence-card {
  margin-top: 14px;
  overflow: hidden;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 12px;
}
.evidence-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 11px 16px;
  background: color-mix(in srgb, var(--do-soft) 45%, var(--do-surface));
  border-bottom: 1px solid var(--do-line);
}
.evidence-card__title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.evidence-card__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
  border-radius: 8px;
}
.evidence-card__title strong {
  color: var(--do-ink);
  font-size: 13.5px;
  font-weight: 650;
}
.count-pill {
  padding: 2px 8px;
  color: var(--do-muted);
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 999px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.evidence-card__hint {
  color: var(--do-muted);
  font-size: 11.5px;
}
.evidence-table {
  --el-table-border-color: var(--do-line);
  --el-table-header-bg-color: var(--do-soft);
  --el-table-row-hover-bg-color: transparent;
  width: 100%;
}
.evidence-table :deep(.el-table__header th.el-table__cell) {
  height: 38px;
  padding: 0;
  color: var(--do-muted);
  background: var(--do-soft);
  border-bottom: 1px solid var(--do-line);
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.evidence-table :deep(.el-table__header th.el-table__cell .cell) {
  padding: 0 14px;
  line-height: 38px;
  white-space: nowrap;
}
.evidence-table :deep(.el-table__body td.el-table__cell) {
  padding: 9px 0;
  border-bottom: 1px solid color-mix(in srgb, var(--do-line) 70%, transparent);
}
.evidence-table :deep(.el-table__body td.el-table__cell .cell) {
  padding: 0 14px;
  font-size: 12.5px;
  line-height: 1.5;
}
.evidence-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background: color-mix(in srgb, var(--do-primary) 5%, var(--do-surface));
}
.evidence-table :deep(.el-table__body tr:hover > td.el-table__cell:first-child) {
  box-shadow: inset 2px 0 0 var(--do-primary);
}
.row-id {
  display: inline-block;
  padding: 2px 7px;
  color: var(--do-muted);
  background: var(--do-soft);
  border-radius: 6px;
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 11.5px;
}
.row-question {
  color: var(--do-ink);
  font-size: 12.5px;
}
.kind-chip {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 650;
  white-space: nowrap;
}
.kind-chip--table {
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}
.kind-chip--column {
  color: var(--do-muted);
  background: var(--do-soft);
}
.kind-chip--derived {
  color: #6f5b8f;
  background: #f1ecf8;
}
.source-cell {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  min-width: 0;
}
.source-cell code {
  color: var(--do-ink);
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.source-arrow {
  flex: none;
  color: var(--do-muted);
}
.alias-chip {
  padding: 1px 7px;
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
  border-radius: 999px;
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
}
.time-cell {
  color: var(--do-muted);
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 11.5px;
  white-space: nowrap;
}
.evidence-card__foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 14px;
  background: color-mix(in srgb, var(--do-soft) 32%, var(--do-surface));
  border-top: 1px solid var(--do-line);
}
.pager-summary {
  color: var(--do-muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}
.pager-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.pager-size {
  width: 98px;
}
.pager-size :deep(.el-select__wrapper) {
  min-height: 26px;
  border-radius: 8px;
  font-size: 12px;
}
.evidence-card__foot :deep(.el-pagination) {
  --el-pagination-font-size: 12px;
  --el-pagination-button-height: 26px;
  --el-pagination-button-width: 26px;
  font-size: 12px;
}
.empty-evidence {
  margin: 14px 0 0;
  padding: 26px 18px;
  color: var(--do-muted);
  background: var(--do-soft);
  border: 1px dashed var(--do-line);
  border-radius: 12px;
  font-size: 12.5px;
  line-height: 1.8;
  text-align: center;
}
/* ---------- 字段派生表达式 ---------- */
.expression-details {
  margin-top: 14px;
  padding: 0 14px 12px;
  background: var(--do-soft);
  border: 1px solid var(--do-line);
  border-radius: 10px;
  font-size: 12.5px;
}
.expression-details summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 11px 0;
  color: var(--do-primary-strong);
  font-size: 12.5px;
  font-weight: 650;
  cursor: pointer;
  list-style: none;
}
.expression-details summary::-webkit-details-marker,
.expression-details summary::marker {
  display: none;
  content: '';
}
.expression-details__chevron {
  flex: none;
  transition: transform 0.15s ease;
}
.expression-details[open] .expression-details__chevron {
  transform: rotate(180deg);
}
.expression-details p {
  margin: 0 0 8px;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.7;
}
.expression-details pre {
  margin: 0 0 6px;
  padding: 8px 10px;
  color: var(--do-ink);
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 8px;
  font-size: 11.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 860px) {
  .evidence-metrics {
    grid-template-columns: 1fr;
  }
  .evidence-card__head {
    flex-direction: column;
    align-items: flex-start;
  }
}
@media (max-width: 700px) {
  .query-lineage-evidence {
    padding: 16px;
  }
  .evidence-form label {
    width: 100%;
  }
  .evidence-submit {
    width: 100%;
  }
}
</style>
