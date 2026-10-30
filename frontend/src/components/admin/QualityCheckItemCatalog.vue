<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Check, Copy } from 'lucide-vue-next'
import LoadingState from '../common/LoadingState.vue'
import ErrorState from '../common/ErrorState.vue'
import EmptyState from '../common/EmptyState.vue'
import type { QualityRuleParameterDefinition, QualityRuleTemplate } from '../../api/admin/governance'
import { qualityDimensionLabel } from '../../utils/enumLabels'
import { describeRuleCondition, ruleEvidenceLimit, ruleBusinessGuidance, ruleScopeLabel, ruleTargetTypeLabel, sortRuleScopes } from '../../utils/qualityRulePresentation'

const props = withDefaults(defineProps<{
  items: QualityRuleTemplate[]
  loading?: boolean
  error?: string
  selectedCode?: string
  selectable?: boolean
  actionLabel?: string
  disabledReason?: string
}>(), { actionLabel: '使用此检查项', selectable: true })
const emit = defineEmits<{ select: [code: string]; retry: [] }>()
const search = ref('')
const dimension = ref('')
const checkWay = ref('')
const inspectedCode = ref(props.selectedCode || '')
watch(() => props.selectedCode, code => { inspectedCode.value = code || '' })
const dimensions = ['COMPLETENESS', 'ACCURACY', 'CONSISTENCY', 'TIMELINESS', 'TRACEABILITY']
const filteredItems = computed(() => props.items.filter(item => (
  (!dimension.value || item.dimension === dimension.value)
  && (!checkWay.value || item.checkType === checkWay.value)
  && (!search.value.trim() || `${item.name} ${item.code} ${item.description}`.toLowerCase().includes(search.value.trim().toLowerCase()))
)))
const inspected = computed(() => filteredItems.value.find(item => item.code === inspectedCode.value))
const defaultCondition = computed(() => inspected.value ? describeRuleCondition(inspected.value.code,
  Object.fromEntries(inspected.value.parameters.map(parameter => [parameter.key, parameter.defaultValue])),
  inspected.value.parameters, inspected.value.description) : '')
function parameterText(parameter: QualityRuleParameterDefinition) {
  if (parameter.type === 'BOOLEAN') return `${parameter.label}：默认${parameter.defaultValue ? '是' : '否'}`
  return `${parameter.label}：${parameter.type === 'INTEGER' ? '整数' : '数值'} ${parameter.minimum ?? '不限'}–${parameter.maximum ?? '不限'}，默认 ${parameter.defaultValue}`
}
function selectItem() {
  if (props.selectable && inspected.value) emit('select', inspected.value.code)
}
function clearFilters() { search.value = ''; dimension.value = ''; checkWay.value = '' }
async function copyCode() {
  if (!inspected.value) return
  try { await navigator.clipboard.writeText(inspected.value.code); ElMessage.success('检查项编码已复制') }
  catch { ElMessage.warning('复制失败，请选择编码后手动复制') }
}
</script>

