<script setup lang="ts">
/**
 * 页面头部组件
 *
 * 统一的页面标题和操作区布局。
 */

interface Props {
  /** 页面标题 */
  title: string
  /** 副标题 */
  subtitle?: string
}

defineProps<Props>()
</script>

<template>
  <header class="page-header">
    <div class="page-header__info">
      <h1 class="page-header__title">{{ title }}</h1>
      <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 0;
}

.page-header__info {
  min-width: 0;
}

.page-header__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--do-ink);
  line-height: 1.3;
}

.page-header__subtitle {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--do-muted);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
