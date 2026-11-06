<script setup lang="ts">
import TitleHelp from './TitleHelp.vue'

defineProps<{
  title: string
  /** 普通用途默认按需展示；对象身份或必须立即看见的状态才使用 inline。 */
  description?: string
  /** 提供 help 时优先显示它，需包含仍然必要的用途或业务边界说明。 */
  help?: string
  eyebrow?: string
  descriptionMode?: 'tooltip' | 'inline'
}>()
</script>

<template>
  <header class="task-page-header" :class="{ 'task-page-header--with-meta': $slots.meta }">
    <div class="task-page-header__copy">
      <span v-if="eyebrow" class="task-page-header__eyebrow">{{ eyebrow }}</span>
      <div class="task-page-header__title">
        <slot name="title-prefix" />
        <TitleHelp v-if="help || (descriptionMode !== 'inline' && description)" :text="help || description" :label="title" />
        <h1>{{ title }}</h1>
        <slot name="title-suffix" />
      </div>
      <p v-if="description && descriptionMode === 'inline'">{{ description }}</p>
    </div>
    <div class="task-page-header__actions">
      <slot name="status" />
      <slot name="actions" />
    </div>
    <div v-if="$slots.meta" class="task-page-header__meta">
      <slot name="meta" />
    </div>
  </header>
</template>

<style scoped>
.task-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}

/* 只有使用 meta 槽位的页面才让头部换行，其他页面保持原有单行布局。 */
.task-page-header--with-meta {
  flex-wrap: wrap;
  row-gap: 8px;
}

.task-page-header__copy {
  min-width: 0;
}

.task-page-header__title {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  width: fit-content;
  max-width: 100%;
  padding-right: 14px;
}

.task-page-header__eyebrow {
  display: block;
  margin-bottom: 6px;
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: .04em;
}

h1 {
  /* 全站标题服务于工作内容，压低展示型字号并留出表格与编辑空间。 */
  min-width: 0;
  margin: 0;
  color: var(--do-ink);
  font-size: clamp(23px, 1.8vw, 28px);
  font-weight: 650;
  letter-spacing: -.025em;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.task-page-header__meta {
  flex: 1 0 100%;
  min-width: 0;
}

p {
  max-width: 720px;
  margin: 8px 0 0;
  color: var(--do-muted);
  font-size: 14px;
  line-height: 1.6;
}

.task-page-header__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

@media (max-width: 900px) {
  .task-page-header { flex-wrap: wrap; }
  .task-page-header__actions { justify-content: flex-start; }
}

</style>
