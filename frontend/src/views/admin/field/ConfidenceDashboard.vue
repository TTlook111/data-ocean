<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { TrendingUp, Settings } from 'lucide-vue-next'
import * as echarts from 'echarts'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useGovernanceScope } from '../../../composables/useGovernanceScope'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import {
  pageConfidence,
  adminSetConfidence,
  getConfidenceTrend,
  type ConfidenceVO,
  type ConfidenceTrendPoint,
} from '../../../api/admin/field'

const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)
const adminContext = useAdminContextStore()
const scope = useGovernanceScope()
const readKey = computed(() =>
  JSON.stringify([
    scope.auth.token,
    scope.iam.snapshot,
    scope.iam.loaded,
    scope.iam.errorMessage,
    adminContext.datasourceId,
    scope.route.query.datasourceId,
    adminContext.loading,
    adminContext.routeSyncing,
  ]),
)
let disposed = false,
  listRequest = 0,
  countRequest = 0,
  trendRequest = 0,
  setSession = 0
const trendLoading = ref(false)
const pageError = ref(''),
  countError = ref(''),
  trendError = ref(''),
  setError = ref(''),
  setBusy = ref(false)
function current(key: string) {
  return !disposed && key === readKey.value && canViewFields.value
}
const FIELD_VIEW = 'governance:field:view'
const FIELD_MANAGE = 'governance:field:manage'
const MANAGE_HINT = '需要 governance:field:manage 与当前数据源同一绑定'
const canViewFields = computed(() => scope.canSource(FIELD_VIEW))
const canManageFields = computed(() => scope.canSource(FIELD_MANAGE))

const loading = ref(false)
const confidenceList = ref<ConfidenceVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const levelFilter = ref('')
// 各等级的全量计数（独立于当前分页，避免用单页数据推断全局）
const levelCounts = ref<{ HIGH: number | null; MEDIUM: number | null; LOW: number | null }>({
  HIGH: null,
  MEDIUM: null,
  LOW: null,
})
const showTrendDialog = ref(false)
const showSetDialog = ref(false)
const trendData = ref<ConfidenceTrendPoint[]>([])
const currentFieldId = ref<number>(0)
const setForm = ref({ score: 50, reason: '' })
const chartRef = ref<HTMLDivElement | null>(null)
let chartInstance: echarts.ECharts | null = null
useAdminScrollLock(() => showTrendDialog.value || showSetDialog.value)

const levelLabel = (level: string) => {
  switch (level) {
    case 'HIGH':
      return '高'
    case 'MEDIUM':
      return '中'
    case 'LOW':
      return '低'
    default:
      return level
  }
}
const levelType = (level: string) => {
  switch (level) {
    case 'HIGH':
      return 'success'
    case 'MEDIUM':
      return 'warning'
    case 'LOW':
      return 'danger'
    default:
      return 'info'
  }
}

function cssToken(name: string, fallback: string) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
}

