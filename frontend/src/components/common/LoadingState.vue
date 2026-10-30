<script setup lang="ts">
/**
 * 统一加载状态组件
 *
 * 提供骨架屏和 spinner 两种加载样式。
 */

interface Props {
  /** 加载文本 */
  text?: string
  /** 加载样式 */
  variant?: 'spinner' | 'skeleton'
  /** 骨架屏行数 */
  rows?: number
}

withDefaults(defineProps<Props>(), {
  text: '加载中...',
  variant: 'spinner',
  rows: 3,
})
</script>

<template>
  <div class="loading-state">
    <template v-if="variant === 'spinner'">
      <div class="loading-spinner"></div>
      <span class="loading-text">{{ text }}</span>
    </template>
    <template v-else>
      <div v-for="i in rows" :key="i" class="skeleton-line"></div>
    </template>
  </div>
</template>

<style scoped>
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  gap: 12px;
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--do-line);
  border-top-color: var(--do-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  color: var(--do-muted);
  font-size: 14px;
}

.skeleton-line {
  height: 16px;
  background: linear-gradient(90deg, var(--do-bg) 25%, var(--do-line) 50%, var(--do-bg) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 4px;
  margin-bottom: 8px;
}

.skeleton-line:last-child {
  width: 60%;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
