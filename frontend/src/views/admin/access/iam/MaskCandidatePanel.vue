<script setup lang="ts">
/**
 * 待确认脱敏候选。
 *
 * 由字段保护统一维护：候选来自 PII 标签联动，确认后调用现有权限策略接口生成 MASK 策略。
 * 直接从字段治理页移入，避免同一任务出现两个主入口。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ShieldCheck } from 'lucide-vue-next'
import ErrorState from '../../../../components/common/ErrorState.vue'
import LoadingState from '../../../../components/common/LoadingState.vue'
import EmptyState from '../../../../components/common/EmptyState.vue'
import { useIamWorkspaceSession } from '../../../../composables/useIamWorkspaceSession'
import { pendingGovernanceWrites, trackGovernanceWrite } from '../../../../composables/governanceWriteTracker'
import {
  confirmMaskCandidate,
  listMaskCandidates,
  rejectMaskCandidate,
  type MaskCandidate,
} from '../../../../api/admin/catalog'

const props = defineProps<{ datasourceId?: number }>()
const emit = defineEmits<{ changed: [] }>()
const session = useIamWorkspaceSession()
const candidates = ref<MaskCandidate[]>([]),
  loading = ref(false),
  error = ref(''),
  actionError = ref(''),
  keyword = ref(''),
  strategyFilter = ref(''),
  page = ref(1)
const candidateBusy = reactive<Record<number, boolean>>({})
const allowedMaskStrategies = new Set(['PHONE', 'ID_CARD', 'EMAIL', 'BANK_CARD', 'NAME'])
const canViewMaskCandidates = computed(() => session.can('security:mask:view', props.datasourceId)),
  canManageMaskCandidates = computed(() => session.can('security:mask:manage', props.datasourceId))
const key = computed(() => JSON.stringify([session.key.value, props.datasourceId])),
  lock = computed(() => `iam-mask:${props.datasourceId}`)
const locked = computed(() =>
  pendingGovernanceWrites.value.some(
    (x) => x.identity === (session.auth.token || '') && x.keys.includes(lock.value),
  ),
)
const filtered = computed(() =>
  candidates.value.filter(
    (c) =>
      `${c.name} ${c.displayName || ''} ${c.fqn}`.toLowerCase().includes(keyword.value.toLowerCase()) &&
      (!strategyFilter.value || maskStrategy(c) === strategyFilter.value),
  ),
)
const shown = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10))
let requestId = 0,
  operation = 0
const current = (k: string) => session.alive() && session.ready.value && k === key.value
function maskStrategy(candidate: MaskCandidate) {
  const p = candidate.pendingMask || {}
  const s = String(p.mask_strategy || p.maskStrategy || '')
  return allowedMaskStrategies.has(s) ? s : ''
}
function maskTag(candidate: MaskCandidate) {
  const p = candidate.pendingMask || {}
  return String(p.tag_fqn || p.tagFqn || 'PII 标签')
}
async function fetchCandidates() {
  const r = ++requestId,
    k = key.value,
    ds = props.datasourceId
  candidates.value = []
  error.value = ''
  if (!ds || !canViewMaskCandidates.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await listMaskCandidates(ds)
    if (!current(k) || r !== requestId) return
    candidates.value = result.data || []
    page.value = Math.max(1, Math.min(page.value, Math.ceil(filtered.value.length / 10) || 1))
  } catch (e) {
    if (current(k) && r === requestId) error.value = e instanceof Error ? e.message : '脱敏候选加载失败'
  } finally {
    if (current(k) && r === requestId) loading.value = false
  }
}
async function act(candidate: MaskCandidate, confirm: boolean) {
  if (
    !canManageMaskCandidates.value ||
    locked.value ||
    Object.values(candidateBusy).some(Boolean) ||
    !candidates.value.includes(candidate)
  )
    return
  const strategy = maskStrategy(candidate)
  if (confirm && !strategy) {
    actionError.value = '当前候选策略无法识别，不能确认'
    return
  }
  const frozen = JSON.stringify(candidate),
    k = key.value,
    ds = props.datasourceId,
    o = ++operation,
    actor = session.auth.token || '',
    targetLock = lock.value
  candidateBusy[candidate.entityId] = true
  actionError.value = ''
  try {
    await ElMessageBox.confirm(
      `数据源 #${ds} / 字段实体 #${candidate.entityId} / ${candidate.fqn}。${confirm ? `确认 ${strategy} 脱敏将生成面向所有用户的正式字段保护；不授予查询权。` : '拒绝仅清除该字段当前候选，不撤销已有正式保护。'}`,
      confirm ? '确认脱敏策略' : '拒绝脱敏候选',
      {
        type: 'warning',
        confirmButtonText: confirm ? '确认生效' : '确认拒绝',
        cancelButtonText: '取消',
        cancelButtonClass: session.cancelClass,
        closeOnClickModal: false,
      },
    )
    if (
      !current(k) ||
      o !== operation ||
      !canManageMaskCandidates.value ||
      !candidates.value.includes(candidate) ||
      JSON.stringify(candidate) !== frozen
    )
      return
    await trackGovernanceWrite(
      actor,
      [targetLock],
      `源 #${ds} / ${candidate.fqn} 脱敏候选 #${candidate.entityId}`,
      () =>
        confirm
          ? confirmMaskCandidate(candidate.entityId, strategy)
          : rejectMaskCandidate(candidate.entityId),
    )
    if (!current(k) || o !== operation) return
    await fetchCandidates()
    if (current(k)) emit('changed')
  } catch (e) {
    if (current(k) && o === operation && e !== 'cancel' && e !== 'close')
      actionError.value = e instanceof Error ? e.message : '操作失败；结果未知时先核对原候选和正式记录'
  } finally {
    if (current(k) && o === operation) candidateBusy[candidate.entityId] = false
  }
}
const confirmCandidate = (c: MaskCandidate) => act(c, true),
  rejectCandidate = (c: MaskCandidate) => act(c, false)
watch([keyword, strategyFilter], () => {
  page.value = 1
})
watch(
  key,
  () => {
    operation++
    Object.keys(candidateBusy).forEach((k) => delete candidateBusy[Number(k)])
    actionError.value = ''
    keyword.value = ''
    strategyFilter.value = ''
    page.value = 1
    session.cancelOwn()
    void fetchCandidates()
  },
  { immediate: true, flush: 'sync' },
)
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
    <div class="candidate-filters">
      <el-input v-model="keyword" clearable placeholder="筛选候选字段或路径" />
      <el-select v-model="strategyFilter" clearable placeholder="全部建议策略">
        <el-option v-for="s in [...allowedMaskStrategies]" :key="s" :label="s" :value="s" />
      </el-select>
      <el-button @click="fetchCandidates">重读候选</el-button>
    </div>
    <p v-if="actionError" role="status">{{ actionError }}</p>
    <p v-if="locked">本源有执行中或结果未知的保护操作，先核对正式记录及候选。</p>
    <ErrorState v-if="error" :message="error" @retry="fetchCandidates" />
    <LoadingState v-else-if="loading" text="正在读取脱敏候选…" />
    <EmptyState v-else-if="!datasourceId" message="先选择数据源，再查看该数据源的脱敏候选。" />
    <EmptyState
      v-else-if="!canViewMaskCandidates"
      message="没有“查看字段保护”能力（security:mask:view）：需要角色拥有该功能并负责当前数据源。"
    />
    <EmptyState v-else-if="!candidates.length" message="当前数据源暂无待确认的脱敏候选。" />
    <el-table v-else :data="shown">
      <el-table-column label="字段" min-width="180">
        <template #default="{ row }">
          <strong>{{ row.displayName || row.name }}</strong>
        </template>
      </el-table-column>
      <el-table-column prop="fqn" label="资产路径" min-width="260" show-overflow-tooltip />
      <el-table-column label="来源标签" width="150">
        <template #default="{ row }">{{ maskTag(row) }}</template>
      </el-table-column>
      <el-table-column label="建议策略" width="150">
        <template #default="{ row }">
          <el-tag :type="maskStrategy(row) ? 'warning' : 'danger'">
            {{ maskStrategy(row) || '策略无法识别' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :loading="candidateBusy[row.entityId]"
            :disabled="locked || !maskStrategy(row) || !canManageMaskCandidates"
            :title="canManageMaskCandidates ? '' : '需要 security:mask:manage 与当前数据源同一绑定'"
            @click="confirmCandidate(row)"
          >
            确认生效
          </el-button>
          <el-button
            link
            type="danger"
            :loading="candidateBusy[row.entityId]"
            :disabled="locked || !canManageMaskCandidates"
            :title="canManageMaskCandidates ? '' : '需要 security:mask:manage 与当前数据源同一绑定'"
            @click="rejectCandidate(row)"
          >
            拒绝
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="!loading && !error && filtered.length"
      v-model:current-page="page"
      :page-size="10"
      :total="filtered.length"
      layout="total, prev, pager, next"
    />
    <p v-if="canViewMaskCandidates && !canManageMaskCandidates" class="mask-candidate-panel__hint">
      你可以查看脱敏候选，但没有“维护字段保护”能力（security:mask:manage），因此不能确认或拒绝。
    </p>
  </section>
</template>

<style scoped>
.candidate-filters {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.candidate-filters .el-input {
  width: min(300px, 100%);
}
.candidate-filters .el-select {
  width: 190px;
}
.mask-candidate-panel {
  display: grid;
  gap: 12px;
  min-width: 0;
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
.mask-candidate-panel__heading p {
  color: var(--do-muted);
  font-size: 12px;
}
.mask-candidate-panel__heading h3 {
  margin: 4px 0;
  color: var(--do-ink);
  font-size: 16px;
}
.mask-candidate-panel__heading p {
  margin: 0;
}
.mask-candidate-panel__hint {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
