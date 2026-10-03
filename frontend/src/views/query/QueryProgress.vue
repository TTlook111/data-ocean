<script setup lang="ts">
import { computed } from 'vue'
import { ShieldAlert, ShieldCheck } from 'lucide-vue-next'
import type { IamS1QueryTaskResult as QueryTaskResult } from '../../api/iamS1'
import { finalProtectionLabel } from '../../utils/queryDisplayState'

const props = defineProps<{
  agentProgress: Array<{ key: string; label: string; status: 'done' | 'active' | 'pending' }>
  latestResult: QueryTaskResult | null
}>()
const protectionLabel = computed(() => finalProtectionLabel(props.latestResult?.finalProtectionStatus))
</script>

<template>
  <div class="query-progress">
    <div v-if="agentProgress.length" class="agent-progress" aria-label="服务端报告的执行阶段">
      <div v-for="step in agentProgress" :key="step.key" class="agent-step" :class="step.status">
        <span class="step-dot" aria-hidden="true"></span>
        <strong>{{ step.label }}</strong>
        <small v-if="step.status === 'active'">进行中</small>
        <small v-else-if="step.status === 'done'">已到达</small>
        <small v-else>待执行</small>
      </div>
    </div>

    <div v-if="protectionLabel" class="trust-notice protected" role="status">
      <ShieldCheck :size="16" aria-hidden="true" />
      <span>{{ protectionLabel }}</span>
    </div>
    <div v-else-if="latestResult?.finalProtectionStatus" class="trust-notice">
      <span>服务端保护状态：{{ latestResult.finalProtectionStatus }}</span>
    </div>
    <div v-if="latestResult?.degraded" class="trust-notice warning">
      <ShieldAlert :size="16" aria-hidden="true" />
      <span>{{ latestResult.degradeNotice || '知识库暂时不可用，当前结果已按降级策略返回。' }}</span>
    </div>
  </div>
</template>

<style scoped>
.query-progress { display: grid; gap: 12px; }
.agent-progress { display: grid; gap: 7px; }
.agent-step { min-height: 42px; display: grid; grid-template-columns: 12px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-muted); background: var(--do-bg); font-size: 12px; }
.agent-step strong { min-width: 0; overflow: hidden; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.agent-step small { color: var(--do-muted); font-size: 11px; }
.step-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--do-line-strong); }
.agent-step.done { color: var(--do-ink); }
.agent-step.done .step-dot { background: var(--do-success); }
.agent-step.active { border-color: color-mix(in srgb, var(--do-primary) 38%, var(--do-line)); color: var(--do-primary-strong); background: var(--do-primary-soft); }
.agent-step.active .step-dot { background: var(--do-primary); box-shadow: 0 0 0 4px color-mix(in srgb, var(--do-primary) 16%, transparent); }
.trust-notice { display: flex; align-items: flex-start; gap: 8px; padding: 9px 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-ink); background: var(--do-bg); font-size: 12px; line-height: 1.5; }
.trust-notice.protected { border-color: color-mix(in srgb, var(--do-success) 35%, var(--do-line)); color: var(--do-success); background: var(--do-success-soft); }
.trust-notice.warning { border-color: color-mix(in srgb, var(--do-warning) 35%, var(--do-line)); color: var(--do-warning); background: var(--do-warning-soft); }
@media (prefers-reduced-motion: reduce) { .agent-step.active .step-dot { box-shadow: none; } }
</style>
