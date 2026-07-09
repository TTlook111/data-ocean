/**
 * QueryResult — 结果展示容器组件
 * 包含结果标签页（表格/SQL/图表/可信度）
 */
<script setup lang="ts">
import {
  BarChart3,
  Download,
  ListChecks,
  MessageSquareText,
  ShieldCheck,
  ThumbsDown,
  ThumbsUp,
} from 'lucide-vue-next'
import type { QueryTaskResult } from '../../api/query'
import ChartContainer from '../../components/chart/ChartContainer.vue'
import QueryProgress from './QueryProgress.vue'

const props = defineProps<{
  latestResult: QueryTaskResult | null
  resultTab: 'table' | 'sql' | 'chart' | 'trust'
  chartType: 'bar' | 'line' | 'pie'
  chartOption: Record<string, unknown> | null
  pagedTableData: Record<string, unknown>[]
  tablePage: number
  tablePageSize: number
  agentProgress: Array<{ key: string; label: string; status: 'done' | 'active' | 'pending' | 'failed' }>
  isLatestProcessing: boolean
  trustSummary: Array<{ label: string; value: string; muted: boolean }>
}>()

const emit = defineEmits<{
  'update:resultTab': [tab: 'table' | 'sql' | 'chart' | 'trust']
  'switch-chart-type': [type: 'bar' | 'line' | 'pie']
  'export-csv': []
  'export-png': []
  'feedback': [type: 'LIKE' | 'DISLIKE']
  'apply-example': [text: string]
  'update:tablePage': [page: number]
}>()
</script>

<template>
  <aside class="result-preview result-rail" aria-label="查询结果与可信依据">
    <!-- 标签页 -->
    <div class="result-tabs">
      <span :class="{ active: resultTab === 'table' }" @click="emit('update:resultTab', 'table')"><ListChecks :size="14" />表格结果</span>
      <span :class="{ active: resultTab === 'sql' }" @click="emit('update:resultTab', 'sql')"><MessageSquareText :size="14" />SQL</span>
      <span :class="{ active: resultTab === 'chart' }" @click="emit('update:resultTab', 'chart')"><BarChart3 :size="14" />图表</span>
      <span :class="{ active: resultTab === 'trust' }" @click="emit('update:resultTab', 'trust')"><ShieldCheck :size="14" />可信依据</span>
    </div>

    <!-- 暂无结果 -->
    <div v-if="!latestResult" class="result-empty">
      <strong>暂无查询结果</strong>
    </div>

    <!-- 表格结果 -->
    <div v-else-if="resultTab === 'table'" class="result-table-wrap">
      <div v-if="isLatestProcessing" class="result-empty">
        <strong>{{ latestResult.progressMessage || '查询正在执行中' }}</strong>
        <span>可以切换到"可信依据"查看 Agent 当前进度。</span>
      </div>
      <div v-if="latestResult.data && latestResult.data.length" class="result-meta">
        <small>共 {{ latestResult.rowCount || latestResult.data.length }} 行 · 耗时 {{ latestResult.totalTimeMs }}ms</small>
        <small v-if="latestResult.usedTables?.length">使用表：{{ latestResult.usedTables.join(', ') }}</small>
      </div>
      <el-table v-if="!isLatestProcessing && latestResult.data && latestResult.data.length" :data="pagedTableData" border stripe max-height="320" size="small">
        <el-table-column v-for="col in (latestResult.columns || [])" :key="col.name" :prop="col.name" :label="col.comment || col.name" min-width="120" show-overflow-tooltip />
      </el-table>
      <el-pagination
        v-if="!isLatestProcessing && latestResult.data && latestResult.data.length > tablePageSize"
        :current-page="tablePage"
        :page-size="tablePageSize"
        :total="latestResult.data.length"
        layout="total, prev, pager, next"
        size="small"
        style="margin-top: 8px; justify-content: flex-end;"
        @update:current-page="emit('update:tablePage', $event)"
      />
      <div v-else-if="!isLatestProcessing" class="result-empty"><strong>查询完成但无数据返回</strong></div>
    </div>

    <!-- SQL 结果 -->
    <div v-else-if="resultTab === 'sql'" class="result-sql-wrap">
      <pre v-if="latestResult.sql" class="sql-block">{{ latestResult.sql }}</pre>
      <p v-if="latestResult.sqlExplanation" class="sql-explanation">{{ latestResult.sqlExplanation }}</p>
      <div v-if="!latestResult.sql" class="result-empty"><strong>无 SQL</strong></div>
    </div>

    <!-- 图表结果 -->
    <div v-else-if="resultTab === 'chart'" class="result-chart-wrap">
      <div v-if="latestResult.chartConfig" class="chart-toolbar">
        <div class="chart-type-switcher">
          <button :class="{ active: chartType === 'bar' }" @click="emit('switch-chart-type', 'bar')">柱状图</button>
          <button :class="{ active: chartType === 'line' }" @click="emit('switch-chart-type', 'line')">折线图</button>
          <button :class="{ active: chartType === 'pie' }" @click="emit('switch-chart-type', 'pie')">饼图</button>
        </div>
        <button class="export-btn" @click="emit('export-png')" :disabled="latestResult.canExport === false"><Download :size="14" />导出 PNG</button>
      </div>
      <ChartContainer v-if="latestResult.chartConfig" :option="chartOption" />
      <div v-else class="result-empty"><strong>无图表数据</strong></div>
    </div>

    <!-- 可信依据 -->
    <div v-else-if="resultTab === 'trust'" class="trust-panel">
      <QueryProgress
        :agent-progress="agentProgress"
        :is-processing="isLatestProcessing"
        :latest-result="latestResult"
      />
      <div class="trust-grid">
        <div
          v-for="item in trustSummary"
          :key="item.label"
          class="trust-card"
          :class="{ muted: item.muted }"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      </div>
    </div>

    <!-- 操作栏 -->
    <div v-if="latestResult" class="result-actions">
      <button class="export-btn" @click="emit('export-csv')" :disabled="!latestResult.data?.length || latestResult.canExport === false">
        <Download :size="14" />导出 CSV
      </button>
      <div class="feedback-btns">
        <button class="feedback-btn like" @click="emit('feedback', 'LIKE')" title="结果准确">
          <ThumbsUp :size="15" />
        </button>
        <button class="feedback-btn dislike" @click="emit('feedback', 'DISLIKE')" title="结果有误">
          <ThumbsDown :size="15" />
        </button>
      </div>
    </div>

    <!-- 推荐追问 -->
    <div v-if="latestResult?.suggestedQuestions?.length" class="suggested-questions">
      <small>推荐追问：</small>
      <button v-for="q in latestResult.suggestedQuestions" :key="q" type="button" @click="emit('apply-example', q)">{{ q }}</button>
    </div>
  </aside>
</template>
