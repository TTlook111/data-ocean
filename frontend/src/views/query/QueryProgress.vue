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
