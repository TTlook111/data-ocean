<script setup lang="ts">
import { Check } from 'lucide-vue-next'

defineProps<{
  steps: Array<{ key: string; label: string; done?: boolean; current?: boolean; disabled?: boolean }>
}>()
</script>

<template>
  <ol class="lifecycle-stepper" aria-label="业务生命周期">
    <li v-for="(step, index) in steps" :key="step.key" :class="{ done: step.done, current: step.current, disabled: step.disabled }">
      <span class="lifecycle-stepper__marker">
        <Check v-if="step.done" :size="14" />
        <span v-else>{{ index + 1 }}</span>
      </span>
      <span>{{ step.label }}</span>
    </li>
  </ol>
</template>

<style scoped>
.lifecycle-stepper {
  display: flex;
  align-items: flex-start;
  margin: 0;
  padding: 0;
  list-style: none;
}

li {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--do-muted);
  font-size: 12px;
  text-align: center;
}

li::before {
  position: absolute;
  top: 14px;
  right: 50%;
  width: 100%;
  height: 1px;
  background: var(--do-line);
  content: '';
}

li:first-child::before {
  display: none;
}

li.done,
li.current {
  color: var(--do-primary-strong);
  font-weight: 800;
}

li.done::before {
  background: var(--do-primary);
}

.lifecycle-stepper__marker {
  z-index: 1;
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid var(--do-line-strong);
  border-radius: 50%;
  background: var(--do-surface);
}

.done .lifecycle-stepper__marker,
.current .lifecycle-stepper__marker {
  border-color: var(--do-primary);
  color: #fff;
  background: var(--do-primary);
}

.disabled {
  opacity: .55;
}
</style>
