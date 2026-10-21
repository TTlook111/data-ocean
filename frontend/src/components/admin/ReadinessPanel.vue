<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, CheckCircle2, CircleAlert } from 'lucide-vue-next'
import type { DatasourceReadiness } from '../../api/admin/datasource'
import { buildContextQuery } from '../../utils/adminNavigation'
const props = defineProps<{ readiness: DatasourceReadiness; compact?: boolean }>()
const dimensions = computed(() => {
  const context = { datasourceId: props.readiness.datasourceId, snapshotId: props.readiness.publishedSnapshotId }
  const sourceQuery = buildContextQuery('datasource', context)
  return [
    { key: 'connection', label: '连接', ready: props.readiness.connectionReady, description: '数据源连接可用', to: { path: '/admin/data-sources', query: { edit: String(context.datasourceId) } }, action: '连接配置' },
    { key: 'metadata', label: '采集', ready: props.readiness.metadataReady, description: props.readiness.snapshotVersion ? '已发布快照 v' + props.readiness.snapshotVersion : '已发布元数据快照', to: { path: props.readiness.metadataReady ? '/admin/assets' : props.readiness.latestCollectedSnapshotId ? '/admin/releases' : '/admin/collections', query: sourceQuery }, action: props.readiness.metadataReady ? '查看数据资产' : props.readiness.latestCollectedSnapshotId ? '审核并发布' : '去采集' },
    { key: 'governance', label: '治理', ready: props.readiness.governanceReady, description: '已满足治理条件', to: { path: props.readiness.governanceReady ? '/admin/governance' : '/admin/governance/issues', query: buildContextQuery('datasource-snapshot', context) }, action: props.readiness.governanceReady ? '查看治理' : '处理治理问题' },
    { key: 'knowledge', label: '知识', ready: props.readiness.knowledgeReady, description: '问数知识已就绪', to: { path: '/admin/semantics/knowledge', query: sourceQuery }, action: '查看语义知识' },
    { key: 'permission', label: '授权', ready: props.readiness.permissionReady, description: '当前账号已满足数据访问条件', to: { path: '/admin/access/iam', query: sourceQuery }, action: '查看权限' },
  ]
})
</script>
<template>
  <div class="readiness-panel" :class="{ 'readiness-panel--compact': compact }" :aria-label="readiness.datasourceName + '的准备条件'">
    <h2 v-if="!compact">数据源准备情况</h2>
    <ul>
      <li v-for="item in dimensions" :key="item.key" :class="{ ready: item.ready }">
        <component :is="item.ready ? CheckCircle2 : CircleAlert" :size="compact ? 14 : 20" class="readiness-panel__icon" aria-hidden="true" />
        <div class="readiness-panel__copy"><strong>{{ item.label }}</strong><span v-if="!compact">{{ item.ready ? item.description : '尚未满足准备条件，请查看下方待处理事项。' }}</span></div>
        <span class="readiness-panel__state">{{ item.ready ? '已就绪' : '待处理' }}</span>
        <RouterLink v-if="!compact" class="readiness-panel__action" :to="item.to">{{ item.action }}<ArrowRight :size="14" aria-hidden="true" /></RouterLink>
      </li>
    </ul>
  </div>
</template>
<style scoped>
.readiness-panel { padding: 18px 20px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
h2 { margin: 0 0 10px; color: var(--do-ink); font-size: 15px; }
ul { margin: 0; padding: 0; list-style: none; }
li { min-height: 64px; display: grid; grid-template-columns: 20px minmax(0, 1fr) 76px 128px; align-items: center; gap: 10px 14px; padding: 12px 0; border-bottom: 1px solid var(--do-line); }
li:last-child { border-bottom: 0; }
.readiness-panel__icon { color: var(--do-warning); }
.ready .readiness-panel__icon { color: var(--do-success); }
.readiness-panel__copy { display: grid; gap: 3px; min-width: 0; }
.readiness-panel__copy strong { color: var(--do-ink); font-size: 14px; }
.readiness-panel__copy span { color: var(--do-muted); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.readiness-panel__state { justify-self: center; color: var(--do-warning); background: var(--do-warning-soft); border-radius: var(--do-radius-full); padding: 3px 8px; font-size: 11.5px; white-space: nowrap; }
.ready .readiness-panel__state { color: color-mix(in srgb, var(--do-success) 75%, var(--do-ink)); background: var(--do-success-soft); }
.readiness-panel__action { justify-self: end; display: inline-flex; align-items: center; justify-content: flex-end; gap: 4px; min-width: 0; padding: 4px 0; color: var(--do-primary-strong); font-size: 12.5px; white-space: nowrap; }
.readiness-panel--compact { padding: 0; border: 0; background: transparent; }
.readiness-panel--compact ul { display: flex; flex-wrap: wrap; gap: 6px 14px; }
.readiness-panel--compact li { display: inline-flex; align-items: center; gap: 5px; min-height: 24px; padding: 3px 8px; border: 1px solid color-mix(in srgb, var(--do-warning) 26%, var(--do-line)); border-radius: var(--do-radius-full); color: color-mix(in srgb, var(--do-warning) 68%, var(--do-ink)); background: var(--do-warning-soft); }
.readiness-panel--compact li.ready { min-height: 22px; padding: 0; border: 0; border-radius: 0; color: var(--do-ink); background: transparent; }
.readiness-panel--compact .readiness-panel__icon { color: currentColor; }
.readiness-panel--compact li.ready .readiness-panel__icon { color: var(--do-success); }
.readiness-panel--compact .readiness-panel__copy { display: block; }
.readiness-panel--compact .readiness-panel__copy strong { font-size: 11.5px; color: inherit; font-weight: 600; }
.readiness-panel--compact .readiness-panel__state { display: none; }
.readiness-panel--compact li:not(.ready) .readiness-panel__state { display: inline; justify-self: auto; padding: 0; color: inherit; background: transparent; font-size: 11px; font-weight: 500; opacity: .82; }
@media (max-width: 760px) { li { grid-template-columns: 20px minmax(0, 1fr) 76px; gap: 10px 14px; } .readiness-panel__action { grid-column: 2 / -1; justify-self: end; } }
</style>
