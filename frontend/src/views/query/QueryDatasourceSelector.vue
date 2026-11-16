<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, ChevronDown, Database, RefreshCw } from 'lucide-vue-next'
import type { DatasourceReadiness } from '../../api/admin/datasource'
import type { IamS1DatasourceRef } from '../../api/iamS1'

const props = defineProps<{
  datasources: IamS1DatasourceRef[]
  readinessMap: Record<number, DatasourceReadiness>
  readinessErrors?: Record<number, string>
  selectedId: number | undefined
  loading: boolean
  readinessLoading: boolean
  errorMessage: string
}>()

const emit = defineEmits<{
  select: [id: number]
  refresh: []
}>()

const open = ref(false)
const triggerRef = ref<HTMLButtonElement>()
const menuRef = ref<HTMLElement>()
const menuWidth = ref(320)
const selectedDatasource = computed(() => props.datasources.find((item) => item.id === props.selectedId))
const selectedReadiness = computed(() => props.selectedId ? props.readinessMap[props.selectedId] : undefined)
const stateLabel = (id: number) => props.readinessLoading ? '状态确认中' : props.readinessMap[id]?.askable ? '已就绪' : props.readinessErrors?.[id] ? (props.readinessErrors[id].includes('无权') ? '无权读取状态' : '状态读取失败') : (props.readinessMap[id]?.stageLabel || (props.readinessLoading ? '状态确认中' : '状态未确认'))

/** 菜单以选择器左边缘为锚点；按剩余视口宽度收敛，避免窄屏向右溢出。 */
function syncMenuWidth() {
  const rect = triggerRef.value?.getBoundingClientRect()
  if (!rect) return
  const available = window.innerWidth - rect.left - 12
  menuWidth.value = Math.max(180, Math.min(320, Math.floor(available)))
}

function optionElements() {
  if (!menuRef.value) return []
  return Array.from(menuRef.value.querySelectorAll<HTMLButtonElement>('.datasource-option'))
}

function focusOption(position: 'selected' | 'first' | 'last' = 'selected') {
  const options = optionElements()
  if (!options.length) {
    menuRef.value?.focus()
    return
  }
  const selected = options.find((option) => option.getAttribute('aria-selected') === 'true')
  const target = position === 'first'
    ? options[0]
    : position === 'last'
      ? options[options.length - 1]
      : (selected || options[0])
  target.focus()
}

function handleShow() {
  syncMenuWidth()
  void nextTick(() => focusOption('selected'))
}

function closeMenu(restoreFocus = true) {
  open.value = false
  if (restoreFocus) void nextTick(() => triggerRef.value?.focus())
}

function chooseDatasource(id: number) {
  emit('select', id)
  closeMenu()
}

function handleTriggerKeydown(event: KeyboardEvent) {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
  event.preventDefault()
  syncMenuWidth()
  open.value = true
  void nextTick(() => focusOption(event.key === 'ArrowUp' ? 'last' : 'selected'))
}

function handleMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.stopPropagation()
    closeMenu()
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const options = optionElements()
  if (!options.length) return
  event.preventDefault()
  const current = options.indexOf(document.activeElement as HTMLButtonElement)
  let nextIndex: number
  if (event.key === 'Home') nextIndex = 0
  else if (event.key === 'End') nextIndex = options.length - 1
  else if (event.key === 'ArrowDown') nextIndex = current < 0 ? 0 : (current + 1) % options.length
  else nextIndex = current <= 0 ? options.length - 1 : current - 1
  options[nextIndex].focus()
}

function handleResize() {
  if (open.value) open.value = false
  syncMenuWidth()
}

