<script setup lang="ts">
import { ref, onMounted, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { TrendingUp, Settings } from 'lucide-vue-next'
import * as echarts from 'echarts'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import { useAdminContextStore } from '../../../stores/adminContext'
import {
  pageConfidence,
  adminSetConfidence,
  getConfidenceTrend,
  type ConfidenceVO,
  type ConfidenceTrendPoint
} from '../../../api/admin/field'

const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)
const adminContext = useAdminContextStore()

const loading = ref(false)
const confidenceList = ref<ConfidenceVO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const levelFilter = ref('')
// 各等级的全量计数（独立于当前分页，避免用单页数据推断全局）
const levelCounts = ref({ HIGH: 0, MEDIUM: 0, LOW: 0 })
const showTrendDialog = ref(false)
const showSetDialog = ref(false)
const trendData = ref<ConfidenceTrendPoint[]>([])
const currentFieldId = ref<number>(0)
const setForm = ref({ score: 50, reason: '' })
const chartRef = ref<HTMLDivElement | null>(null)
let chartInstance: echarts.ECharts | null = null

const levelLabel = (level: string) => {
  switch (level) {
    case 'HIGH': return '高'
    case 'MEDIUM': return '中'
    case 'LOW': return '低'
    default: return level
  }
}
const levelType = (level: string) => {
  switch (level) {
    case 'HIGH': return 'success'
    case 'MEDIUM': return 'warning'
    case 'LOW': return 'danger'
    default: return 'info'
  }
}

async function fetchConfidenceList() {
  loading.value = true
  try {
    const res = await pageConfidence({
      page: page.value,
      pageSize: pageSize.value,
      level: levelFilter.value || undefined,
      datasourceId: adminContext.datasourceId,
    })
    confidenceList.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '获取可信度列表失败')
  } finally {
    loading.value = false
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
  try {
    const [high, medium, low] = await Promise.all([
      pageConfidence({ page: 1, pageSize: 1, level: 'HIGH', datasourceId: adminContext.datasourceId }),
      pageConfidence({ page: 1, pageSize: 1, level: 'MEDIUM', datasourceId: adminContext.datasourceId }),
      pageConfidence({ page: 1, pageSize: 1, level: 'LOW', datasourceId: adminContext.datasourceId }),
    ])
    levelCounts.value = {
      HIGH: high.data?.total ?? 0,
      MEDIUM: medium.data?.total ?? 0,
      LOW: low.data?.total ?? 0,
    }
  } catch {
    // 统计失败不阻断主列表展示
  }
}

async function openTrend(columnMetaId: number) {
  currentFieldId.value = columnMetaId
  try {
    const res = await getConfidenceTrend(columnMetaId, 30)
    trendData.value = res.data ?? []
    showTrendDialog.value = true
    await nextTick()
    renderChart()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '获取趋势数据失败')
  }
}

function renderChart() {
  if (!chartRef.value || !trendData.value.length) return
  if (chartInstance) {
    chartInstance.dispose()
  }
  chartInstance = echarts.init(chartRef.value)
  const xData = trendData.value.map(p => p.time?.substring(0, 16) || '')
  const yData = trendData.value.map(p => p.cumulativeScore)
  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const point = trendData.value[params[0].dataIndex]
        const delta = point.deltaScore > 0 ? `+${point.deltaScore}` : `${point.deltaScore}`
        return `${params[0].axisValue}<br/>分数: ${point.cumulativeScore}<br/>变化: ${delta}<br/>事件: ${point.eventType}`
      }
    },
    grid: { left: 50, right: 20, top: 30, bottom: 40 },
    xAxis: {
      type: 'category',
      data: xData,
      axisLabel: { rotate: 30, fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      name: '可信度分数'
    },
    series: [{
      type: 'line',
      data: yData,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { color: '#4d8fdc', width: 2 },
      itemStyle: { color: '#4d8fdc' },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(77, 143, 220, 0.3)' },
          { offset: 1, color: 'rgba(77, 143, 220, 0.02)' }
        ])
      },
      markLine: {
        silent: true,
        data: [
          { yAxis: 70, lineStyle: { color: '#67c23a', type: 'dashed' }, label: { formatter: 'HIGH' } },
          { yAxis: 40, lineStyle: { color: '#e6a23c', type: 'dashed' }, label: { formatter: 'MEDIUM' } }
        ]
      }
    }]
  })
}

