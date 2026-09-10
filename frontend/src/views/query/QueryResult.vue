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
  X,
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
  'close': []
}>()
</script>

<template>
  <aside class="result-preview result-rail" aria-label="查询结果与可信依据">
    <header class="result-header">
      <div>
        <strong>结果检查器</strong>
        <small>数据、图表、SQL 与可信依据</small>
      </div>
      <button type="button" aria-label="收起结果检查器" @click="emit('close')"><X :size="18" /></button>
    </header>
    <!-- 标签页 -->
    <div class="result-tabs" role="tablist" aria-label="结果视图">
      <button type="button" role="tab" :aria-selected="resultTab === 'table'" :class="{ active: resultTab === 'table' }" @click="emit('update:resultTab', 'table')">
        <ListChecks :size="14" />
        <span>表格结果</span>
      </button>
      <button type="button" role="tab" :aria-selected="resultTab === 'sql'" :class="{ active: resultTab === 'sql' }" @click="emit('update:resultTab', 'sql')">
        <MessageSquareText :size="14" />
        <span>SQL</span>
      </button>
      <button type="button" role="tab" :aria-selected="resultTab === 'chart'" :class="{ active: resultTab === 'chart' }" @click="emit('update:resultTab', 'chart')">
        <BarChart3 :size="14" />
        <span>图表</span>
      </button>
      <button type="button" role="tab" :aria-selected="resultTab === 'trust'" :class="{ active: resultTab === 'trust' }" @click="emit('update:resultTab', 'trust')">
        <ShieldCheck :size="14" />
        <span>可信依据</span>
      </button>
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
      <div v-if="!isLatestProcessing && (!latestResult.data || !latestResult.data.length)" class="result-empty"><strong>查询完成但无数据返回</strong></div>
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

<style scoped>
.result-preview {
  min-width: 0;
  height: 100vh;
  overflow: hidden;
  border-left: 1px solid var(--do-line);
  background: var(--do-surface);
  box-shadow: -12px 0 36px rgba(15, 23, 42, 0.06);
}

.result-header {
  min-height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 18px;
  border-bottom: 1px solid var(--do-line);
}

.result-header strong,
.result-header small {
  display: block;
}

.result-header strong {
  color: var(--do-ink);
  font-size: 15px;
}

.result-header small {
  margin-top: 4px;
  color: var(--do-muted);
  font-size: 11px;
}

.result-header button {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  color: var(--do-muted);
  background: transparent;
  cursor: pointer;
}

.result-header button:hover {
  color: var(--do-ink);
  background: var(--do-bg);
}

.result-tabs {
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--do-line);
  background: linear-gradient(180deg, rgba(248, 251, 255, 0.98), rgba(255, 255, 255, 0.94));
}

.result-tabs button,
.chart-type-switcher button,
.export-btn,
.feedback-btn,
.suggested-questions button {
  font: inherit;
}

.result-tabs button {
  flex: 1 1 0;
  height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  justify-content: center;
  padding: 0 7px;
  border: 0;
  border-radius: 8px;
  color: var(--do-muted);
  background: transparent;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: color 150ms ease, background 150ms ease, box-shadow 150ms ease;
}

.result-tabs button:hover {
  color: var(--do-primary-strong);
  background: rgba(77, 143, 220, 0.07);
}

.result-tabs button.active {
  color: var(--do-primary-strong);
  background: #eaf4ff;
  box-shadow: inset 0 0 0 1px rgba(77, 143, 220, 0.14);
}

.result-tabs button:focus-visible,
.result-header button:focus-visible,
.chart-type-switcher button:focus-visible,
.export-btn:focus-visible,
.feedback-btn:focus-visible,
.suggested-questions button:focus-visible {
  outline: 3px solid rgba(77, 143, 220, 0.2);
  outline-offset: 2px;
}

.result-empty {
  min-height: 112px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 6px;
  padding: 24px;
  color: var(--do-muted);
  font-size: 13px;
  text-align: center;
}

.result-empty strong {
  color: var(--do-ink);
  font-size: 14px;
}

.result-empty span {
  color: var(--do-muted);
  font-size: 12px;
}

.result-table-wrap,
.result-sql-wrap,
.result-chart-wrap {
  min-width: 0;
  padding: 16px;
}

.result-meta {
  display: grid;
  gap: 4px;
  margin-bottom: 10px;
  color: var(--do-muted);
  font-size: 12px;
}

.result-meta small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-table-wrap :deep(.el-table) {
  width: 100%;
}

.result-table-wrap :deep(.el-table__header th) {
  background: #f8fafc;
  color: #475569;
}

.sql-block {
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: #dbeafe;
  background: #172033;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.sql-explanation {
  margin: 12px 0 0;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.7;
}

.chart-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.chart-type-switcher {
  display: flex;
  gap: 4px;
}

.chart-type-switcher button {
  padding: 5px 10px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  color: var(--do-muted);
  background: var(--do-surface);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 150ms ease, color 150ms ease, background 150ms ease;
}

.chart-type-switcher button:hover {
  border-color: var(--do-primary);
  color: var(--do-primary-strong);
}

.chart-type-switcher button.active {
  border-color: var(--do-primary);
  color: #fff;
  background: var(--do-primary);
}

.result-chart-wrap :deep(.chart-container) {
  min-height: 280px;
}

.trust-panel {
  display: grid;
  gap: 12px;
  padding: 12px;
}

.trust-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.trust-card {
  min-height: 78px;
  display: grid;
  align-content: start;
  gap: 7px;
  padding: 11px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
}

.trust-card span {
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 900;
}

.trust-card strong {
  overflow-wrap: anywhere;
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.55;
}

.trust-card.muted strong {
  color: var(--do-muted);
  font-weight: 700;
}

.result-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 14px;
  padding: 10px 0 14px;
  border-top: 1px solid var(--do-line);
}

.export-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 11px;
  border: 1px solid var(--do-line);
  border-radius: 7px;
  color: var(--do-ink);
  background: var(--do-surface);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 150ms ease, color 150ms ease, background 150ms ease;
}

.export-btn:hover:not(:disabled) {
  border-color: var(--do-primary);
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}

.export-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.feedback-btns {
  display: flex;
  gap: 8px;
}

.feedback-btn {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid var(--do-line);
  border-radius: 7px;
  color: var(--do-muted);
  background: var(--do-surface);
  cursor: pointer;
  transition: border-color 150ms ease, color 150ms ease, background 150ms ease;
}

.feedback-btn.like:hover {
  border-color: #67c23a;
  color: #67c23a;
  background: #f0f9eb;
}

.feedback-btn.dislike:hover {
  border-color: #f56c6c;
  color: #f56c6c;
  background: #fef0f0;
}

.suggested-questions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 14px 14px;
  padding-top: 12px;
  border-top: 1px solid var(--do-line);
}

.suggested-questions small {
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 800;
}

.suggested-questions button {
  padding: 5px 9px;
  border: 1px solid var(--do-line);
  border-radius: 999px;
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
  font-size: 12px;
  cursor: pointer;
}

.suggested-questions button:hover {
  border-color: var(--do-primary);
  background: #e1f0ff;
}

@media (max-width: 720px) {
  .result-tabs span { display: none; }
}
</style>
