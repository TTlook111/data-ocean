<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeft, BarChart3, CircleAlert, CircleHelp, CircleX, Code2, Download, ListChecks, LoaderCircle, ShieldAlert, ShieldCheck, ThumbsDown, ThumbsUp, X } from 'lucide-vue-next'
import type { IamS1QueryTaskResult as QueryTaskResult } from '../../api/iamS1'
import { finalProtectionLabel, type QueryDisplayState } from '../../utils/queryDisplayState'
import { getPieChartNote, getQueryChartTitle, getQueryChartTypes, type QueryChartType } from '../../utils/queryChart'
import ChartContainer from '../../components/chart/ChartContainer.vue'
import QueryProgress from './QueryProgress.vue'
import QueryResultTable from './QueryResultTable.vue'

const props = defineProps<{
  latestResult: QueryTaskResult | null
  displayTaskId?: string
  viewState: QueryDisplayState
  resultTab: 'table' | 'sql' | 'chart' | 'trust'
  chartType: 'bar' | 'line' | 'pie'
  chartOption: Record<string, unknown> | null
  pagedTableData: Record<string, unknown>[]
  tablePage: number
  tablePageSize: number
  agentProgress: Array<{ key: string; label: string; status: 'done' | 'active' | 'pending' }>
  trustSummary: Array<{ label: string; value: string; muted: boolean }>
  datasourceFacts: Array<{ label: string; value: string }>
  canViewSql: boolean
  canExport: boolean
  sqlLoading: boolean
  sqlErrorMessage: string
  originalQuestion: string
}>()

const emit = defineEmits<{
  'update:resultTab': [tab: 'table' | 'sql' | 'chart' | 'trust']
  'switch-chart-type': [type: 'bar' | 'line' | 'pie']
  'export-csv': []
  'feedback': [type: 'LIKE' | 'DISLIKE']
  'update:tablePage': [page: number]
  'retry': [question: string]
  'clarify': [question: string]
  'resume': [taskId: string]
  'reload-result': [taskId: string]
  'retry-sql': []
  'close': []
}>()

const chartFailed = ref(false)
const detailsOpen = ref(false)
const detailsTab = ref<'trust' | 'sql'>('trust')
const lastResultTab = ref<'table' | 'chart'>('chart')
const hasRows = computed(() => props.viewState.kind === 'success-data')
const showStatusPanel = computed(() => !detailsOpen.value && !['success-data', 'success-empty'].includes(props.viewState.kind))
const chartTitle = computed(() => getQueryChartTitle(props.latestResult))
const availableChartTypes = computed(() => getQueryChartTypes(props.latestResult?.chartConfig))
const pieChartNote = computed(() => getPieChartNote(props.latestResult?.chartConfig))
const chartTypeChoices: Array<{ type: QueryChartType; label: string }> = [
  { type: 'bar', label: '柱状图' }, { type: 'line', label: '折线图' }, { type: 'pie', label: '饼图' },
]
const protectionLabel = computed(() => finalProtectionLabel(props.latestResult?.finalProtectionStatus))

watch(() => props.latestResult?.taskId, () => {
  chartFailed.value = false
  detailsOpen.value = false
  detailsTab.value = 'trust'
})
watch(() => props.resultTab, (tab) => {
  if (tab === 'chart' || tab === 'table') {
    lastResultTab.value = tab
    detailsOpen.value = false
  } else {
    detailsOpen.value = true
    detailsTab.value = tab
  }
})

function openDetails() {
  if (props.resultTab === 'chart' || props.resultTab === 'table') lastResultTab.value = props.resultTab
  detailsTab.value = 'trust'
  detailsOpen.value = true
  emit('update:resultTab', 'trust')
}

function returnToResults() {
  detailsOpen.value = false
  emit('update:resultTab', lastResultTab.value)
}

function showResultTab(tab: 'chart' | 'table') {
  lastResultTab.value = tab
  detailsOpen.value = false
  emit('update:resultTab', tab)
}

function showDetailsTab(tab: 'trust' | 'sql') {
  if (tab === 'sql' && !props.canViewSql) return
  detailsTab.value = tab
  detailsOpen.value = true
  emit('update:resultTab', tab)
}

defineExpose({ openDetails })
</script>

