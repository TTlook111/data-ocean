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
const readyCount = computed(() => steps.value.filter(step => step.ready).length)
const allReady = computed(() => steps.value.length > 0 && readyCount.value === steps.value.length)
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
    <header>
      <div class="preparation-path__heading">
        <span class="section-kicker">准备路径</span>
        <div class="preparation-path__title-row">
          <h2>每一项，为问数提供什么</h2>
          <span class="preparation-path__progress" :class="{ 'is-complete': allReady }">{{ readyCount }} / {{ steps.length }} 已具备</span>
        </div>
      </div>
      <p>各项分别核验，不是一组自动完成的任务。</p>
    </header>
    <ol>
      <li v-for="(step, index) in steps" :key="step.key" :data-stage="step.key">
        <span class="preparation-path__number" :class="{ ready: step.ready }" aria-hidden="true">
          <Check v-if="step.ready" :size="14" />
          <span v-else>{{ String(index + 1).padStart(2, '0') }}</span>
        </span>
        <div class="preparation-path__body">
          <div class="preparation-path__title">
            <h3>{{ step.title }}</h3>
            <span class="preparation-path__state" :class="step.ready ? 'is-ready' : 'is-pending'">{{ step.state }}</span>
          </div>
          <p>{{ step.description }}</p>
          <small v-if="step.evidence">{{ step.evidence }}</small>
        </div>
        <RouterLink v-if="destination(step.key)" class="preparation-path__link" :to="destination(step.key)!.to">
          {{ destination(step.key)!.label }}<ArrowUpRight :size="13" aria-hidden="true" />
        </RouterLink>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.preparation-path { min-width: 0; border: 1px solid var(--do-line); border-radius: 12px; background: var(--do-surface); box-shadow: var(--do-shadow); }
header { display: flex; align-items: end; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 18px 22px 14px; border-bottom: 1px solid var(--do-line); }
.preparation-path__heading { min-width: 0; }
.section-kicker { color: var(--do-primary-strong); font-size: 11px; font-weight: 700; letter-spacing: .04em; }
.preparation-path__title-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 6px; }
h2 { margin: 0; color: var(--do-ink); font-size: 17px; font-weight: 650; line-height: 1.35; }
.preparation-path__progress { padding: 2px 9px; border: 1px solid var(--do-line); border-radius: 9999px; color: var(--do-muted); background: var(--do-soft); font-size: 11px; font-weight: 600; line-height: 1.6; font-variant-numeric: tabular-nums; white-space: nowrap; }
.preparation-path__progress.is-complete { border-color: transparent; color: var(--do-success); background: var(--do-success-soft); }
header p { max-width: 230px; margin: 0; color: var(--do-muted); font-size: 11.5px; line-height: 1.6; }
ol { margin: 0; padding: 0; list-style: none; }
li { position: relative; display: grid; grid-template-columns: 26px minmax(0, 1fr) auto; align-items: start; gap: 12px; padding: 15px 22px; border-bottom: 1px solid var(--do-line); transition: background .15s ease; }
li::before { position: absolute; top: 9px; bottom: 9px; left: 0; width: 2px; border-radius: 2px; background: var(--do-primary); opacity: 0; content: ''; transition: opacity .15s ease; }
li:hover { background: color-mix(in srgb, var(--do-primary-soft) 24%, transparent); }
li:hover::before { opacity: 1; }
li:last-child { border-bottom: 0; }
.preparation-path__number { display: grid; place-items: center; width: 26px; height: 26px; margin-top: 1px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-muted); background: var(--do-soft); font-size: 10.5px; font-variant-numeric: tabular-nums; letter-spacing: .02em; }
.preparation-path__number.ready { border-color: transparent; color: var(--do-success); background: var(--do-success-soft); }
.preparation-path__body { min-width: 0; }
.preparation-path__title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
h3 { margin: 0; color: var(--do-ink); font-size: 13.5px; font-weight: 650; line-height: 1.5; }
.preparation-path__state { padding: 1px 8px; border: 1px solid var(--do-line); border-radius: 9999px; color: var(--do-muted); background: var(--do-soft); font-size: 11px; line-height: 1.6; white-space: nowrap; }
.preparation-path__state.is-ready { border-color: transparent; color: var(--do-success); background: var(--do-success-soft); }
.preparation-path__body p { margin: 4px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
small { display: block; margin-top: 4px; color: var(--do-muted); font-size: 11px; line-height: 1.55; overflow-wrap: anywhere; }
.preparation-path__link { display: inline-flex; align-items: center; gap: 3px; min-height: 26px; padding: 2px 8px; border-radius: 6px; color: var(--do-primary-strong); font-size: 12px; white-space: nowrap; transition: background .15s ease; }
.preparation-path__link:hover { background: var(--do-primary-soft); }
@media (max-width: 900px) {
  header { padding: 16px 18px 14px; }
  li { grid-template-columns: 26px minmax(0, 1fr); padding: 14px 18px; }
  .preparation-path__link { grid-column: 2; justify-self: start; margin-top: 2px; }
}
</style>
