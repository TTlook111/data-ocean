<script setup lang="ts">
import type { SnapshotDetail } from '../../api/admin/metadata'
import LoadingState from '../common/LoadingState.vue'
import ErrorState from '../common/ErrorState.vue'
import EmptyState from '../common/EmptyState.vue'
import SnapshotDetailContent from './SnapshotDetailContent.vue'

defineProps<{
  detail: SnapshotDetail | null
  loading: boolean
  error: string
  datasourceName?: string
  showDatasource?: boolean
}>()

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="!detail && datasourceName" class="snapshot-detail-panel__source">
    <span>数据源</span>
    <strong :title="datasourceName" tabindex="0">{{ datasourceName }}</strong>
  </div>
  <LoadingState v-if="loading" variant="skeleton" :rows="5" text="正在读取快照详情…" />
  <ErrorState v-else-if="error" :message="error" @retry="emit('retry')" />
  <SnapshotDetailContent v-else-if="detail" :detail="detail" :show-datasource="showDatasource" />
  <EmptyState v-else message="未获取到快照详情" />
</template>

<style scoped>
.snapshot-detail-panel__source { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 12px; min-width: 0; margin-bottom: 12px; }
.snapshot-detail-panel__source > span { color: var(--do-muted); font-size: 12px; }
.snapshot-detail-panel__source > strong { min-width: 0; color: var(--do-ink); font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.snapshot-detail-panel__source > strong:focus-visible { border-radius: 3px; outline: 2px solid color-mix(in srgb, var(--do-primary) 42%, transparent); outline-offset: 3px; }
</style>