onMounted(() => {
  syncMenuWidth()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => window.removeEventListener('resize', handleResize))
</script>

<template>
  <div class="datasource-picker">
    <el-popover
      v-model:visible="open"
      placement="bottom-start"
      trigger="click"
      effect="light"
      :width="menuWidth"
      :offset="6"
      :show-arrow="false"
      :teleported="true"
      :persistent="false"
      popper-class="query-datasource-popper"
      @show="handleShow"
    >
      <template #reference>
        <!-- 包一层，避免 Element Plus 触发器的 aria 合并覆盖按钮自身的 listbox 语义。 -->
        <span class="datasource-trigger-wrap">
          <button
            ref="triggerRef"
            class="datasource-trigger"
            type="button"
            :aria-expanded="open"
            aria-haspopup="listbox"
            aria-controls="query-datasource-options"
            :aria-label="selectedDatasource ? `选择数据源，当前为 ${selectedDatasource.name}，${stateLabel(selectedDatasource.id)}` : '选择数据源'"
            @click="syncMenuWidth"
            @keydown="handleTriggerKeydown"
          >
            <Database :size="16" aria-hidden="true" />
            <strong class="source-name" :title="selectedDatasource?.name">{{ selectedDatasource?.name || '选择数据源' }}</strong>
            <span v-if="selectedDatasource" class="source-readiness" :class="{ ready: selectedReadiness?.askable }">
              <i aria-hidden="true"></i>{{ stateLabel(selectedDatasource.id) }}
            </span>
            <ChevronDown :size="15" class="source-chevron" aria-hidden="true" />
          </button>
        </span>
      </template>

      <div ref="menuRef" class="datasource-menu" tabindex="-1" @keydown="handleMenuKeydown">
        <div v-if="loading && !datasources.length" class="menu-state" role="status">正在加载数据源…</div>
        <div v-else-if="errorMessage" class="menu-state error" role="alert">
          <span>{{ errorMessage }}</span>
          <button type="button" @click="emit('refresh')">重试</button>
        </div>
        <div v-else-if="!datasources.length" class="menu-state">暂无可用数据源</div>
        <div v-else id="query-datasource-options" class="datasource-options" role="listbox" aria-label="可用数据源">
          <button
            v-for="datasource in datasources"
            :key="datasource.id"
            type="button"
            class="datasource-option"
            :class="{ active: datasource.id === selectedId }"
            role="option"
            :aria-selected="datasource.id === selectedId"
            :tabindex="-1"
            @click="chooseDatasource(datasource.id)"
          >
            <Database :size="15" aria-hidden="true" />
            <span class="option-name" :title="datasource.name">{{ datasource.name }}</span>
            <span class="option-status" :class="{ ready: readinessMap[datasource.id]?.askable }">
              {{ stateLabel(datasource.id) }}
            </span>
            <Check v-if="datasource.id === selectedId" :size="15" class="option-check" aria-hidden="true" />
            <span v-else class="option-check-spacer" aria-hidden="true"></span>
          </button>
        </div>
        <button class="refresh-option" type="button" :disabled="loading || readinessLoading" @click="emit('refresh')">
          <RefreshCw :size="14" :class="{ spinning: loading || readinessLoading }" aria-hidden="true" />刷新数据源状态
        </button>
      </div>
    </el-popover>
  </div>
</template>

<style scoped>
.datasource-picker { position: relative; min-width: 0; }
.datasource-trigger-wrap { min-width: 0; max-width: 100%; display: flex; }
.datasource-trigger { min-height: 38px; max-width: min(360px, 42vw); display: flex; align-items: center; gap: 8px; padding: 4px 9px; border: 1px solid var(--do-line); border-radius: 9px; color: var(--do-ink); background: rgba(255, 255, 255, .9); text-align: left; cursor: pointer; transition: border-color var(--do-transition-fast), background var(--do-transition-fast), box-shadow var(--do-transition-fast); }
.datasource-trigger:hover { border-color: var(--do-line-strong); background: #fff; }
.datasource-trigger[aria-expanded='true'] { border-color: color-mix(in srgb, var(--do-primary) 42%, var(--do-line)); background: #fff; box-shadow: 0 1px 4px rgba(33, 78, 140, .08); }
.datasource-trigger:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 32%, transparent); outline-offset: 2px; border-color: var(--do-primary); }
.datasource-trigger > svg:first-child { flex: 0 0 auto; color: var(--do-primary-strong); }
.source-name { min-width: 0; overflow: hidden; font-size: 12.5px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.source-readiness { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 4px; padding: 2px 7px; border-radius: var(--do-radius-full); color: color-mix(in srgb, var(--do-warning) 65%, var(--do-ink)); background: var(--do-warning-soft); font-size: 10.5px; font-weight: 650; white-space: nowrap; }
.source-readiness i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.source-readiness.ready { color: color-mix(in srgb, var(--do-success) 70%, var(--do-ink)); background: var(--do-success-soft); }
.source-chevron { flex: 0 0 auto; color: var(--do-muted); transition: transform var(--do-transition-fast); }
.datasource-trigger[aria-expanded='true'] .source-chevron { transform: rotate(180deg); }

.datasource-menu { display: grid; gap: 4px; min-width: 0; outline: none; }
.datasource-options { min-height: 0; max-height: min(320px, 55dvh); display: grid; align-content: start; gap: 2px; overflow-y: auto; overscroll-behavior: contain; padding-right: 2px; scrollbar-width: thin; scrollbar-color: color-mix(in srgb, var(--do-primary) 24%, transparent) transparent; }
.datasource-options::-webkit-scrollbar { width: 5px; }
.datasource-options::-webkit-scrollbar-track { background: transparent; }
.datasource-options::-webkit-scrollbar-thumb { border-radius: var(--do-radius-full); background: color-mix(in srgb, var(--do-primary) 22%, transparent); }
.datasource-option { min-width: 0; min-height: 44px; display: grid; grid-template-columns: 20px minmax(0, 1fr) auto 16px; align-items: center; gap: 6px; padding: 7px 8px; border: 1px solid transparent; border-radius: 8px; color: var(--do-ink); background: transparent; text-align: left; cursor: pointer; font: inherit; }
.datasource-option:hover { background: color-mix(in srgb, var(--do-primary) 5%, white); }
.datasource-option:focus-visible { outline: 2px solid var(--do-primary); outline-offset: -2px; background: var(--do-primary-soft); }
.datasource-option.active { border-color: color-mix(in srgb, var(--do-primary) 20%, transparent); background: color-mix(in srgb, var(--do-primary) 8%, white); }
.datasource-option > svg:first-child { color: var(--do-primary-strong); }
.option-name { min-width: 0; overflow: hidden; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.option-status { justify-self: end; padding: 2px 7px; border-radius: var(--do-radius-full); color: color-mix(in srgb, var(--do-warning) 65%, var(--do-ink)); background: var(--do-warning-soft); font-size: 10.5px; font-weight: 650; white-space: nowrap; }
.option-status.ready { color: color-mix(in srgb, var(--do-success) 70%, var(--do-ink)); background: var(--do-success-soft); }
.option-check { color: var(--do-primary-strong); }
.option-check-spacer { width: 15px; height: 1px; }
.menu-state { padding: 10px; color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.menu-state.error { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--do-danger); }
.menu-state button { min-height: 32px; padding: 0 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.refresh-option { min-height: 38px; display: flex; align-items: center; gap: 7px; margin-top: 2px; padding: 0 9px; border: 0; border-top: 1px solid var(--do-line); border-radius: 0 0 8px 8px; color: var(--do-primary-strong); background: transparent; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.refresh-option:hover:not(:disabled) { background: var(--do-primary-soft); }
.refresh-option:focus-visible { outline: 2px solid var(--do-primary); outline-offset: -2px; }
.refresh-option:disabled { cursor: wait; opacity: .55; }
.menu-state button:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
.spinning { animation: spin 800ms linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 768px) {
  .datasource-trigger-wrap { width: 100%; }
  .datasource-trigger { width: 100%; max-width: none; min-height: 42px; }
  .source-name { flex: 1 1 auto; }
  .source-readiness { max-width: 96px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
@media (prefers-reduced-motion: reduce) { .spinning { animation-duration: 2s; } }
</style>

<style>
/* 浮层挂载到 body，复用 Element Plus 的 popper 层级；仅保留清晰的白色面板。 */
.query-datasource-popper.el-popover.el-popper {
  max-width: calc(100vw - 24px);
  padding: 6px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  color: var(--do-ink);
  background: rgba(255, 255, 255, .985);
  box-shadow: 0 6px 18px rgba(33, 78, 140, .08);
  backdrop-filter: blur(14px) saturate(120%);
  -webkit-backdrop-filter: blur(14px) saturate(120%);
}
</style>