<template>
  <aside class="result-preview result-rail" aria-label="查询结果">
    <header class="result-header">
      <div class="result-title">
        <strong>查询结果</strong>
      </div>
      <div class="result-header-actions">
        <button type="button" class="close-button" aria-label="收起查询结果" @click="emit('close')"><X :size="18" aria-hidden="true" /></button>
      </div>
    </header>

    <div class="result-body">
      <section v-if="showStatusPanel" class="result-status" :class="`state-${viewState.kind}`" role="status" aria-live="polite" aria-atomic="true">
        <span class="status-symbol" :class="`symbol-${viewState.kind}`" aria-hidden="true">
          <LoaderCircle v-if="viewState.kind === 'processing' || viewState.kind === 'selection-loading'" :size="22" />
          <CircleHelp v-else-if="viewState.kind === 'clarification' || viewState.kind === 'unknown' || viewState.kind === 'empty'" :size="22" />
          <ShieldAlert v-else-if="viewState.kind === 'failed' || viewState.kind === 'timeout'" :size="22" />
          <CircleX v-else-if="viewState.kind === 'cancelled'" :size="22" />
          <CircleAlert v-else :size="22" />
        </span>
        <strong>{{ viewState.title }}</strong>
        <span>{{ viewState.description }}</span>
        <div class="status-actions">
          <button v-if="viewState.kind === 'selection-error' && displayTaskId" type="button" @click="emit('reload-result', displayTaskId)">重新读取</button>
          <button v-if="viewState.canResume && displayTaskId" type="button" @click="emit('resume', displayTaskId)">恢复等待</button>
          <button v-if="viewState.kind === 'clarification' && originalQuestion" type="button" @click="emit('clarify', originalQuestion)">补充查询条件</button>
          <button v-if="['failed', 'cancelled', 'timeout'].includes(viewState.kind) && originalQuestion" type="button" @click="emit('retry', originalQuestion)">保留原问题重试</button>
        </div>
      </section>

      <section v-else-if="!detailsOpen && viewState.kind === 'success-empty'" class="result-status state-success-empty" role="status" aria-live="polite" aria-atomic="true">
        <CircleHelp :size="22" class="success-icon" aria-hidden="true" />
        <strong>{{ viewState.title }}</strong>
        <span>{{ viewState.description }}</span>
      </section>

      <template v-else-if="!detailsOpen && hasRows && latestResult">
        <div class="result-tabs" role="tablist" aria-label="结果视图">
          <button type="button" role="tab" :aria-selected="resultTab === 'chart'" :class="{ active: resultTab === 'chart' }" @click="showResultTab('chart')"><BarChart3 :size="15" aria-hidden="true" />图表</button>
          <button type="button" role="tab" :aria-selected="resultTab === 'table'" :class="{ active: resultTab === 'table' }" @click="showResultTab('table')"><ListChecks :size="15" aria-hidden="true" />数据</button>
          <span v-if="protectionLabel" class="protection-badge"><ShieldCheck :size="14" aria-hidden="true" />{{ protectionLabel }}</span>
        </div>

        <section v-if="resultTab === 'chart'" class="result-scroll chart-result-view">
          <div class="chart-heading">
            <div class="result-meta">
              <h3>{{ chartTitle }}</h3>
              <span>{{ latestResult.rowCount ?? latestResult.data?.length ?? 0 }} 行<span v-if="latestResult.totalTimeMs"> · {{ (latestResult.totalTimeMs / 1000).toFixed(2) }} 秒</span></span>
            </div>
            <div v-if="latestResult.chartConfig && !chartFailed && availableChartTypes.length > 1" class="chart-type-switcher" role="group" aria-label="图表类型">
              <button v-for="choice in chartTypeChoices" :key="choice.type" type="button" :aria-pressed="chartType === choice.type" :class="{ active: chartType === choice.type }" :disabled="!availableChartTypes.includes(choice.type)" :title="choice.type === 'pie' ? pieChartNote : undefined" @click="emit('switch-chart-type', choice.type)">{{ choice.label }}</button>
            </div>
          </div>
          <p v-if="pieChartNote && availableChartTypes.length > 1" class="chart-note">{{ pieChartNote }}</p>
          <ChartContainer v-if="latestResult.chartConfig && !chartFailed" :option="chartOption" @error="chartFailed = true" />
          <div v-else class="chart-fallback" role="status">
            <strong>{{ chartFailed ? '图表未能显示，已回退到同一查询的受保护数据' : '当前结果没有图表配置，以下显示同一查询的受保护数据' }}</strong>
          </div>
          <div class="data-detail-heading">
            <h3>数据明细</h3>
          </div>
          <QueryResultTable :result="latestResult" :rows="pagedTableData" :page="tablePage" :page-size="tablePageSize" @update:page="emit('update:tablePage', $event)" />
        </section>

        <section v-else class="result-scroll table-result-view">
          <div class="data-detail-heading">
            <div><h3>数据明细</h3><small>{{ latestResult.rowCount ?? latestResult.data?.length ?? 0 }} 行</small></div>
          </div>
          <QueryResultTable :result="latestResult" :rows="pagedTableData" :page="tablePage" :page-size="tablePageSize" @update:page="emit('update:tablePage', $event)" />
        </section>
      </template>

      <section v-if="detailsOpen" class="details-view">
        <div class="details-toolbar">
          <button class="back-button" type="button" @click="returnToResults"><ArrowLeft :size="15" aria-hidden="true" />返回结果</button>
          <div class="details-tabs" role="tablist" aria-label="查询详细信息">
            <button type="button" role="tab" :aria-selected="detailsTab === 'trust'" :class="{ active: detailsTab === 'trust' }" @click="showDetailsTab('trust')"><ShieldCheck :size="14" aria-hidden="true" />可信依据</button>
            <button v-if="canViewSql" type="button" role="tab" :aria-selected="detailsTab === 'sql'" :class="{ active: detailsTab === 'sql' }" @click="showDetailsTab('sql')"><Code2 :size="14" aria-hidden="true" />SQL</button>
          </div>
        </div>

        <div class="details-scroll">
          <template v-if="detailsTab === 'sql'">
            <div v-if="sqlLoading" class="detail-state" role="status">正在读取当前查询的 SQL…</div>
            <div v-else-if="sqlErrorMessage" class="detail-state sql-read-error" role="alert">
              <span>{{ sqlErrorMessage }}</span>
              <button type="button" @click="emit('retry-sql')">重试读取</button>
            </div>
            <pre v-else-if="latestResult?.sql" class="sql-block">{{ latestResult.sql }}</pre>
            <div v-else class="detail-state">当前查询没有可显示的 SQL。</div>
            <p v-if="latestResult?.sqlExplanation" class="sql-explanation">{{ latestResult.sqlExplanation }}</p>
          </template>
          <template v-else>
            <div v-if="latestResult?.question" class="detail-facts"><div><span>原始问题</span><strong>{{ latestResult.question }}</strong></div></div>
            <QueryProgress :agent-progress="agentProgress" :latest-result="latestResult" />
            <div v-if="latestResult" class="detail-facts">
              <div v-if="latestResult.activeMetadataSnapshotId"><span>元数据快照</span><strong>{{ latestResult.activeMetadataSnapshotId }}</strong></div>
              <div v-if="latestResult.permissionRevision"><span>权限版本</span><strong>{{ latestResult.permissionRevision }}</strong></div>
              <div v-if="latestResult.finalProtectionStatus"><span>最终保护</span><strong>{{ latestResult.finalProtectionStatus }}</strong></div>
            </div>
            <div v-if="datasourceFacts.length" class="detail-facts datasource-facts">
              <div v-for="fact in datasourceFacts" :key="fact.label"><span>{{ fact.label }}</span><strong>{{ fact.value }}</strong></div>
            </div>
            <div v-if="trustSummary.length && latestResult?.status === 'COMPLETED'" class="trust-grid">
              <div v-for="item in trustSummary" :key="item.label" class="trust-card" :class="{ muted: item.muted }">
                <span>{{ item.label }}</span><strong>{{ item.value }}</strong>
              </div>
            </div>
            <p v-if="latestResult?.status === 'PROCESSING' && !agentProgress.length" class="detail-state">服务端尚未报告可确认的阶段节点。</p>
          </template>
        </div>
      </section>
    </div>

    <footer v-if="hasRows && latestResult" class="result-actions">
      <span v-if="!canExport" class="permission-hint">当前账号不可导出此结果</span>
      <button v-else type="button" class="export-button" @click="emit('export-csv')"><Download :size="14" aria-hidden="true" />导出 CSV</button>
      <div class="feedback-buttons" aria-label="评价结果">
        <button type="button" class="feedback-button" aria-label="评价：结果准确" title="结果准确" @click="emit('feedback', 'LIKE')"><ThumbsUp :size="15" aria-hidden="true" /></button>
        <button type="button" class="feedback-button" aria-label="评价：结果有误" title="结果有误" @click="emit('feedback', 'DISLIKE')"><ThumbsDown :size="15" aria-hidden="true" /></button>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.result-preview { min-width: 0; min-height: 0; height: 100%; display: grid; grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; border-left: 1px solid var(--do-line); background: var(--do-surface); }
