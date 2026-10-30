<script setup lang="ts">
import { computed } from 'vue'
import { ArrowUpRight, Check } from 'lucide-vue-next'
import type { DatasourceReadiness } from '../../api/admin/datasource'
import { useIamS1Store } from '../../stores/iamS1'
import { buildContextQuery } from '../../utils/adminNavigation'
import { canOpenDatasourceWorkspace, datasourcePreparationFacts } from '../../utils/datasourcePreparation'

const props = defineProps<{ readiness: DatasourceReadiness }>()
const iam = useIamS1Store()
const steps = computed(() => datasourcePreparationFacts(props.readiness))
const destinations: Record<string, { path: string; label: string }> = {
  collection: { path: '/admin/collections', label: '采集记录' },
  governance: { path: '/admin/governance', label: '治理工作区' },
  publication: { path: '/admin/releases', label: '元数据版本' },
  index: { path: '/admin/semantics/knowledge', label: '知识与索引' },
  query: { path: '/admin/access/iam', label: '授权配置' },
}
function destination(key: string) {
  const target = destinations[key]
  if (!target || !canOpenDatasourceWorkspace(target.path, iam, props.readiness.datasourceId)) return undefined
  return { ...target, to: { path: target.path, query: buildContextQuery(key === 'governance' ? 'datasource-snapshot' : 'datasource', {
    datasourceId: props.readiness.datasourceId, snapshotId: props.readiness.publishedSnapshotId,
  }) } }
}
</script>

<template>
  <section class="preparation-path" aria-label="从接入到问数的准备路径">
    <header><div><span class="section-kicker">准备路径</span><h2>每一项，为问数提供什么</h2></div><p>各项分别核验，不是一组自动完成的任务。</p></header>
    <ol>
      <li v-for="(step, index) in steps" :key="step.key" :data-stage="step.key">
        <span class="preparation-path__number" :class="{ ready: step.ready }" aria-hidden="true"><Check v-if="step.ready" :size="15" /><span v-else>{{ index + 1 }}</span></span>
        <div class="preparation-path__body"><div class="preparation-path__title"><h3>{{ step.title }}</h3><span :class="{ ready: step.ready }">{{ step.state }}</span></div><p>{{ step.description }}</p><small v-if="step.evidence">{{ step.evidence }}</small></div>
        <RouterLink v-if="destination(step.key)" :to="destination(step.key)!.to">{{ destination(step.key)!.label }}<ArrowUpRight :size="14" aria-hidden="true" /></RouterLink>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.preparation-path { min-width: 0; border: 1px solid var(--do-line); border-radius: 12px; background: var(--do-surface); }
header { display: flex; align-items: end; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 22px 24px 18px; border-bottom: 1px solid var(--do-line); }
.section-kicker { color: var(--do-primary-strong); font-size: 11px; }
h2 { margin: 7px 0 0; color: var(--do-ink); font-size: 18px; font-weight: 600; }
header p { max-width: 230px; margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
ol { padding: 0 24px; margin: 0; list-style: none; }
li { display: grid; grid-template-columns: 30px minmax(0,1fr) auto; align-items: start; gap: 12px; padding: 18px 0; border-bottom: 1px solid var(--do-line); }
li:last-child { border-bottom: 0; }
.preparation-path__number { display: grid; place-items: center; width: 28px; height: 28px; margin-top: 2px; border: 1px solid var(--do-line-strong); border-radius: 50%; color: var(--do-muted); background: var(--do-soft); font-size: 11px; }
.preparation-path__number.ready { border-color: var(--do-line); color: var(--do-success); background: var(--do-success-soft); }
.preparation-path__body { min-width: 0; }
.preparation-path__title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; min-height: 28px; }
h3 { margin: 0; color: var(--do-ink); font-size: 14px; font-weight: 650; }
.preparation-path__title > span { color: var(--do-muted); font-size: 11px; }
.preparation-path__title > span.ready { color: var(--do-success); }
.preparation-path__body p { margin: 5px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.65; }
small { display: block; margin-top: 5px; color: var(--do-muted); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
li > a { display: flex; align-items: center; gap: 3px; min-height: 36px; color: var(--do-primary-strong); font-size: 12px; white-space: nowrap; }
@media (max-width: 900px) { header { padding: 18px; } ol { padding-inline: 18px; } li { grid-template-columns: 28px minmax(0,1fr); } li > a { grid-column: 2; justify-self: start; min-height: 32px; } }
</style>
