<script setup lang="ts">
import { AlertTriangle } from 'lucide-vue-next'

interface ColumnUsed {
  table: string
  column: string
  confidence?: number
  definition?: string
}

const props = defineProps<{
  tablesUsed?: string[]
  columnsUsed?: ColumnUsed[]
  lowConfidenceWarning?: boolean
  dataScope?: string
}>()
</script>

<template>
  <div v-if="tablesUsed?.length || columnsUsed?.length" class="query-explanation">
    <div v-if="lowConfidenceWarning" class="confidence-warning">
      <AlertTriangle :size="14" />
      <span>部分字段可信度较低，结果仅供参考</span>
    </div>
    <div class="explanation-row" v-if="tablesUsed?.length">
      <span class="label">数据来源：</span>
      <span v-for="t in tablesUsed" :key="t" class="tag">{{ t }}</span>
    </div>
    <div class="explanation-row" v-if="dataScope">
      <span class="label">数据范围：</span>
      <span>{{ dataScope }}</span>
    </div>
    <div v-if="columnsUsed?.length" class="columns-detail">
      <span class="label">使用字段：</span>
      <div class="column-list">
        <span v-for="c in columnsUsed" :key="`${c.table}.${c.column}`" class="column-item">
          {{ c.column }}
          <span v-if="c.confidence" class="confidence" :class="{ low: c.confidence < 70 }">
            {{ c.confidence }}
          </span>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.query-explanation {
  padding: 10px 14px;
  background: var(--do-bg);
  border: 1px solid var(--do-line);
  border-radius: 6px;
  font-size: 12px;
  color: var(--do-muted);
  margin-top: 10px;
}
.confidence-warning {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #e6a23c;
  margin-bottom: 8px;
  font-weight: 500;
}
.explanation-row {
  margin-bottom: 4px;
}
.label { color: var(--do-ink); font-weight: 500; }
.tag {
  display: inline-block;
  padding: 1px 8px;
  margin: 0 4px;
  background: var(--do-primary-soft);
  border-radius: 3px;
  font-size: 11px;
}
.columns-detail { margin-top: 6px; }
.column-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
.column-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: var(--do-surface);
  border: 1px solid var(--do-line);
  border-radius: 3px;
}
.confidence {
  font-size: 10px;
  padding: 0 4px;
  border-radius: 2px;
  background: #e8f5e9;
  color: #2e7d32;
}
.confidence.low {
  background: #fff3e0;
  color: #e65100;
}
</style>
