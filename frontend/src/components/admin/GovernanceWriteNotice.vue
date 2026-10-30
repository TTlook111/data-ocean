<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { pendingGovernanceWrites } from '../../composables/governanceWriteTracker'
const auth = useAuthStore()
const records = computed(() => pendingGovernanceWrites.value.filter((item) => item.identity === auth.token))
</script>
<template>
  <aside v-if="records.length" class="governance-write-notice" role="status">
    <strong>核对执行回执</strong>
    <p v-for="(record, index) in records.slice(0, 8)" :key="index">
      {{ record.label }}：{{
        record.state === 'unknown' ? '结果未知，请先重新读取并核对，勿重复提交' : '正在执行'
      }}
    </p>
    <small>保护在当前会话内跨页面保留；整页刷新后仍须核对真实记录，不能把旧成功当作本次回执。</small>
  </aside>
</template>
<style scoped>
.governance-write-notice {
  padding: 14px 16px;
  border: 1px solid var(--do-line);
  border-left: 3px solid var(--do-warning);
  border-radius: 8px;
  background: var(--do-surface);
  font-size: 12px;
  line-height: 1.7;
}
.governance-write-notice p {
  margin: 6px 0;
}
.governance-write-notice small {
  color: var(--do-muted);
}
</style>