.result-header { min-width: 0; min-height: 64px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 16px; border-bottom: 1px solid var(--do-line); }
.result-title { min-width: 0; }
.result-title strong, .result-title small { display: block; }
.result-title strong { color: var(--do-ink); font-size: 17px; }
.result-title small { max-width: 100%; margin-top: 3px; overflow: hidden; color: var(--do-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.result-header-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 6px; }
.details-button, .close-button, .back-button { min-height: 40px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 9px; border: 1px solid transparent; border-radius: var(--do-radius-sm); color: var(--do-muted); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }
.details-button:hover, .close-button:hover, .back-button:hover { border-color: var(--do-line); color: var(--do-primary-strong); background: var(--do-primary-soft); }
.result-body { min-width: 0; min-height: 0; overflow: hidden; display: grid; grid-template-rows: auto minmax(0, 1fr); }
.result-tabs, .details-toolbar { min-height: 48px; display: flex; align-items: center; gap: 6px; padding: 7px 14px; border-bottom: 1px solid var(--do-line); background: var(--do-surface); }
.result-tabs { grid-row: 1; }
.result-tabs button, .details-tabs button { min-height: 36px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding: 0 13px; border: 1px solid transparent; border-radius: var(--do-radius-sm); color: var(--do-muted); background: transparent; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }
.result-tabs button { min-width: 92px; }
.result-tabs button:hover, .details-tabs button:hover { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.result-tabs button.active, .details-tabs button.active { border-color: color-mix(in srgb, var(--do-primary) 17%, transparent); color: var(--do-primary-strong); background: var(--do-primary-soft); }
.protection-badge { margin-left: auto; display: inline-flex; align-items: center; gap: 5px; color: var(--do-success); font-size: 11px; font-weight: 700; white-space: nowrap; }
.result-scroll, .details-scroll { min-width: 0; min-height: 0; overflow: auto; overscroll-behavior: contain; padding: 14px 16px 18px; }
.chart-result-view, .table-result-view { grid-row: 2; }
.chart-heading { min-width: 0; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 10px; }
.result-meta { min-width: 0; display: grid; gap: 5px; }
.result-meta h3 { margin: 0; color: var(--do-ink); font-size: 16px; font-weight: 700; line-height: 1.5; overflow-wrap: anywhere; }
.result-meta span { color: var(--do-muted); font-size: 12px; }
.chart-type-switcher { display: flex; gap: 2px; padding: 3px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); }
.chart-type-switcher button { min-height: 34px; padding: 0 10px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-muted); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }
.chart-type-switcher button:hover:not(:disabled):not(.active) { color: var(--do-primary-strong); background: var(--do-primary-soft); }
.chart-type-switcher button.active { color: var(--do-surface); background: var(--do-primary-strong); }
.chart-type-switcher button:disabled { opacity: .45; cursor: not-allowed; }
.chart-note { margin: 0 0 8px; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
.chart-result-view :deep(.chart-container) { height: clamp(280px, 36dvh, 380px); min-height: 260px; }
.chart-fallback { padding: 10px 12px; border: 1px solid var(--do-warning); border-radius: var(--do-radius-sm); color: var(--do-warning); background: var(--do-warning-soft); font-size: 12px; line-height: 1.5; }
.data-detail-heading { min-height: 42px; display: flex; align-items: center; justify-content: space-between; gap: 10px; margin: 10px 0 7px; }
.data-detail-heading h3 { margin: 0; color: var(--do-ink); font-size: 15px; }
.data-detail-heading small { color: var(--do-muted); font-size: 11px; }
.export-button { min-height: 38px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 11px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-ink); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.export-button:hover { border-color: var(--do-primary); color: var(--do-primary-strong); background: var(--do-primary-soft); }
.result-status { grid-column: 1; grid-row: 1 / -1; min-height: 100%; display: grid; place-items: center; align-content: center; gap: 9px; padding: 24px; color: var(--do-muted); text-align: center; }
.result-status strong { color: var(--do-ink); font-size: 15px; }
.result-status > span:not(.status-symbol) { max-width: 520px; font-size: 13px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.status-symbol { display: grid; place-items: center; color: var(--do-primary); }
.symbol-processing, .symbol-selection-loading { color: var(--do-primary); }
.symbol-clarification { color: var(--do-warning); }
.symbol-failed, .symbol-timeout, .symbol-cancelled { color: var(--do-danger); }
.state-success-empty .success-icon { color: var(--do-muted); }
.status-actions { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; margin-top: 5px; }
.status-actions button { min-height: 40px; padding: 0 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }
.status-actions button:hover { border-color: var(--do-primary); background: var(--do-primary-soft); }
.details-view { grid-column: 1; grid-row: 1 / -1; min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr); }
.details-toolbar { justify-content: space-between; }
.back-button { min-height: 36px; padding-inline: 7px; }
.details-tabs { display: flex; align-items: center; gap: 4px; }
.details-tabs button { min-height: 36px; padding-inline: 9px; font-size: 12px; }
.details-scroll { display: grid; align-content: start; gap: 12px; }
.sql-block { max-height: 58dvh; overflow: auto; margin: 0; padding: 14px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); color: #dbeafe; background: var(--do-ink); font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.sql-explanation { margin: 0; color: var(--do-muted); font-size: 13px; line-height: 1.65; }
.detail-state { padding: 14px 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-muted); background: var(--do-bg); font-size: 12px; line-height: 1.6; }
.sql-read-error { display: grid; gap: 8px; border-color: color-mix(in srgb, var(--do-danger) 30%, var(--do-line)); color: var(--do-danger); background: var(--do-danger-soft); }
.sql-read-error button { justify-self: start; min-height: 36px; padding: 0 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; cursor: pointer; }
.detail-facts { display: grid; gap: 7px; }
.detail-facts > div { min-height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); }
.detail-facts span { color: var(--do-muted); font-size: 12px; }
.detail-facts strong { overflow-wrap: anywhere; color: var(--do-ink); font-size: 12px; }
.trust-grid { display: grid; grid-template-columns: 1fr; gap: 8px; }
.trust-card { min-height: 66px; display: grid; align-content: start; gap: 6px; padding: 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); background: var(--do-surface); }
.trust-card span { color: var(--do-muted); font-size: 12px; font-weight: 700; }
.trust-card strong { overflow-wrap: anywhere; color: var(--do-ink); font-size: 12px; line-height: 1.55; }
.trust-card.muted strong { color: var(--do-muted); font-weight: 500; }
.result-actions { min-height: 54px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 7px 14px; border-top: 1px solid var(--do-line); background: var(--do-surface); }
.permission-hint { color: var(--do-muted); font-size: 11px; }
.feedback-buttons { display: flex; gap: 6px; }
.feedback-button { width: 40px; height: 40px; display: grid; place-items: center; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-muted); background: var(--do-surface); cursor: pointer; }
.feedback-button:hover { border-color: var(--do-primary); color: var(--do-primary-strong); background: var(--do-primary-soft); }
.result-tabs button:focus-visible, .details-button:focus-visible, .close-button:focus-visible, .back-button:focus-visible, .details-tabs button:focus-visible, .chart-type-switcher button:focus-visible, .export-button:focus-visible, .feedback-button:focus-visible, .status-actions button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 32%, transparent); outline-offset: 2px; }
.symbol-processing svg, .symbol-selection-loading svg { animation: status-spin 900ms linear infinite; }
@media (max-width: 768px) {
  .result-preview { border-left: 0; }
  .result-header { min-height: 56px; padding-inline: 12px; }
  .result-title strong { font-size: 15px; }
  .result-scroll, .details-scroll { padding: 12px; }
  .chart-result-view :deep(.chart-container) { height: clamp(250px, 32dvh, 320px); min-height: 240px; }
  .details-toolbar { padding-inline: 8px; }
  .details-tabs button { gap: 4px; padding-inline: 7px; }
}
@media (prefers-reduced-motion: reduce) { .symbol-processing svg, .symbol-selection-loading svg { animation-duration: 2s; } }
</style>
