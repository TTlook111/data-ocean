<script setup lang="ts">
import { computed, nextTick, onMounted, onScopeDispose, ref, watch } from 'vue'
import { iamS1GetTask, iamS1ViewSql, type IamS1QueryTaskResult } from '../../api/iamS1'
import { useQueryExport } from '../../composables/useQueryExport'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { getHttpErrorMessage } from '../../utils/httpError'
import { resolveQueryDisplayState } from '../../utils/queryDisplayState'
import { buildQueryChartOption, getDefaultQueryChartType } from '../../utils/queryChart'
import QueryResult from './QueryResult.vue'

const props = defineProps<{ taskId: string; datasourceId: number; originalQuestion: string }>()
const emit = defineEmits<{ suggestion: [question: string]; 'layout-ready': [] }>()
const auth = useAuthStore()
const iamS1 = useIamS1Store()
const root = ref<HTMLElement>()
const visible = ref(typeof IntersectionObserver === 'undefined')
const result = ref<IamS1QueryTaskResult | null>(null)
const loading = ref(false)
const error = ref('')
const tab = ref<'chart' | 'table'>('chart')
const detailsTab = ref<'chart' | 'table' | 'sql' | 'trust'>('chart')
const chartType = ref<'bar' | 'line' | 'pie'>('bar')
const dialogOpen = ref(false)
const sqlLoading = ref(false)
const sqlError = ref('')
let readGeneration = 0
let sqlGeneration = 0
let alive = true
let observer: IntersectionObserver | undefined

const canViewSql = computed(() => result.value?.status === 'COMPLETED'
  && result.value.canViewSql === true && iamS1.viewSql)
const canExport = computed(() => result.value?.status === 'COMPLETED'
  && result.value.canExport === true && iamS1.exportResult)
const exporter = useQueryExport({ latestResult: result, canExport })
const viewState = computed(() => resolveQueryDisplayState({ result: result.value, selectionLoading: loading.value, selectionError: error.value }))
const chartOption = computed(() => {
  const style = getComputedStyle(root.value || document.documentElement)
  return buildQueryChartOption(result.value?.chartConfig, chartType.value, {
    colors: ['--do-primary', '--do-success', '--do-ink', '--do-warning', '--do-info'].map(name => style.getPropertyValue(name).trim()).filter(Boolean),
    textColor: style.getPropertyValue('--do-ink').trim(), mutedColor: style.getPropertyValue('--do-muted').trim(),
    borderColor: style.getPropertyValue('--do-line').trim(), fontFamily: style.fontFamily,
  })
})
const trustSummary = computed(() => [
  { label: '本次使用的表', value: result.value?.usedTables?.join(', ') || '服务端未提供表级证据', muted: !result.value?.usedTables?.length },
  { label: '本次使用的字段', value: result.value?.usedColumns?.join(', ') || '服务端未提供字段级证据', muted: !result.value?.usedColumns?.length },
  { label: '元数据快照', value: String(result.value?.activeMetadataSnapshotId ?? '未提供'), muted: !result.value?.activeMetadataSnapshotId },
  { label: '检索构建', value: result.value?.ragBuildId ? `${result.value.ragBuildId} / 来源快照 ${result.value.ragSourceSnapshotId ?? '未提供'}` : '服务端未提供构建标识', muted: !result.value?.ragBuildId },
  { label: '受保护执行标识', value: typeof result.value?.sourceTrace?.attemptId === 'string' ? result.value.sourceTrace.attemptId : '未提供', muted: !result.value?.sourceTrace?.attemptId },
  { label: '同次 SQL 哈希', value: typeof result.value?.sourceTrace?.sqlHash === 'string' ? result.value.sourceTrace.sqlHash : '未提供', muted: !result.value?.sourceTrace?.sqlHash },
  { label: '查询来源记录', value: '尚未确认后台来源记录是否登记完成；完整查询来源请由有权人员在后台核对。', muted: true },
  { label: '追溯投影', value: '当前结果接口未提供投影完成状态。执行成功不等于投影完成；后台查询来源只显示已投影记录。', muted: true },
  { label: '来源说明', value: '这里展示本次查询的使用来源。业务关系与加工血缘由后台独立维护；执行成功仍需核对业务口径。', muted: true },
])
const resultProps = computed(() => ({
  latestResult: result.value, displayTaskId: props.taskId, viewState: viewState.value,
  chartType: chartType.value, chartOption: chartOption.value,
  pagedTableData: exporter.pagedTableData.value, tablePage: exporter.tablePage.value, tablePageSize: exporter.tablePageSize,
  agentProgress: [], trustSummary: trustSummary.value, datasourceFacts: [],
  canViewSql: canViewSql.value, canExport: canExport.value, sqlLoading: sqlLoading.value, sqlErrorMessage: sqlError.value,
  originalQuestion: props.originalQuestion, feedbackStatus: exporter.feedbackStatus.value,
  exportBusy: exporter.exportSubmitting.value,
  feedbackBusy: exporter.feedbackSubmitting.value, feedbackLoading: exporter.feedbackLoading.value,
}))