function rgbaColor(color: string, alpha: number) {
  const match = color.match(/^#([\da-f]{3}|[\da-f]{6})$/i)
  if (!match) return color
  const digits = match[1]!.length === 3 ? [...match[1]!].map((digit) => digit + digit).join('') : match[1]!
  const red = Number.parseInt(digits.slice(0, 2), 16)
  const green = Number.parseInt(digits.slice(2, 4), 16)
  const blue = Number.parseInt(digits.slice(4, 6), 16)
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

function confidenceColor(level: string) {
  if (level === 'HIGH') return cssToken('--do-success', '#16a34a')
  if (level === 'MEDIUM') return cssToken('--do-warning', '#d97706')
  return cssToken('--do-danger', '#dc2626')
}

async function fetchConfidenceList() {
  const request = ++listRequest,
    key = readKey.value
  pageError.value = ''
  confidenceList.value = []
  total.value = 0
  loading.value = false
  if (!canViewFields.value) return
  loading.value = true
  try {
    const res = await pageConfidence({
      page: page.value,
      pageSize: pageSize.value,
      level: levelFilter.value || undefined,
      datasourceId: adminContext.datasourceId,
    })
    if (!current(key) || request !== listRequest) return
    confidenceList.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch {
    if (current(key) && request === listRequest) pageError.value = '既有来源分列表读取失败，请重试。'
  } finally {
    if (current(key) && request === listRequest) loading.value = false
  }
}
function handlePageChange(p: number) {
  page.value = p
  fetchConfidenceList()
}

function handleLevelFilter() {
  page.value = 1
  fetchConfidenceList()
}

// 获取各等级的全量计数：分别按等级查 total（pageSize=1 仅取总数，不依赖当前分页）
async function fetchLevelCounts() {
  const request = ++countRequest,
    key = readKey.value,
    ds = adminContext.datasourceId
  levelCounts.value = { HIGH: null, MEDIUM: null, LOW: null }
  countError.value = ''
  if (!canViewFields.value) return
  const levels = ['HIGH', 'MEDIUM', 'LOW'] as const
  const results = await Promise.allSettled(
    levels.map((level) => pageConfidence({ page: 1, pageSize: 1, level, datasourceId: ds })),
  )
  if (!current(key) || request !== countRequest) return
  results.forEach((result, index) => {
    const level = levels[index]!
    if (result.status === 'fulfilled' && Number.isFinite(result.value.data?.total))
      levelCounts.value[level] = result.value.data.total
    else countError.value = '部分等级统计不可用，未知不计为0。'
  })
}
async function openTrend(columnMetaId: number) {
  if (!canViewFields.value || !confidenceList.value.some((r) => r.columnMetaId === columnMetaId)) return
  const request = ++trendRequest,
    key = readKey.value
  currentFieldId.value = columnMetaId
  trendData.value = []
  trendError.value = ''
  trendLoading.value = true
  showTrendDialog.value = true
  const owned = () =>
    current(key) && request === trendRequest && showTrendDialog.value && columnMetaId === currentFieldId.value
  try {
    const res = await getConfidenceTrend(columnMetaId, 30)
    if (!owned()) return
    trendData.value = res.data ?? []
    trendLoading.value=false
    await nextTick()
    if (owned()) renderChart()
  } catch {
    if (owned()) trendError.value = '趋势读取失败，不能视作没有事件。'
  } finally {
    if (owned()) trendLoading.value = false
  }
}
function renderChart() {
  if (!chartRef.value || !trendData.value.length) return
  if (chartInstance) {
    chartInstance.dispose()
  }
  chartInstance = echarts.init(chartRef.value)
  const xData = trendData.value.map((p) => p.time?.substring(0, 16) || '')
  const yData = trendData.value.map((p) => p.cumulativeScore)
  const primary = cssToken('--do-primary', '#2d68cc')
  const success = cssToken('--do-success', '#16a34a')
  const warning = cssToken('--do-warning', '#d97706')
  chartInstance.setOption({
    animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      formatter: (params: any) => {
        const point = trendData.value[params[0].dataIndex]
        const delta = point.deltaScore > 0 ? `+${point.deltaScore}` : `${point.deltaScore}`
        return `${params[0].axisValue}\n旧分数: ${point.cumulativeScore}\n变化: ${delta}\n事件: ${point.eventType}`
      },
    },
    grid: { left: 50, right: 20, top: 30, bottom: 40 },
    xAxis: {
      type: 'category',
      data: xData,
      axisLabel: { rotate: 30, fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      name: '可信度分数',
    },
    series: [
      {
        type: 'line',
        data: yData,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { color: primary, width: 2 },
        itemStyle: { color: primary },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: rgbaColor(primary, 0.3) },
            { offset: 1, color: rgbaColor(primary, 0.02) },
          ]),
        },
        markLine: {
          silent: true,
          data: [
            { yAxis: 70, lineStyle: { color: success, type: 'dashed' }, label: { formatter: 'HIGH' } },
            { yAxis: 40, lineStyle: { color: warning, type: 'dashed' }, label: { formatter: 'MEDIUM' } },
          ],
        },
      },
    ],
  })
}

