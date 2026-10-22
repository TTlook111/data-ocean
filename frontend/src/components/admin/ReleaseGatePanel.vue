<script setup lang="ts">
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { ShieldCheck, ShieldAlert } from 'lucide-vue-next'
import BusinessStatusBadge from './BusinessStatusBadge.vue'
import LoadingState from '../common/LoadingState.vue'
import ErrorState from '../common/ErrorState.vue'
import { snapshotStatusLabel } from '../../utils/enumLabels'
import type { ReleaseGate, ReleaseGateItem } from '../../api/admin/versioning'

const props = withDefaults(defineProps<{
  gate: ReleaseGate | null
  loading?: boolean
  error?: string
  compact?: boolean
  issueTarget?: RouteLocationRaw
}>(), {
  loading: false,
  error: '',
  compact: false,
  issueTarget: undefined,
})

defineEmits<{ retry: [] }>()

const summary = computed(() => {
  const gate = props.gate
  if (!gate) return ''
  if (gate.blockingCount > 0) return `存在 ${gate.blockingCount} 项阻断，暂不可发布`
  if (gate.snapshotStatus !== 'APPROVED' && gate.snapshotStatus !== 'PUBLISHED') {
    return `门禁已通过，当前状态为「${snapshotStatusLabel(gate.snapshotStatus)}」，批准后才能发布`
  }
  if (gate.warningCount > 0) return `可以发布，但有 ${gate.warningCount} 项提示需确认`
  return '门禁已通过，可以发布'
})

const summaryTone = computed(() => {
  const gate = props.gate
  if (!gate) return 'muted'
  if (gate.blockingCount > 0) return 'danger'
  if (gate.snapshotStatus !== 'APPROVED' && gate.snapshotStatus !== 'PUBLISHED') return 'info'
  if (gate.warningCount > 0) return 'warning'
  return 'success'
})

function itemLabel(item: ReleaseGateItem) {
  if (item.status === 'WARN') return '提示（不阻断发布）'
  return undefined
}
</script>

<template>
  <section class="release-gate" :class="{ 'release-gate--compact': compact }" aria-label="发布门禁">
    <header class="release-gate__header">
      <div class="release-gate__title">
        <ShieldCheck v-if="!gate || gate.blockingCount === 0" :size="18" aria-hidden="true" />
        <ShieldAlert v-else :size="18" aria-hidden="true" />
        <h3>发布门禁</h3>
      </div>
      <span v-if="summary" class="release-gate__summary" :class="'release-gate__summary--' + summaryTone">
        {{ summary }}
      </span>
    </header>

    <LoadingState v-if="loading && !gate" text="正在读取发布门禁…" />
    <ErrorState v-else-if="error" :message="error" @retry="$emit('retry')" />
    <ul v-else-if="gate" class="release-gate__items">
      <li
        v-for="item in gate.items"
        :key="item.code"
        class="release-gate__item"
        :class="'release-gate__item--' + item.status.toLowerCase()"
      >
        <div class="release-gate__item-head">
          <BusinessStatusBadge :status="item.status" :label="itemLabel(item)" />
          <span class="release-gate__item-message">{{ item.message }}</span>
        </div>
        <div v-if="item.code === 'SOURCE_HIGH_UNRESOLVED' && item.status === 'FAIL'" class="release-gate__item-actions">
          <RouterLink v-if="issueTarget" :to="issueTarget" class="release-gate__link">去问题中心处理</RouterLink>
        </div>
      </li>
    </ul>
    <p v-else class="release-gate__empty">暂无门禁数据</p>
  </section>
</template>

<style scoped>
.release-gate {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: 14px;
  background: var(--do-surface, #fff);
}

.release-gate--compact {
  gap: 8px;
  padding: 12px;
}

.release-gate__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
}

.release-gate__title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--do-ink);
}

.release-gate__title h3 {
  margin: 0;
  font-size: 14px;
}

.release-gate__summary {
  font-size: 12px;
  font-weight: 700;
}

.release-gate__summary--success { color: var(--do-success); }
.release-gate__summary--warning { color: var(--do-warning); }
.release-gate__summary--danger { color: var(--do-danger); }
.release-gate__summary--info { color: var(--do-muted); }
.release-gate__summary--muted { color: var(--do-muted); }

.release-gate__items {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.release-gate__item {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--do-surface-soft, #f7fafd);
}

.release-gate__item--fail { background: var(--do-danger-soft); }
.release-gate__item--warn { background: var(--do-warning-soft); }
.release-gate__item--pass { background: var(--do-success-soft); }

.release-gate__item-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
}

.release-gate__item-message {
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.5;
}

.release-gate__link {
  color: var(--do-primary, #1d4ed8);
  font-size: 12px;
  font-weight: 700;
}

.release-gate__empty {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
}
</style>
