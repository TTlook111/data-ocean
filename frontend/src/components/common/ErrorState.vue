<script setup lang="ts">
/**
 * 统一错误状态组件
 *
 * 显示错误信息和重试按钮。
 */

interface Props {
  /** 错误消息 */
  message?: string
  /** 是否显示重试按钮 */
  showRetry?: boolean
}

withDefaults(defineProps<Props>(), {
  message: '加载失败，请稍后重试',
  showRetry: true,
})

const emit = defineEmits<{
  retry: []
}>()
</script>

<template>
  <div class="error-state">
    <div class="error-icon">!</div>
    <p class="error-message">{{ message }}</p>
    <button v-if="showRetry" class="error-retry" @click="emit('retry')">
      重试
    </button>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  gap: 12px;
}

.error-icon {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fef2f2;
  color: #dc2626;
  font-size: 20px;
  font-weight: bold;
}

.error-message {
  color: var(--do-muted);
  font-size: 14px;
  text-align: center;
  margin: 0;
}

.error-retry {
  padding: 8px 16px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  background: var(--do-surface);
  color: var(--do-primary);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.error-retry:hover {
  border-color: var(--do-primary);
  background: var(--do-primary-soft);
}
</style>