function disposeChart() {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
}

function openSetDialog(columnMetaId: number, currentScore: number) {
  if (
    !canManageFields.value ||
    setBusy.value ||
    !confidenceList.value.some((r) => r.columnMetaId === columnMetaId)
  ) {
    ElMessage.warning(MANAGE_HINT)
    return
  }
  currentFieldId.value = columnMetaId
  setSession++
  setError.value = ''
  setForm.value = { score: currentScore, reason: '' }
  showSetDialog.value = true
}

async function confirmSetScore() {
  if (setBusy.value || !showSetDialog.value || !canManageFields.value) return
  const id = currentFieldId.value,
    key = readKey.value,
    session = setSession,
    body = { ...setForm.value }
  if (!Number.isFinite(body.score) || body.score < 0 || body.score > 100) return
  const owned = () => current(key) && session === setSession && showSetDialog.value && canManageFields.value
  setBusy.value = true
  setError.value = ''
  try {
    await trackGovernanceWrite(
      scope.auth.token,
      [`legacy-confidence:${id}`],
      `调整旧来源分 · 字段 #${id}`,
      () => adminSetConfidence(id, body),
    )
    if (!owned()) return
    ElMessage.success('既有来源分已调整；当前修订使用档案不由此计算')
    showSetDialog.value = false
    await Promise.all([fetchConfidenceList(), fetchLevelCounts()])
  } catch (cause) {
    if (owned())
      setError.value =
        (cause as any)?.response?.data?.message ||
        (cause instanceof Error ? cause.message : '设置未确认完成，请核对旧来源记录')
  } finally {
    if (session === setSession) setBusy.value = false
  }
}
watch(
  readKey,
  () => {
    listRequest++
    countRequest++
    trendRequest++
    setSession++
    showTrendDialog.value = false
    showSetDialog.value = false
    setBusy.value = false
    trendData.value = []
    trendLoading.value = false
    page.value = 1
    disposeChart()
    void Promise.all([fetchConfidenceList(), fetchLevelCounts()])
  },
  { immediate: true, flush: 'sync' },
)
watch(
  showTrendDialog,
  (open) => {
    if (!open) {
      trendRequest++
      trendLoading.value = false
      disposeChart()
    }
  },
  { flush: 'sync' },
)
watch(
  showSetDialog,
  (open) => {
    if (!open) {
      setSession++
      setBusy.value = false
    }
  },
  { flush: 'sync' },
)
onMounted(() => {
  withContext(() => {
    reveal('.content-panel, .stats-row, .toolbar', { y: 14, stagger: 0.06 })
  })
  void adminContext.initialize()
})
onBeforeUnmount(() => {
  disposed = true
  listRequest++
  countRequest++
  trendRequest++
  setSession++
  disposeChart()
})
</script>