function disposeChart() {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
}

function openSetDialog(columnMetaId: number, currentScore: number) {
  currentFieldId.value = columnMetaId
  setForm.value = { score: currentScore, reason: '' }
  showSetDialog.value = true
}

async function confirmSetScore() {
  try {
    await adminSetConfidence(currentFieldId.value, setForm.value)
    ElMessage.success('可信度设置成功')
    showSetDialog.value = false
    await Promise.all([fetchConfidenceList(), fetchLevelCounts()])
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '设置失败')
  }
}

onMounted(async () => {
  withContext(() => { reveal('.content-panel, .stats-row, .toolbar', { y: 14, stagger: 0.06 }) })
  await adminContext.initialize()
  await Promise.all([fetchConfidenceList(), fetchLevelCounts()])
})

watch(
  () => adminContext.datasourceId,
  () => {
    page.value = 1
    Promise.all([fetchConfidenceList(), fetchLevelCounts()])
  },
)
</script>

<template>
  <main ref="pageRef" class="confidence-page post-login-page">

    <section class="stats-row">
      <div class="stat-card high">
        <span class="stat-value">{{ levelCounts.HIGH }}</span>
        <span class="stat-label">高可信</span>
      </div>
      <div class="stat-card medium">
        <span class="stat-value">{{ levelCounts.MEDIUM }}</span>
        <span class="stat-label">中可信</span>
      </div>
      <div class="stat-card low">
        <span class="stat-value">{{ levelCounts.LOW }}</span>
        <span class="stat-label">低可信</span>
      </div>
    </section>

    <section class="content-panel">
      <div class="toolbar">
        <el-select v-model="levelFilter" placeholder="全部等级" clearable size="small" style="width: 140px" @change="handleLevelFilter">
          <el-option label="全部等级" value="" />
          <el-option label="高可信" value="HIGH" />
          <el-option label="中可信" value="MEDIUM" />
          <el-option label="低可信" value="LOW" />
        </el-select>
      </div>
      <el-table :data="confidenceList" v-loading="loading" stripe>
        <el-table-column prop="columnMetaId" label="字段ID" width="90" />
        <el-table-column prop="tableName" label="表名" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.tableName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="columnName" label="字段名" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.columnName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="score" label="分数" width="160">
          <template #default="{ row }">
            <el-progress :percentage="row.score" :color="row.level === 'HIGH' ? '#67c23a' : row.level === 'MEDIUM' ? '#e6a23c' : '#f56c6c'" :stroke-width="10" />
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
              <TrendingUp :size="14" style="margin-right: 2px" />趋势
            </el-button>
            <el-button link type="warning" size="small" @click="openSetDialog(row.columnMetaId, row.score)">
              <Settings :size="14" style="margin-right: 2px" />设置
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!confidenceList.length && !loading" description="暂无可信度数据" />
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

    <el-dialog v-model="showTrendDialog" title="可信度趋势（近30天）" width="680px" @closed="disposeChart">
      <div v-if="trendData.length" ref="chartRef" class="trend-chart"></div>
      <el-empty v-else description="暂无趋势数据" />
    </el-dialog>

    <el-dialog v-model="showSetDialog" title="设置可信度" width="400px">
      <el-form label-width="80px">
        <el-form-item label="分数">
          <el-slider v-model="setForm.score" :min="0" :max="100" show-input />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="setForm.reason" type="textarea" placeholder="设置原因（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showSetDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmSetScore">确认设置</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.confidence-page { padding: 24px; }
.stats-row { display: flex; gap: 16px; margin-bottom: 20px; }
.stat-card { flex: 1; padding: 16px; border-radius: 8px; border: 1px solid var(--do-line); background: var(--do-surface); text-align: center; }
.stat-card .stat-value { display: block; font-size: 28px; font-weight: 600; }
.stat-card .stat-label { font-size: 13px; color: var(--do-muted); }
.stat-card.high .stat-value { color: #67c23a; }
.stat-card.medium .stat-value { color: #e6a23c; }
.stat-card.low .stat-value { color: #f56c6c; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
.toolbar { display: flex; gap: 12px; margin-bottom: 12px; }
.pagination-bar { display: flex; justify-content: flex-end; margin-top: 16px; }
.trend-chart { width: 100%; height: 360px; }
</style>