async function readResult() {
  const ticket = ++readGeneration
  sqlGeneration++
  result.value = null
  error.value = ''
  sqlError.value = ''
  sqlLoading.value = false
  loading.value = true
  dialogOpen.value = false
  try {
    const response = await iamS1GetTask(props.taskId)
    if (!alive || ticket !== readGeneration) return
    if (response.data.taskId !== props.taskId) throw new Error('返回结果与这条回答不一致')
    // Never hydrate a card from stored metadata after a failed current authorization check.
    result.value = { ...response.data, sql: undefined }
    chartType.value = getDefaultQueryChartType(response.data.chartConfig)
    tab.value = response.data.chartConfig ? 'chart' : 'table'
    exporter.tablePage.value = 1
  } catch (cause) {
    if (alive && ticket === readGeneration) error.value = getHttpErrorMessage(cause, '这条回答暂时无法读取，请重试')
  } finally {
    if (alive && ticket === readGeneration) {
      loading.value = false
      void nextTick(() => { if (alive) emit('layout-ready') })
    }
  }
}

async function readSql() {
  if (!canViewSql.value || !dialogOpen.value) return
  const ticket = ++sqlGeneration
  const readTicket = readGeneration
  sqlLoading.value = true
  sqlError.value = ''
  if (result.value) result.value = { ...result.value, sql: undefined }
  try {
    const response = await iamS1ViewSql(props.taskId)
    if (!alive || !dialogOpen.value || ticket !== sqlGeneration || readTicket !== readGeneration || !canViewSql.value) return
    if (result.value) result.value = { ...result.value, sql: response.data.sql }
  } catch (cause) {
    if (alive && ticket === sqlGeneration && readTicket === readGeneration) sqlError.value = getHttpErrorMessage(cause, 'SQL 无法读取，当前权限或结果可能已变化')
  } finally {
    if (alive && ticket === sqlGeneration) sqlLoading.value = false
  }
}

function openDetails(nextTab: 'chart' | 'table' | 'sql' | 'trust') {
  if (nextTab === 'sql' && !canViewSql.value) return
  detailsTab.value = nextTab
  dialogOpen.value = true
  if (nextTab === 'sql') void readSql()
}
function setDetailsTab(nextTab: 'chart' | 'table' | 'sql' | 'trust') {
  if (nextTab === 'sql' && !canViewSql.value) return
  detailsTab.value = nextTab
  if (nextTab === 'sql') void readSql()
}
function closeDetails() {
  sqlGeneration++
  sqlLoading.value = false
  dialogOpen.value = false
  if (result.value) result.value = { ...result.value, sql: undefined }
}

watch([() => props.taskId, () => props.datasourceId, () => auth.token, () => iamS1.snapshot, visible], () => {
  readGeneration++
  sqlGeneration++
  result.value = null
  dialogOpen.value = false
  if (visible.value) void readResult()
}, { immediate: true })
onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) {
      visible.value = true
      observer?.disconnect()
    }
  }, { rootMargin: '120px' })
  if (root.value) observer.observe(root.value)
})
onScopeDispose(() => { alive = false; readGeneration++; sqlGeneration++; observer?.disconnect() })
</script>

<template>
  <div ref="root" class="query-answer" :data-task-id="taskId">
    <p v-if="result?.status === 'COMPLETED'" class="answer-summary">{{ result.sqlExplanation || '查询已完成，请结合结果核对业务口径。' }}</p>
    <div v-if="!visible || loading" class="answer-read-state" role="status">正在按当前权限读取这条回答…</div>
    <div v-else-if="error" class="answer-read-state answer-error" role="alert">
      <span>{{ error }}</span><button type="button" @click="readResult">重新读取</button>
    </div>
    <QueryResult v-else v-bind="resultProps" embedded :result-tab="tab"
      @update:result-tab="tab = $event === 'table' ? 'table' : 'chart'" @switch-chart-type="chartType = $event"
      @update:table-page="exporter.tablePage.value = $event" @export-csv="exporter.exportCsv"
      @feedback="exporter.handleFeedback" @refresh-feedback="exporter.refreshFeedback" @expand="openDetails" />
    <div v-if="result?.status === 'COMPLETED' && result.suggestedQuestions?.length" class="answer-suggestions" aria-label="推荐追问">
      <small>继续了解</small><button v-for="question in result.suggestedQuestions" :key="question" type="button" @click="emit('suggestion', question)">{{ question }}</button>
    </div>
    <el-dialog v-model="dialogOpen" title="这条回答的结果与依据" width="1040px" class="query-evidence-dialog" destroy-on-close append-to-body @close="closeDetails">
      <QueryResult v-if="dialogOpen" v-bind="resultProps" :result-tab="detailsTab"
        @update:result-tab="setDetailsTab" @switch-chart-type="chartType = $event"
        @update:table-page="exporter.tablePage.value = $event" @export-csv="exporter.exportCsv"
        @feedback="exporter.handleFeedback" @refresh-feedback="exporter.refreshFeedback"
        @retry-sql="readSql" @close="closeDetails" @reload-result="readResult" />
    </el-dialog>
  </div>
</template>

<style scoped>
.query-answer { min-width: 0; margin-top: 16px; }
.answer-summary { margin: 0 0 16px; font-size: 15px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; }
.answer-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 14px; }
.answer-suggestions small { color: var(--do-muted); }
.answer-suggestions button { min-height: 40px; padding: 0 12px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.answer-read-state { min-height: 90px; display: flex; align-items: center; gap: 12px; padding: 16px; border: 1px solid var(--do-line); border-radius: 12px; color: var(--do-muted); background: var(--do-surface); font-size: 13px; line-height: 1.6; }
.answer-error { flex-wrap: wrap; color: var(--do-danger); }
.answer-read-state button { min-height: 40px; padding: 0 12px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-ink); background: var(--do-surface); cursor: pointer; }
</style>
