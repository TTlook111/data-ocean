/**
 * QueryProgress — Agent 进度条组件
 * 展示 Agent 各节点的执行进度
 */
<script setup lang="ts">
import { ShieldCheck, ShieldAlert } from 'lucide-vue-next'
import type { QueryTaskResult } from '../../api/query'

defineProps<{
  agentProgress: Array<{ key: string; label: string; status: 'done' | 'active' | 'pending' | 'failed' }>
  isProcessing: boolean
  latestResult: QueryTaskResult | null
}>()
</script>

<template>
  <div class="trust-panel">
    <div class="agent-progress">
      <div
        v-for="step in agentProgress"
        :key="step.key"
        class="agent-step"
        :class="step.status"
      >
        <span class="step-dot"></span>
        <strong>{{ step.label }}</strong>
      </div>
    </div>

    <div v-if="latestResult?.progressMessage" class="trust-notice">
      <ShieldCheck :size="15" />
      <span>{{ latestResult.progressMessage }}</span>
    </div>
    <div v-if="latestResult?.degraded" class="trust-notice warning">
      <ShieldAlert :size="15" />
      <span>{{ latestResult.degradeNotice || '知识库暂时不可用，当前结果已按降级策略返回。' }}</span>
    </div>
  </div>
</template>

<style scoped>
.trust-panel {
  display: grid;
  gap: 12px;
  padding: 12px;
}

.agent-progress {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}

.agent-step {
  min-height: 48px;
  position: relative;
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  align-items: center;
  gap: 7px;
  padding: 10px 11px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #f8fafc;
  color: var(--do-muted);
  font-size: 12px;
}

.agent-step:not(:last-child)::after {
  position: absolute;
  left: 18px;
  top: calc(100% - 1px);
  width: 1px;
  height: 10px;
  background: var(--do-line-strong);
  content: "";
}

.agent-step strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #cbd5e1;
}

.agent-step.done {
  color: #166534;
  background: #f0fdf4;
  border-color: #bbf7d0;
}

.agent-step.done .step-dot {
  background: #22c55e;
}

.agent-step.active {
  color: var(--do-primary-strong);
  background: #eff6ff;
  border-color: #bfdbfe;
}

.agent-step.active .step-dot {
  background: var(--do-primary);
  box-shadow: 0 0 0 4px rgba(77, 143, 220, 0.14);
}

.agent-step.failed {
  color: #b42318;
  background: #fef2f2;
  border-color: #fecaca;
}

.agent-step.failed .step-dot {
  background: #ef4444;
}

.trust-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  color: var(--do-primary-strong);
  background: #eff6ff;
  font-size: 12px;
  font-weight: 700;
}

.trust-notice.warning {
  color: #92400e;
  background: #fffbeb;
  border-color: #fde68a;
}

@media (max-width: 1200px) {
  .agent-progress {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .agent-step:not(:last-child)::after {
    display: none;
  }
}
</style>
