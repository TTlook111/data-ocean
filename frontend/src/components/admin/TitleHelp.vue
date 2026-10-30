<script setup lang="ts">
import { ref } from 'vue'
import { ElTooltip, type TooltipInstance } from 'element-plus'
import { CircleHelp } from 'lucide-vue-next'

defineProps<{ text?: string; label: string }>()
const visible = ref(false)
const tooltip = ref<TooltipInstance>()

function dismiss() {
  tooltip.value?.onClose()
  visible.value = false
}
</script>

<template>
  <ElTooltip
    v-if="text"
    ref="tooltip"
    v-model:visible="visible"
    :content="text"
    :trigger="['hover', 'focus']"
    :trigger-keys="[]"
    :show-after="120"
    :hide-after="150"
    :persistent="false"
    :teleported="true"
    :enterable="true"
    effect="light"
    placement="top-start"
    popper-class="title-help-tooltip"
  >
    <button
      class="title-help"
      type="button"
      :aria-label="`查看${label}说明`"
      @click="visible = true"
      @keydown.esc.stop.prevent="dismiss"
    >
      <CircleHelp :size="14" aria-hidden="true" />
    </button>
  </ElTooltip>
</template>

<style scoped>
.title-help {
  position: absolute;
  top: -11px;
  right: -7px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--do-muted);
  background: transparent;
  opacity: .7;
  cursor: help;
  transition: color 150ms ease, opacity 150ms ease;
}
.title-help:hover, .title-help:focus-visible {
  color: var(--do-primary-strong);
  opacity: 1;
}
.title-help:focus-visible {
  outline: 2px solid var(--do-primary);
  outline-offset: 1px;
}
@media (pointer: coarse) {
  .title-help { top: -21px; right: -17px; width: 44px; height: 44px; }
}
@media (prefers-reduced-motion: reduce) {
  .title-help { transition: none; }
}
</style>

<style>
.title-help-tooltip.el-popper {
  max-width: min(340px, calc(100vw - 32px));
  padding: 11px 14px;
  border-color: var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-ink);
  background: var(--do-surface);
  box-shadow: var(--do-shadow-lg);
  font-size: 13px;
  font-weight: 400;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.title-help-tooltip.el-popper .el-popper__arrow::before {
  border-color: var(--do-line);
  background: var(--do-surface);
}
</style>
