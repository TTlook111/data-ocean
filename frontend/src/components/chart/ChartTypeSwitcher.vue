<script setup lang="ts">
defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [type: string]
}>()

const types = [
  { value: 'bar', label: '柱状图' },
  { value: 'line', label: '折线图' },
  { value: 'pie', label: '饼图' },
]
</script>

<template>
  <div class="chart-type-switcher">
    <button
      v-for="t in types"
      :key="t.value"
      :class="{ active: modelValue === t.value }"
      @click="emit('update:modelValue', t.value)"
    >{{ t.label }}</button>
  </div>
</template>

<style scoped>
.chart-type-switcher {
  display: flex;
  gap: 4px;
}
.chart-type-switcher button {
  padding: 4px 12px;
  border: 1px solid var(--do-line);
  border-radius: 4px;
  background: var(--do-surface);
  cursor: pointer;
  font-size: 12px;
  color: var(--do-muted);
  transition: all 0.15s;
}
.chart-type-switcher button.active {
  background: var(--do-primary);
  color: #fff;
  border-color: var(--do-primary);
}
</style>