<template>
  <section class="quality-check-catalog" aria-label="支持的检查项">
    <div class="quality-check-catalog__filters">
      <label><span>查找检查项</span><el-input v-model="search" clearable :prefix-icon="Search" placeholder="名称、用途或编码" aria-label="查找检查项" /></label>
      <label><span>治理维度</span><el-select v-model="dimension" clearable placeholder="全部维度" aria-label="检查项治理维度"><el-option v-for="value in dimensions" :key="value" :label="qualityDimensionLabel(value)" :value="value" /></el-select></label>
      <label><span>检查方式</span><el-select v-model="checkWay" clearable placeholder="全部方式" aria-label="检查项检查方式"><el-option label="元数据检查" value="SCHEMA" /><el-option label="业务数据（只读）" value="DATA" /></el-select></label>
    </div>
    <ErrorState v-if="error" :message="error" @retry="emit('retry')" />
    <LoadingState v-else-if="loading" text="正在读取支持的检查项…" />
    <template v-else-if="!filteredItems.length">
      <EmptyState :message="items.length ? '没有符合筛选条件的检查项。' : '当前没有可用的检查项。'" />
      <el-button v-if="items.length" @click="clearFilters">清除筛选</el-button>
    </template>
    <template v-else>
      <p class="quality-check-catalog__count" role="status">显示 {{ filteredItems.length }} / {{ items.length }} 项 · 选择一项查看用途与条件</p>
      <div class="quality-check-catalog__body">
        <ul class="quality-check-catalog__list" aria-label="检查项列表">
          <li v-for="item in filteredItems" :key="item.code">
            <button type="button" class="quality-check-catalog__item" :aria-pressed="inspected?.code === item.code" @click="inspectedCode = item.code">
              <span><strong>{{ item.name }}</strong><small>{{ qualityDimensionLabel(item.dimension) }} · {{ item.checkType === 'DATA' ? '业务数据（只读）' : '元数据检查' }} · 检查{{ ruleTargetTypeLabel(item.targetType) }}</small></span>
              <Check v-if="inspected?.code === item.code" :size="16" aria-hidden="true" />
            </button>
          </li>
        </ul>
        <article v-if="inspected" class="quality-check-catalog__detail" aria-label="检查项说明" aria-live="polite">
          <header><span class="quality-check-catalog__eyebrow">检查什么问题</span><h3>{{ inspected.name }}</h3><p>{{ inspected.description }}</p></header>
          <section><h4>什么时候适合使用</h4><p>{{ ruleBusinessGuidance(inspected.code).scenario }}</p><h4>使用前先确认</h4><p>{{ ruleBusinessGuidance(inspected.code).prerequisite }}</p></section>
          <section><h4>默认异常条件</h4><p>{{ defaultCondition }}</p><ul v-if="inspected.parameters.length"><li v-for="parameter in inspected.parameters" :key="parameter.key">{{ parameterText(parameter) }}</li></ul><p v-else>使用固定条件，无需额外参数。</p></section>
          <section><h4>可配置的对象范围</h4><div class="quality-check-catalog__scopes"><el-tag v-for="scope in sortRuleScopes(inspected.allowedScopes)" :key="scope" type="info">{{ ruleScopeLabel(scope) }}</el-tag></div></section>
          <section><h4>结果的适用边界</h4><p>{{ ruleEvidenceLimit(inspected.code) }}</p><p v-if="inspected.code === 'DATA_UNIQUE_VIOLATION'">默认只检查主键字段；检查业务唯一字段时，需要显式关闭“仅检查主键字段”。</p><p>失败、不适用和未执行都不等于通过。</p></section>
          <details><summary>技术信息</summary><code>{{ inspected.code }}</code><el-button link :icon="Copy" @click="copyCode">复制编码</el-button></details>
          <el-button type="primary" :disabled="!selectable" :title="selectable ? '' : disabledReason" @click="selectItem">{{ actionLabel }}</el-button>
          <p v-if="!selectable && disabledReason" role="status">{{ disabledReason }}</p>
        </article>
        <div v-else class="quality-check-catalog__placeholder">先选择一个检查项，了解它检查什么，再配置规则的对象和异常条件。</div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.quality-check-catalog { display: grid; gap: 14px; min-width: 0; }
.quality-check-catalog__filters { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: 12px; }
.quality-check-catalog__filters label { display: grid; gap: 6px; min-width: 0; color: var(--do-ink); font-size: 12px; }
.quality-check-catalog__count { margin: 0; color: var(--do-muted); font-size: 12px; }
.quality-check-catalog__body { display: grid; grid-template-columns: minmax(240px, .9fr) minmax(0, 1.1fr); gap: 16px; align-items: start; }
.quality-check-catalog__list { list-style: none; display: grid; gap: 6px; margin: 0; padding: 2px; max-height: 480px; overflow-y: auto; }
.quality-check-catalog__item { display: flex; justify-content: space-between; align-items: center; gap: 10px; width: 100%; padding: 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-surface); color: var(--do-ink); text-align: left; cursor: pointer; }
.quality-check-catalog__item[aria-pressed="true"] { border-color: var(--do-primary); background: var(--do-primary-soft); }
.quality-check-catalog__item:focus-visible { outline: 2px solid var(--do-primary); outline-offset: -2px; }
.quality-check-catalog__item strong { display: block; font-size: 13px; line-height: 1.5; }
.quality-check-catalog__item small { display: block; margin-top: 4px; color: var(--do-muted); font-size: 11px; line-height: 1.5; }
.quality-check-catalog__detail { display: grid; gap: 14px; padding: 16px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); }
.quality-check-catalog__detail h3 { margin: 4px 0 8px; font-size: 18px; color: var(--do-ink); }
.quality-check-catalog__detail h4 { margin: 0 0 6px; font-size: 13px; color: var(--do-ink); }
.quality-check-catalog__detail p,.quality-check-catalog__detail li { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.7; }
.quality-check-catalog__detail ul { padding-left: 18px; margin: 6px 0 0; }
.quality-check-catalog__eyebrow { color: var(--do-primary-strong); font-size: 11px; }
.quality-check-catalog__scopes { display: flex; flex-wrap: wrap; gap: 4px; }
.quality-check-catalog__detail details { padding-top: 10px; border-top: 1px solid var(--do-line); font-size: 12px; color: var(--do-muted); }
.quality-check-catalog__detail summary { cursor: pointer; }
.quality-check-catalog__detail code { display: block; margin-top: 8px; overflow-wrap: anywhere; user-select: all; }
.quality-check-catalog__placeholder { padding: 28px 20px; color: var(--do-muted); font-size: 13px; line-height: 1.8; }
@media (max-width: 700px) { .quality-check-catalog__body { grid-template-columns: minmax(0, 1fr); } .quality-check-catalog__filters { grid-template-columns: minmax(0, 1fr); } .quality-check-catalog__list { max-height: 230px; } }
</style>
