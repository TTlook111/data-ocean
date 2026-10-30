<script setup lang="ts">
import { computed } from 'vue'
import type { QualityRuleSummary } from '../../api/admin/governance'
import ErrorState from '../common/ErrorState.vue'
import { rulePlanStatusLabel } from '../../utils/qualityRulePresentation'
const props = defineProps<{ summary?: QualityRuleSummary | null; loading?: boolean; error?: string; compact?: boolean }>()
defineEmits<{ retry: [] }>()
const exceptions = computed(() => props.summary?.rules?.filter(item => item.status !== 'READY' || item.reason) || [])
</script>
<template>
  <section class="rule-applicability" :class="{ 'rule-applicability--compact': compact }" aria-label="检查前适用性核对">
    <header v-if="!compact"><div><span>检查前核对</span><h2>这些规则会检查什么？</h2></div><slot name="actions" /></header>
    <ErrorState v-if="error" :message="error" @retry="$emit('retry')" />
    <p v-else-if="loading" role="status">正在按当前快照核对启用配置、对象与覆盖关系…</p>
    <template v-else-if="summary">
      <div class="rule-applicability__metrics">
        <div><span>已启用候选配置</span><strong>{{ summary.enabledCount }}</strong><small>继承全局 {{ summary.globalCount ?? '—' }} · 本源专用 {{ summary.datasourceCount ?? '—' }}</small></div>
        <div><span>可参与检查</span><strong>{{ summary.applicableCount }}</strong><small>已找到适用对象</small></div>
        <div><span>不适用或被覆盖</span><strong>{{ summary.notApplicableCount ?? '—' }}</strong><small>查看下方具体原因</small></div>
        <div><span>配置异常</span><strong>{{ summary.invalidCount ?? '—' }}</strong><small>需要修正规则配置</small></div>
      </div>
      <details v-if="compact && exceptions.length" class="rule-applicability__exceptions"><summary>展开 {{ exceptions.length }} 条不适用、异常或部分覆盖说明</summary><article v-for="item in exceptions" :key="item.ruleId"><strong>{{ item.ruleName }}</strong><span>{{ rulePlanStatusLabel(item.status) }}</span><p>{{ item.reason || '当前没有可参与检查的目标' }}</p></article></details>
      <div v-if="!compact && exceptions.length" class="rule-applicability__exceptions">
        <article v-for="item in exceptions" :key="item.ruleId"><strong>{{ item.ruleName }}</strong><span>{{ rulePlanStatusLabel(item.status) }}</span><p>{{ item.reason || '当前快照没有可参与检查的目标。' }}<template v-if="item.status === 'READY'"> · 预计覆盖 {{ item.plannedTargetCount }}/{{ item.targetCount }} 个目标</template></p></article>
      </div>
      <p v-else-if="!compact && !summary.rules">当前接口未返回逐条规划明细，不能由数量推断具体原因。</p>
      <p v-else-if="!compact && summary.enabledCount === 0">当前没有启用的候选规则，请先配置规则。</p><p v-else-if="!compact">当前候选配置均已找到适用对象。</p>
      <p v-if="compact" class="rule-applicability__note">按当前快照的全部启用配置只读规划，不随下方筛选和分页变化，尚未执行检查。逐条原因见下表。</p><p v-else class="rule-applicability__note">此处只核对已保存配置与快照结构，不读取业务数据或执行检查。实际执行还需权限和数据源连接可用；业务上是否合适，还需确认对象、阈值、唯一性或更新时间字段的含义。</p>
    </template>
    <p v-else>选择有查看权限的数据源和快照后，核对规则适用性。</p>
  </section>
</template>
<style scoped>
.rule-applicability { display: grid; gap: 16px; min-width: 0; padding: 20px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.rule-applicability header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.rule-applicability header span { color: var(--do-primary-strong); font-size: 11px; }
.rule-applicability h2 { margin: 4px 0 0; color: var(--do-ink); font-size: 17px; }
.rule-applicability--compact { padding: 14px; gap: 10px; background: var(--do-bg); }
.rule-applicability--compact .rule-applicability__metrics strong { font-size: 22px; }
.rule-applicability--compact .rule-applicability__metrics small { font-size: 11px; }
.rule-applicability p { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.7; }
.rule-applicability__metrics { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 16px; }
.rule-applicability__metrics > div { display: grid; gap: 5px; padding-left: 14px; border-left: 2px solid var(--do-line); }
.rule-applicability__metrics span,.rule-applicability__metrics small { color: var(--do-muted); font-size: 12px; }
.rule-applicability__metrics strong { color: var(--do-ink); font-size: 27px; font-variant-numeric: tabular-nums; }
.rule-applicability__exceptions { display: grid; gap: 8px; max-height: 280px; overflow-y: auto; }
.rule-applicability__exceptions summary { color: var(--do-primary-strong); cursor: pointer; font-size: 12px; padding: 6px 0; }
.rule-applicability__exceptions article { padding: 10px 12px; border-radius: var(--do-radius-md); background: var(--do-bg); font-size: 12px; }
.rule-applicability__exceptions strong { color: var(--do-ink); }.rule-applicability__exceptions span { margin-left: 10px; color: var(--do-warning); }
.rule-applicability__note { padding-top: 12px; border-top: 1px solid var(--do-line); }
@media(max-width:760px) { .rule-applicability__metrics { grid-template-columns: repeat(2,minmax(0,1fr)); } .rule-applicability { padding: 16px; } }
</style>
