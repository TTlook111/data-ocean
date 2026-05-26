<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { TrendingUp, Settings } from 'lucide-vue-next'
import * as echarts from 'echarts'
import { useGsapMotion } from '../../../composables/useGsapMotion'
import {
  batchGetConfidence,
  adminSetConfidence,
  getConfidenceTrend,
  type ConfidenceVO,
  type ConfidenceTrendPoint
} from '../../../api/admin/field'

const pageRef = ref<HTMLElement | null>(null)
const { reveal, withContext } = useGsapMotion(pageRef)

const loading = ref(false)
const confidenceList = ref<ConfidenceVO[]>([])
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
    const ids = Array.from({ length: 50 }, (_, i) => i + 1)
    const res = await batchGetConfidence(ids)
    confidenceList.value = res.data ?? []
  } finally {
    loading.value = false
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
    await fetchConfidenceList()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '设置失败')
  }
}

onMounted(() => {
  withContext(() => { reveal('.page-header, .content-panel, .stats-row, .toolbar', { y: 14, stagger: 0.06 }) })
  fetchConfidenceList()
})
</script>

<template>
  <main ref="pageRef" class="confidence-page post-login-page">
    <header class="page-header">
      <div>
        <p>字段治理</p>
        <h1>可信度看板</h1>
        <span class="header-subtitle">查看和管理字段可信度评分，分数影响 RAG 召回和 SQL 生成优先级</span>
      </div>
    </header>

    <section class="stats-row">
      <div class="stat-card high">
        <span class="stat-value">{{ confidenceList.filter(c => c.level === 'HIGH').length }}</span>
        <span class="stat-label">高可信</span>
      </div>
      <div class="stat-card medium">
        <span class="stat-value">{{ confidenceList.filter(c => c.level === 'MEDIUM').length }}</span>
        <span class="stat-label">中可信</span>
      </div>
      <div class="stat-card low">
        <span class="stat-value">{{ confidenceList.filter(c => c.level === 'LOW').length }}</span>
        <span class="stat-label">低可信</span>
      </div>
    </section>

    <section class="content-panel">
      <el-table :data="confidenceList" v-loading="loading" stripe>
        <el-table-column prop="columnMetaId" label="字段ID" width="100" />
        <el-table-column prop="score" label="分数" width="100">
          <template #default="{ row }">
            <el-progress :percentage="row.score" :color="row.level === 'HIGH' ? '#67c23a' : row.level === 'MEDIUM' ? '#e6a23c' : '#f56c6c'" :stroke-width="10" />
          </template>
        </el-table-column>
        <el-table-column prop="level" label="等级" width="100">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small">{{ levelLabel(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" />
        <el-table-column prop="lastUpdated" label="更新时间" width="180" />
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
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-header h1 { margin: 4px 0; font-size: 22px; color: var(--do-ink); }
.page-header p { margin: 0; font-size: 12px; color: var(--do-muted); }
.header-subtitle { font-size: 13px; color: var(--do-muted); }
.stats-row { display: flex; gap: 16px; margin-bottom: 20px; }
.stat-card { flex: 1; padding: 16px; border-radius: 8px; border: 1px solid var(--do-line); background: var(--do-surface); text-align: center; }
.stat-card .stat-value { display: block; font-size: 28px; font-weight: 600; }
.stat-card .stat-label { font-size: 13px; color: var(--do-muted); }
.stat-card.high .stat-value { color: #67c23a; }
.stat-card.medium .stat-value { color: #e6a23c; }
.stat-card.low .stat-value { color: #f56c6c; }
.content-panel { background: var(--do-surface); border: 1px solid var(--do-line); border-radius: 8px; padding: 16px; }
.trend-chart { width: 100%; height: 360px; }
</style>