<template>
  <main ref="pageRef" class="confidence-page post-login-page">
    <GovernanceWriteNotice />
    <div class="legacy-confidence-heading">
      <h3>既有来源可信度记录</h3>
      <p>
        源级旧政策记录，用于追溯趋势与人工调整。它不替代上方当前修订的使用参考分，也不是业务正确概率；当前快照不限制此源级列表。
      </p>
    </div>
    <p v-if="countError" role="status">{{ countError }}</p>
    <p v-if="!canViewFields" role="status">没有当前源的字段查看能力，未读取旧来源记录。</p>

    <section class="stats-row">
      <div class="stat-card high">
        <span class="stat-value">{{ levelCounts.HIGH ?? '—' }}</span>
        <span class="stat-label">高可信</span>
      </div>
      <div class="stat-card medium">
        <span class="stat-value">{{ levelCounts.MEDIUM ?? '—' }}</span>
        <span class="stat-label">中可信</span>
      </div>
      <div class="stat-card low">
        <span class="stat-value">{{ levelCounts.LOW ?? '—' }}</span>
        <span class="stat-label">低可信</span>
      </div>
    </section>

    <section class="content-panel">
      <div class="toolbar">
        <el-select
          v-model="levelFilter"
          placeholder="全部等级"
          clearable
          size="small"
          style="width: 140px"
          @change="handleLevelFilter"
        >
          <el-option label="全部等级" value="" />
          <el-option label="高可信" value="HIGH" />
          <el-option label="中可信" value="MEDIUM" />
          <el-option label="低可信" value="LOW" />
        </el-select>
      </div>
      <p v-if="pageError" role="alert">
        {{ pageError }}
        <el-button @click="fetchConfidenceList">重试</el-button>
      </p>
      <el-table v-else empty-text="暂无既有来源分记录" :data="confidenceList" v-loading="loading" stripe>
        <el-table-column prop="columnMetaId" label="字段ID" width="90" />
        <el-table-column prop="tableName" label="表名" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.tableName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="columnName" label="字段名" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.columnName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="score" label="分数" width="160">
          <template #default="{ row }">
            <el-progress :percentage="row.score" :color="confidenceColor(row.level)" :stroke-width="10" />
          </template>
        </el-table-column>
        <el-table-column prop="level" label="等级" width="90">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small">{{ levelLabel(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
        <el-table-column prop="lastUpdated" label="更新时间" width="170" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTrend(row.columnMetaId)">
              <TrendingUp :size="14" style="margin-right: 2px" />
              趋势
            </el-button>
            <el-button
              link
              type="warning"
              size="small"
              :disabled="!canManageFields"
              :title="canManageFields ? '' : MANAGE_HINT"
              @click="openSetDialog(row.columnMetaId, row.score)"
            >
              <Settings :size="14" style="margin-right: 2px" />
              设置
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-bar" v-if="total > 0">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :current-page="page"
          :page-size="pageSize"
          @current-change="handlePageChange"
        />
      </div>
    </section>

    <el-dialog
      class="governance-dialog"
      v-model="showTrendDialog"
      title="旧来源分趋势（近30天）"
      width="min(680px,94vw)"
      @closed="disposeChart"
    >
      <p v-if="trendLoading" role="status">正在读取旧来源分趋势…</p>
      <p v-else-if="trendError" role="alert">{{ trendError }}</p>
      <div v-else-if="trendData.length" ref="chartRef" class="trend-chart"></div>
      <el-empty v-else description="暂无趋势数据" />
    </el-dialog>

    <el-dialog
      class="governance-dialog"
      v-model="showSetDialog"
      title="调整既有来源分"
      :show-close="!setBusy"
      :close-on-click-modal="false"
      :close-on-press-escape="!setBusy"
      width="min(440px, 94vw)"
    >
      <p v-if="setError" role="alert">{{ setError }}</p>
      <el-form :disabled="setBusy" label-width="80px">
        <el-form-item label="分数">
          <el-slider v-model="setForm.score" :min="0" :max="100" show-input />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="setForm.reason" type="textarea" placeholder="设置原因（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="setBusy" @click="showSetDialog = false">取消</el-button>
        <el-button type="primary" :loading="setBusy" :disabled="!canManageFields" @click="confirmSetScore">
          确认设置
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.legacy-confidence-heading h3 {
  margin: 0;
  font-size: 16px;
}
.legacy-confidence-heading p {
  font-size: 13px;
  line-height: 1.7;
  color: var(--do-muted);
}
.confidence-page {
  min-width: 0;
  width: 100%;
  padding: 0;
}
.stats-row {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  flex: 1;
  padding: 16px;
  border-radius: 8px;
  border: 1px solid var(--do-line);
  background: var(--do-surface);
  text-align: center;
}
.stat-card .stat-value {
  display: block;
  font-size: 28px;
  font-weight: 600;
}
.stat-card .stat-label {
  font-size: 13px;
  color: var(--do-muted);
}
.stat-card.high .stat-value {
  color: var(--do-success);
}
.stat-card.medium .stat-value {
  color: var(--do-warning);
}
.stat-card.low .stat-value {
  color: var(--do-danger);
}
.content-panel {
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  padding: 16px;
}
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.trend-chart {
  width: 100%;
  height: 360px;
}
</style>
