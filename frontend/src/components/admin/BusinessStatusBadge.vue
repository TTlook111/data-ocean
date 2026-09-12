<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  status?: string | number | boolean | null
  label?: string
}>(), {
  status: '',
})

/**
 * 通用状态中文映射（兜底用）。
 *
 * **与 `utils/enumLabels.ts` 的域映射必须保持一致**——同一个状态在全站不能有两种中文
 * （`实施任务清单` UI-11「相同状态在全站含义一致」、开发指导 §11.3）。
 *
 * 注意 `APPROVED` 的语义随域不同：快照域是「已审核」（待发布），知识域是「已批准」。
 * 通用表无法同时满足两者，因此**渲染域状态时调用方必须显式传 `label`**
 * （例如 `:label="snapshotStatusLabel(row.status)"`），通用表只作为无域上下文时的兜底。
 */
const labels: Record<string, string> = {
  DRAFT: '草稿', CHECKING: '校验中', ISSUE_FOUND: '存在问题', APPROVED: '已批准',
  PUBLISHED: '已发布', EXPIRED: '已过期', PENDING: '待处理', RUNNING: '进行中',
  SUCCESS: '成功', FAILED: '失败', OPEN: '待处理', CONFIRMED: '已确认',
  RESOLVED: '已解决', REJECTED: '已拒绝', REOPENED: '已重新打开', AUTO_CLOSED: '自动关闭',
  PENDING_REVIEW: '待审核', INDEXING: '索引中', ENABLED: '已启用', DISABLED: '已停用',
  ACTIVE: '启用', INACTIVE: '停用', HEALTHY: '连接正常', UNHEALTHY: '连接异常',
}

const tones: Record<string, string> = {
  PUBLISHED: 'success', APPROVED: 'success', SUCCESS: 'success', RESOLVED: 'success',
  HEALTHY: 'success', ENABLED: 'success', ACTIVE: 'success', CHECKING: 'info',
  RUNNING: 'info', INDEXING: 'info', PENDING_REVIEW: 'warning', PENDING: 'warning',
  ISSUE_FOUND: 'danger', FAILED: 'danger', OPEN: 'danger', REOPENED: 'danger',
  REJECTED: 'danger', UNHEALTHY: 'danger', EXPIRED: 'muted', DRAFT: 'muted',
  DISABLED: 'muted', INACTIVE: 'muted',
  // 治理状态（`utils/enumLabels.ts` 的 governanceStatusLabels）。
  // 缺了这六项时全部落到 muted 灰，「禁止使用/已废弃」与「正常可用」外观完全一致，
  // 只能靠读文字区分——治理页最需要一眼看出的正是这组差异。
  NORMAL: 'success', RECOMMENDED: 'info',
  DISCOVERED: 'warning', SENSITIVE: 'warning',
  DEPRECATED: 'danger', BLOCKED: 'danger',
}

const key = computed(() => String(props.status ?? ''))
const text = computed(() => props.label || labels[key.value] || key.value || '未设置')
const tone = computed(() => tones[key.value] || 'muted')
</script>

<template>
  <span class="business-status-badge" :class="'business-status-badge--' + tone">
    <i aria-hidden="true"></i>
    {{ text }}
    <small v-if="key && text !== key">{{ key }}</small>
  </span>
</template>

<style scoped>
.business-status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 24px;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.business-status-badge i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.business-status-badge small {
  color: inherit;
  font-size: 10px;
  font-weight: 600;
  opacity: .7;
}

.business-status-badge--success { color: var(--do-success); background: var(--do-success-soft); }
.business-status-badge--info { color: var(--do-info); background: var(--do-info-soft); }
.business-status-badge--warning { color: var(--do-warning); background: var(--do-warning-soft); }
.business-status-badge--danger { color: var(--do-danger); background: var(--do-danger-soft); }
.business-status-badge--muted { color: var(--do-muted); background: var(--do-bg); }
</style>
