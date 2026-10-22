<script setup lang="ts">
/**
 * 待确认脱敏候选。
 *
 * 由字段保护统一维护：候选来自 PII 标签联动，确认后调用现有权限策略接口生成 MASK 策略。
 * 直接从字段治理页移入，避免同一任务出现两个主入口。
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ShieldCheck } from 'lucide-vue-next'
import ErrorState from '../../../../components/common/ErrorState.vue'
import LoadingState from '../../../../components/common/LoadingState.vue'
import EmptyState from '../../../../components/common/EmptyState.vue'
import { useIamS1Store } from '../../../../stores/iamS1'
import { confirmMaskCandidate, listMaskCandidates, rejectMaskCandidate, type MaskCandidate } from '../../../../api/admin/catalog'

const props = defineProps<{ datasourceId?: number }>()
const iamS1 = useIamS1Store()
const candidates = ref<MaskCandidate[]>([])
const loading = ref(false)
const error = ref('')
const candidateBusy = reactive<Record<number, boolean>>({})
const allowedMaskStrategies = new Set(['PHONE', 'ID_CARD', 'EMAIL', 'BANK_CARD', 'NAME'])
let requestId = 0
let disposed = false

const canViewMaskCandidates = computed(
  () => iamS1.canOnDatasource('security:mask:view', props.datasourceId),
)
const canManageMaskCandidates = computed(
  () => iamS1.canOnDatasource('security:mask:manage', props.datasourceId),
)

function maskStrategy(candidate: MaskCandidate) {
  const pending = candidate.pendingMask || {}
  const strategy = String(pending.mask_strategy || pending.maskStrategy || '')
  return allowedMaskStrategies.has(strategy) ? strategy : ''
}

function maskTag(candidate: MaskCandidate) {
  const pending = candidate.pendingMask || {}
  return String(pending.tag_fqn || pending.tagFqn || 'PII 标签')
}

async function fetchCandidates() {
  const current = ++requestId
  candidates.value = []
  error.value = ''
  if (!props.datasourceId || !canViewMaskCandidates.value) {
    loading.value = false
    return
  }
  const datasourceId = props.datasourceId
  loading.value = true
  try {
    const result = await listMaskCandidates(datasourceId)
    if (disposed || current !== requestId || datasourceId !== props.datasourceId) return
    candidates.value = result.data || []
  } catch (cause) {
    if (disposed || current !== requestId || datasourceId !== props.datasourceId) return
    error.value = cause instanceof Error ? cause.message : '脱敏候选加载失败'
  } finally {
    if (!disposed && current === requestId) loading.value = false
  }
}

async function confirmCandidate(candidate: MaskCandidate) {
  if (!canManageMaskCandidates.value) {
    ElMessage.warning('没有“维护字段保护”能力：需要角色拥有该功能并负责当前数据源。')
    return
  }
  const strategy = maskStrategy(candidate)
  if (!strategy) {
    ElMessage.warning('当前候选的脱敏策略无法识别，已阻止确认，请先修复后端候选数据')
    return
  }
  if (candidateBusy[candidate.entityId]) return
  candidateBusy[candidate.entityId] = true
  try {
    await ElMessageBox.confirm(
      `确认将「${candidate.displayName || candidate.name}」设置为 ${strategy} 脱敏？确认后会生成面向所有用户的 MASK 策略。`,
      '确认脱敏策略',
      { type: 'warning', confirmButtonText: '确认生效', cancelButtonText: '取消' },
    )
    await confirmMaskCandidate(candidate.entityId, strategy)
    ElMessage.success('脱敏策略已确认')
    await fetchCandidates()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') {
      ElMessage.error(cause instanceof Error ? cause.message : '确认脱敏策略失败')
    }
  } finally {
    candidateBusy[candidate.entityId] = false
  }
}

async function rejectCandidate(candidate: MaskCandidate) {
  if (!canManageMaskCandidates.value) {
    ElMessage.warning('没有“维护字段保护”能力：需要角色拥有该功能并负责当前数据源。')
    return
  }
  if (candidateBusy[candidate.entityId]) return
  candidateBusy[candidate.entityId] = true
  try {
    await ElMessageBox.confirm(`确认拒绝「${candidate.displayName || candidate.name}」的脱敏候选？`, '拒绝脱敏候选', {
      type: 'warning',
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
    })
    await rejectMaskCandidate(candidate.entityId)
    ElMessage.success('已拒绝脱敏候选')
    await fetchCandidates()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') {
      ElMessage.error(cause instanceof Error ? cause.message : '拒绝脱敏候选失败')
    }
  } finally {
    candidateBusy[candidate.entityId] = false
  }
}

watch(() => props.datasourceId, fetchCandidates, { immediate: true })
onBeforeUnmount(() => {
  disposed = true
  requestId++
})
</script>

<template>
  <section class="mask-candidate-panel" aria-labelledby="mask-candidate-title">
    <div class="mask-candidate-panel__heading">
      <div>
        <span>PII 标签联动</span>
        <h3 id="mask-candidate-title">待确认脱敏候选</h3>
        <p>确认后生成面向所有用户的 MASK 策略；拒绝只清除当前候选。</p>
      </div>
      <ShieldCheck :size="22" aria-hidden="true" />
    </div>
    <ErrorState v-if="error" :message="error" @retry="fetchCandidates" />
    <LoadingState v-else-if="loading" text="正在读取脱敏候选…" />
    <EmptyState v-else-if="!datasourceId" message="先选择数据源，再查看该数据源的脱敏候选。" />
    <EmptyState
      v-else-if="!canViewMaskCandidates"
      message="没有“查看字段保护”能力（security:mask:view）：需要角色拥有该功能并负责当前数据源。"
    />
    <EmptyState v-else-if="!candidates.length" message="当前数据源暂无待确认的脱敏候选。" />
    <el-table v-else :data="candidates" stripe>
      <el-table-column label="字段" min-width="180">
        <template #default="{ row }"><strong>{{ row.displayName || row.name }}</strong></template>
      </el-table-column>
      <el-table-column prop="fqn" label="资产路径" min-width="260" show-overflow-tooltip />
      <el-table-column label="来源标签" width="150"><template #default="{ row }">{{ maskTag(row) }}</template></el-table-column>
      <el-table-column label="建议策略" width="150">
        <template #default="{ row }"><el-tag :type="maskStrategy(row) ? 'warning' : 'danger'">{{ maskStrategy(row) || '策略无法识别' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :loading="candidateBusy[row.entityId]"
            :disabled="!maskStrategy(row) || !canManageMaskCandidates"
            :title="canManageMaskCandidates ? '' : '需要 security:mask:manage 与当前数据源同一绑定'"
            @click="confirmCandidate(row)"
          >确认生效</el-button>
          <el-button
            link
            type="danger"
            :loading="candidateBusy[row.entityId]"
            :disabled="!canManageMaskCandidates"
            :title="canManageMaskCandidates ? '' : '需要 security:mask:manage 与当前数据源同一绑定'"
            @click="rejectCandidate(row)"
          >拒绝</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p v-if="canViewMaskCandidates && !canManageMaskCandidates" class="mask-candidate-panel__hint">
      你可以查看脱敏候选，但没有“维护字段保护”能力（security:mask:manage），因此不能确认或拒绝。
    </p>
  </section>
</template>

<style scoped>
.mask-candidate-panel {
  display: grid;
  gap: 12px;
  margin-bottom: 20px;
  padding: 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-bg);
}
.mask-candidate-panel__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  color: var(--do-primary-strong);
}
.mask-candidate-panel__heading span,
.mask-candidate-panel__heading p { color: var(--do-muted); font-size: 12px; }
.mask-candidate-panel__heading h3 { margin: 4px 0; color: var(--do-ink); font-size: 16px; }
.mask-candidate-panel__heading p { margin: 0; }
.mask-candidate-panel__hint { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
</style>
