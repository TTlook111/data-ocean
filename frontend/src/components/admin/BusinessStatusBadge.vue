<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  status?: string | number | boolean | null
  label?: string
}>(), {
  status: '',
})

const labels: Record<string, string> = {
  DRAFT: '草稿', CHECKING: '检查中', ISSUE_FOUND: '发现问题', APPROVED: '已批准',
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
