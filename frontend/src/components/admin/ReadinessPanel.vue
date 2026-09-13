<script setup lang="ts">
import { computed } from 'vue'
import { CheckCircle2, CircleAlert, CircleDashed } from 'lucide-vue-next'
import type { DatasourceReadiness } from '../../api/admin/datasource'
import BusinessStatusBadge from './BusinessStatusBadge.vue'

const props = defineProps<{ readiness: DatasourceReadiness }>()

const dimensions = computed(() => [
  { key: 'connectionReady', label: '连接', value: props.readiness.connectionReady },
  { key: 'metadataReady', label: '采集', value: props.readiness.metadataReady },
  { key: 'governanceReady', label: '治理', value: props.readiness.governanceReady },
  { key: 'knowledgeReady', label: '知识', value: props.readiness.knowledgeReady },
  { key: 'permissionReady', label: '授权', value: props.readiness.permissionReady },
])
</script>

<template>
  <section class="readiness-panel">
    <div class="readiness-panel__heading">
      <div>
        <span class="readiness-panel__eyebrow">业务就绪度</span>
        <h2>{{ readiness.datasourceName }}</h2>
      </div>
      <BusinessStatusBadge :status="readiness.askable ? 'PUBLISHED' : readiness.stage" :label="readiness.askable ? '可以问数' : readiness.stageLabel" />
    </div>

    <div class="readiness-panel__progress">
      <div class="readiness-panel__progress-copy">
        <span>当前阶段：{{ readiness.stageLabel }}</span>
        <strong>{{ readiness.progress }}%</strong>
      </div>
      <el-progress :percentage="readiness.progress" :show-text="false" :status="readiness.askable ? 'success' : undefined" />
    </div>

    <div class="readiness-panel__grid">
      <div v-for="item in dimensions" :key="item.key" class="readiness-panel__item" :class="{ ready: item.value }">
        <CheckCircle2 v-if="item.value" :size="17" />
        <CircleAlert v-else-if="readiness.blockReasons.length" :size="17" />
        <CircleDashed v-else :size="17" />
        <span>{{ item.label }}</span>
        <strong>{{ item.value ? '已就绪' : '待处理' }}</strong>
      </div>
    </div>
  </section>
</template>

<style scoped>
.readiness-panel {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.readiness-panel__heading,
.readiness-panel__progress-copy {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.readiness-panel__eyebrow {
  color: var(--do-muted);
  font-size: 12px;
}

h2 {
  margin: 4px 0 0;
  color: var(--do-ink);
  font-size: 18px;
}

.readiness-panel__progress {
  margin-top: 18px;
}

.readiness-panel__progress-copy {
  margin-bottom: 8px;
  color: var(--do-muted);
  font-size: 12px;
}

.readiness-panel__progress-copy strong {
  color: var(--do-ink);
  font-size: 18px;
}

.readiness-panel__grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
  margin-top: 20px;
}

.readiness-panel__item {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 7px;
  padding: 10px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-muted);
  font-size: 12px;
}

.readiness-panel__item svg {
  grid-row: span 2;
  margin-top: 2px;
  color: var(--do-warning);
}

.readiness-panel__item.ready svg {
  color: var(--do-success);
}

.readiness-panel__item strong {
  color: var(--do-ink);
  font-size: 12px;
}

</style>
