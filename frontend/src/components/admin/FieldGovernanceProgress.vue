<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getFieldGovernanceProgress, type FieldGovernanceProgress } from '../../api/admin/fieldSemantic'

const props = defineProps<{ columnId: number; snapshotId: number; version?: number; checkId?: number; canOpenTags?: boolean }>()
const emit = defineEmits<{ 'open-tags': [] }>()
const progress = ref<FieldGovernanceProgress>()
const loading = ref(false)
const error = ref('')
const labels: Record<string, string> = {
  NEEDS_GOVERNANCE: '待治理', HUMAN_EDITING: '人工补充', AWAITING_REVIEW: '待人工审核',
  NEEDS_CLASSIFICATION: '待补充业务标签',
  AWAITING_RECHECK: '待系统复查', ROUND_COMPLETED: '本轮已完成', NOT_APPLICABLE: '不适用',
}
const label = computed(() => progress.value ? labels[progress.value.stage] || '阶段待确认' : '')
watch(() => [props.columnId, props.snapshotId, props.version, props.checkId], async (_, __, cleanup) => {
  let active = true
  cleanup(() => { active = false })
  progress.value = undefined
  error.value = ''
  loading.value = true
  try {
    const response = await getFieldGovernanceProgress(props.columnId, props.snapshotId)
    if (!active) return
    if (response.data.columnMetaId !== props.columnId || response.data.snapshotId !== props.snapshotId) {
      throw new Error('治理阶段与当前字段范围不一致')
    }
    progress.value = response.data
  } catch {
    if (active) error.value = '治理阶段读取失败，请重新选择字段或刷新。'
  } finally {
    if (active) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <div class="field-governance-progress" role="status" aria-atomic="true">
    <span v-if="loading">正在核对治理阶段…</span>
    <span v-else-if="error">{{ error }}</span>
    <template v-else-if="progress">
      <strong>{{ label }}</strong>
      <span>{{ progress.reason }}</span><button v-if="progress.stage === 'NEEDS_CLASSIFICATION' && canOpenTags" type="button" @click="emit('open-tags')">前往当前字段标签</button>
    </template>
  </div>
</template>

<style scoped>
.field-governance-progress { display: flex; flex-wrap: wrap; gap: 8px 16px; padding: 12px 16px; border-left: 3px solid var(--do-primary, #1677ff); background: var(--do-surface); color: var(--do-ink); font-size: 13px; }
.field-governance-progress strong { flex-shrink: 0; }
button { min-height:32px; border:1px solid var(--do-line); border-radius:6px; background:var(--do-surface); color:var(--do-primary-strong); font:inherit; padding:4px 10px; cursor:pointer; }
button:focus-visible { outline:2px solid var(--do-primary); outline-offset:2px; }
</style>
