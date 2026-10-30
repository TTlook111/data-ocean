<script setup lang="ts">
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getGenerationInputs, type GenerationInputs } from '../../api/admin/knowledge'
import { getHttpErrorMessage } from '../../utils/httpError'
import ErrorState from '../common/ErrorState.vue'
const props = defineProps<{ datasourceId?: number; snapshotId?: number; tableNames?: string[] }>()
const emit = defineEmits<{ ready: [boolean]; loaded: [GenerationInputs] }>()
const auth=useAuthStore(),iam=useIamS1Store()
const input = ref<GenerationInputs>()
const error = ref('')
const loading = ref(false)
const includedFacts = computed(() => (input.value?.glossaryFacts || []).filter(fact =>
  !props.tableNames?.length || fact.dependencies.every(d => props.tableNames!.some(table => d === `table:${table}` || d.startsWith(`column:${table}.`)))))
const excludedTerms = computed(() => [...(input.value?.excludedTerms || []), ...(input.value?.glossaryFacts || [])
  .filter(fact => !includedFacts.value.includes(fact)).map(fact => ({ termId: fact.term_id, name: fact.name, reason: '所选业务表不能完整覆盖该术语的字段依赖' }))])
let requestId = 0
async function load() {
  const token = ++requestId
  loading.value = false
  emit('ready', false); input.value = undefined; error.value = ''
  if (!props.datasourceId || !props.snapshotId) return
  if(!iam.loaded || (!iam.systemAdmin && !iam.datasourcesWithFunction('knowledge:manage').includes(props.datasourceId))){error.value='没有本数据源的知识维护权，无法读取生成输入。';return}
  loading.value = true
  try {
    const result = await getGenerationInputs(props.datasourceId, props.snapshotId)
    if (token === requestId) { if(result.data.datasourceId!==props.datasourceId || result.data.snapshotId!==props.snapshotId)throw new Error('生成来源与指定源或快照不一致');input.value = result.data; emit('ready', true); emit('loaded', result.data) }
  } catch (cause) { if (token === requestId) error.value = getHttpErrorMessage(cause, '生成来源读取失败') }
  finally { if (token === requestId) loading.value = false }
}
watch(() => [props.datasourceId, props.snapshotId,auth.token,iam.snapshot,iam.loaded], load, { immediate: true })
onBeforeUnmount(() => { ++requestId })
</script>
<template>
  <section v-loading="loading" class="source-preview" aria-label="本次知识生成来源">
    <ErrorState v-if="error" :message="error" @retry="load" />
    <template v-else-if="input">
      <strong>本次生成依据：已发布快照 v{{ input.snapshotVersion ?? input.snapshotId }}</strong>
      <p v-if="input.tableCount !== undefined">{{ input.tableCount }} 张表 · {{ input.columnCount }} 个字段，结构目录保持完整。</p>
      <p>{{ includedFacts.length ? `将纳入 ${includedFacts.length} 条已审核业务术语。` : '尚无适用的已审核术语，仍可生成结构目录，业务解释后续补充。' }}</p>
      <details v-if="includedFacts.length"><summary>查看已审核术语与字段依据</summary>
        <div v-for="fact in includedFacts" :key="fact.term_id" class="source-item">
          <strong>{{ fact.name }} · 修订 {{ fact.revision }}</strong><p>{{ fact.description }}</p>
          <small>{{ fact.dependencies.filter(d => d.startsWith('column:')).map(d => d.slice(7)).join('、') }}</small>
        </div>
      </details>
      <details v-if="excludedTerms.length" open><summary>{{ excludedTerms.length }} 条术语未纳入</summary>
        <p v-for="item in excludedTerms" :key="item.termId">{{ item.name }}：{{ item.reason }}</p>
      </details>
    </template>
    <p v-else-if="!loading && !error">请选择数据源和已发布快照，以核对本次生成来源。</p>
  </section>
</template>
<style scoped>
.source-preview { display: grid; gap: 8px; margin: 12px 0; padding: 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); font-size: 13px; }
p { margin: 0; color: var(--do-muted); line-height: 1.6; }
.source-item { padding: 8px 0; border-bottom: 1px solid var(--do-line); }
small { overflow-wrap: anywhere; }
summary { cursor: pointer; }
</style>
