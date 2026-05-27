<script setup lang="ts">
import { Download } from 'lucide-vue-next'

const props = defineProps<{
  getDataURL: () => string | null
  disabled?: boolean
}>()

function exportPng() {
  const url = props.getDataURL()
  if (!url) return
  const link = document.createElement('a')
  link.href = url
  link.download = `chart-${Date.now()}.png`
  link.click()
}
</script>

<template>
  <button class="export-btn" :disabled="disabled" @click="exportPng">
    <Download :size="14" />导出 PNG
  </button>
</template>

<style scoped>
.export-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--do-line);
  border-radius: 4px;
  background: var(--do-surface);
  cursor: pointer;
  font-size: 12px;
  color: var(--do-muted);
}
.export-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
