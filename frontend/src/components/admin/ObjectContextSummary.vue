<script setup lang="ts">
import { ArrowLeft, LockKeyhole } from 'lucide-vue-next'

defineProps<{
  title: string
  description?: string
  backTo: string
  sourceLabel?: string
}>()
</script>

<template>
  <section class="object-context-summary">
    <RouterLink class="object-context-summary__back" :to="backTo">
      <ArrowLeft :size="16" />
      返回上一级
    </RouterLink>
    <div class="object-context-summary__main">
      <LockKeyhole :size="18" />
      <div>
        <strong>{{ title }}</strong>
        <span v-if="description">{{ description }}</span>
      </div>
    </div>
    <small v-if="sourceLabel">来源：{{ sourceLabel }}</small>
  </section>
</template>

<style scoped>
.object-context-summary {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 18px;
  padding: 12px 14px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-surface);
}

.object-context-summary__back {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 800;
}

.object-context-summary__main {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  color: var(--do-primary-strong);
}

.object-context-summary__main div {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.object-context-summary__main strong {
  overflow: hidden;
  color: var(--do-ink);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.object-context-summary__main span,
.object-context-summary small {
  color: var(--do-muted);
  font-size: 12px;
}

.object-context-summary small {
  margin-left: auto;
}

</style>
