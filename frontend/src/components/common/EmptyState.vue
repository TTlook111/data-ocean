<script setup lang="ts">
/**
 * 统一空状态组件
 *
 * 显示空数据提示和操作按钮。
 */

interface Props {
  /** 提示消息 */
  message?: string
  /** 操作按钮文本 */
  actionText?: string
}

withDefaults(defineProps<Props>(), {
  message: '暂无数据',
  actionText: '',
})

const emit = defineEmits<{
  action: []
}>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon">
      <slot name="icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"/>
        </svg>
      </slot>
    </div>
    <p class="empty-message">{{ message }}</p>
    <button v-if="actionText" class="empty-action" @click="emit('action')">
      {{ actionText }}
    </button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;
  gap: 12px;
}

.empty-icon {
  color: var(--do-muted);
  opacity: 0.5;
}

.empty-message {
  color: var(--do-muted);
  font-size: 14px;
  text-align: center;
  margin: 0;
}

.empty-action {
  padding: 8px 16px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  background: var(--do-surface);
  color: var(--do-primary);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.empty-action:hover {
  border-color: var(--do-primary);
  background: var(--do-primary-soft);
}
</style>
