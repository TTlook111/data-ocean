<script setup lang="ts">
import TitleHelp from './TitleHelp.vue'

defineProps<{
  title: string
  description?: string
  eyebrow?: string
  descriptionMode?: 'tooltip' | 'inline'
}>()
</script>

<template>
  <header class="task-page-header">
    <div class="task-page-header__copy">
      <span v-if="eyebrow" class="task-page-header__eyebrow">{{ eyebrow }}</span>
      <div class="task-page-header__title">
        <TitleHelp v-if="descriptionMode !== 'inline'" :text="description" :label="title" />
        <h1>{{ title }}</h1>
      </div>
      <p v-if="description && descriptionMode === 'inline'">{{ description }}</p>
    </div>
    <div class="task-page-header__actions">
      <slot name="status" />
      <slot name="actions" />
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

.task-page-header__copy {
  min-width: 0;
}

.task-page-header__title {
  position: relative;
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
  margin: 0;
  color: var(--do-ink);
  font-size: clamp(24px, 3vw, 32px);
  line-height: 1.2;
  overflow-wrap: anywhere;
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
