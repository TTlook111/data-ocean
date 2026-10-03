<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, ChevronDown, Database, RefreshCw } from 'lucide-vue-next'
import type { DatasourceReadiness } from '../../api/admin/datasource'
import type { IamS1DatasourceRef } from '../../api/iamS1'

const props = defineProps<{
  datasources: IamS1DatasourceRef[]
  readinessMap: Record<number, DatasourceReadiness>
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
const selectedDatasource = computed(() => props.datasources.find((item) => item.id === props.selectedId))
const selectedReadiness = computed(() => props.selectedId ? props.readinessMap[props.selectedId] : undefined)

function chooseDatasource(id: number) {
  emit('select', id)
  open.value = false
}
</script>

<template>
  <div class="datasource-picker">
    <button
      class="datasource-trigger"
      type="button"
      :aria-expanded="open"
      aria-controls="query-datasource-options"
      aria-label="选择数据源"
      @click="open = !open"
    >
      <Database :size="17" aria-hidden="true" />
      <span class="source-copy">
        <small>数据源</small>
        <strong>{{ selectedDatasource?.name || '选择数据源' }}</strong>
      </span>
      <span v-if="selectedDatasource" class="source-readiness" :class="{ ready: selectedReadiness?.askable }">
        <i aria-hidden="true"></i>{{ selectedReadiness?.askable ? '已就绪' : (selectedReadiness?.stageLabel || '状态确认中') }}
      </span>
      <ChevronDown :size="16" class="source-chevron" aria-hidden="true" />
    </button>

    <div v-if="open" id="query-datasource-options" class="datasource-menu" role="group" aria-label="可用数据源">
      <div v-if="loading && !datasources.length" class="menu-state" role="status">正在加载数据源…</div>
      <div v-else-if="errorMessage" class="menu-state error">
        <span>{{ errorMessage }}</span>
        <button type="button" @click="emit('refresh')">重试</button>
      </div>
      <div v-else-if="!datasources.length" class="menu-state">暂无可用数据源</div>
      <template v-else>
        <button
          v-for="datasource in datasources"
          :key="datasource.id"
          type="button"
          class="datasource-option"
          :class="{ active: datasource.id === selectedId }"
          :aria-current="datasource.id === selectedId ? 'true' : undefined"
          @click="chooseDatasource(datasource.id)"
        >
          <Database :size="16" aria-hidden="true" />
          <span><strong>{{ datasource.name }}</strong><small>{{ readinessMap[datasource.id]?.askable ? '可询问' : (readinessMap[datasource.id]?.stageLabel || '状态确认中') }}</small></span>
          <Check v-if="datasource.id === selectedId" :size="16" aria-hidden="true" />
        </button>
      </template>
      <button class="refresh-option" type="button" :disabled="loading || readinessLoading" @click="emit('refresh')">
        <RefreshCw :size="14" :class="{ spinning: loading || readinessLoading }" aria-hidden="true" />刷新数据源状态
      </button>
    </div>
  </div>
</template>

<style scoped>
.datasource-picker { position: relative; min-width: 0; }
.datasource-trigger { min-height: 46px; max-width: min(500px, 48vw); display: flex; align-items: center; gap: 10px; padding: 6px 11px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); color: var(--do-ink); background: var(--do-surface); text-align: left; cursor: pointer; }
.datasource-trigger:hover, .datasource-trigger[aria-expanded='true'] { border-color: var(--do-primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--do-primary) 12%, transparent); }
.datasource-trigger > svg:first-child { flex: 0 0 auto; color: var(--do-primary-strong); }
.source-copy { min-width: 0; display: grid; gap: 2px; }
.source-copy small { color: var(--do-muted); font-size: 11px; }
.source-copy strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.source-readiness { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 5px; color: var(--do-muted); font-size: 11px; }
.source-readiness i { width: 7px; height: 7px; border-radius: 50%; background: var(--do-warning); }
.source-readiness.ready i { background: var(--do-success); }
.source-chevron { flex: 0 0 auto; color: var(--do-muted); }
.datasource-menu { position: absolute; top: calc(100% + 6px); left: 0; z-index: 80; width: min(420px, calc(100vw - 24px)); max-height: min(360px, 60dvh); display: grid; gap: 4px; overflow-y: auto; padding: 7px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); box-shadow: var(--do-shadow-lg); }
.datasource-option { min-height: 48px; display: grid; grid-template-columns: 24px minmax(0, 1fr) 18px; align-items: center; gap: 8px; padding: 7px 9px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-ink); background: transparent; text-align: left; cursor: pointer; }
.datasource-option:hover, .datasource-option.active { background: var(--do-primary-soft); }
.datasource-option > svg { color: var(--do-primary-strong); }
.datasource-option span { min-width: 0; }
.datasource-option strong, .datasource-option small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.datasource-option strong { font-size: 13px; }
.datasource-option small { margin-top: 3px; color: var(--do-muted); font-size: 11px; }
.menu-state { padding: 10px; color: var(--do-muted); font-size: 12px; }
.menu-state.error { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--do-danger); }
.menu-state button, .refresh-option { min-height: 40px; border: 0; border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: transparent; cursor: pointer; }
.menu-state button { padding: 0 10px; }
.refresh-option { display: flex; align-items: center; gap: 7px; padding: 0 10px; border-top: 1px solid var(--do-line); font: inherit; font-size: 12px; text-align: left; }
.refresh-option:hover:not(:disabled) { background: var(--do-bg); }
.refresh-option:disabled { cursor: wait; opacity: .55; }
.spinning { animation: spin 800ms linear infinite; }
.datasource-trigger:focus-visible, .datasource-option:focus-visible, .menu-state button:focus-visible, .refresh-option:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 768px) {
  .datasource-trigger { width: 100%; max-width: none; min-height: 44px; }
  .source-readiness { max-width: 86px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
@media (prefers-reduced-motion: reduce) { .spinning { animation-duration: 2s; } }
</style>
