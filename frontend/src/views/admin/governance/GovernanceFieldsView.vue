<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ShieldCheck } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import FieldTagManager from '../field/FieldTagManager.vue'
import ConfidenceDashboard from '../field/ConfidenceDashboard.vue'
import FeedbackReview from '../field/FeedbackReview.vue'
import { confirmMaskCandidate, listMaskCandidates, rejectMaskCandidate, type MaskCandidate } from '../../../api/admin/catalog'
import { useAdminContextStore } from '../../../stores/adminContext'
import ErrorState from '../../../components/common/ErrorState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const adminContext = useAdminContextStore()
const activeTab = ref(String(route.query.tab || 'tags'))
const candidates = ref<MaskCandidate[]>([])
const maskLoading = ref(false)
const maskError = ref('')
const candidateBusy = reactive<Record<number, boolean>>({})
let maskRequestId = 0
let disposed = false
const allowedMaskStrategies = new Set(['PHONE', 'ID_CARD', 'EMAIL', 'BANK_CARD', 'NAME'])

const maskStrategy = (candidate: MaskCandidate) => {
  const pending = candidate.pendingMask || {}
  const strategy = String(pending.mask_strategy || pending.maskStrategy || '')
  return allowedMaskStrategies.has(strategy) ? strategy : ''
}

const maskTag = (candidate: MaskCandidate) => {
  const pending = candidate.pendingMask || {}
  return String(pending.tag_fqn || pending.tagFqn || 'PII 标签')
}

const hasDatasource = computed(() => Boolean(adminContext.datasourceId))

function selectTab(tab: string) {
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } })
}

async function fetchCandidates() {
  const currentRequest = ++maskRequestId
  if (!adminContext.datasourceId) {
    candidates.value = []
    maskError.value = ''
    return
  }
  const datasourceId = adminContext.datasourceId
  maskLoading.value = true
  maskError.value = ''
  try {
    const result = await listMaskCandidates(adminContext.datasourceId)
    if (disposed || currentRequest !== maskRequestId || datasourceId !== adminContext.datasourceId) return
    candidates.value = result.data || []
  } catch (error) {
    if (disposed || currentRequest !== maskRequestId || datasourceId !== adminContext.datasourceId) return
    candidates.value = []
    maskError.value = error instanceof Error ? error.message : '脱敏候选加载失败'
  } finally {
    maskLoading.value = false
  }
}

async function confirmCandidate(candidate: MaskCandidate) {
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
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '确认脱敏策略失败')
    }
  } finally {
    candidateBusy[candidate.entityId] = false
  }
}

async function rejectCandidate(candidate: MaskCandidate) {
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
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '拒绝脱敏候选失败')
    }
  } finally {
    candidateBusy[candidate.entityId] = false
  }
}

onMounted(async () => {
  await adminContext.initialize()
  if (activeTab.value === 'mask') await fetchCandidates()
})

watch(() => route.query.tab, async (tab) => {
  activeTab.value = String(tab || 'tags')
  if (activeTab.value === 'mask') await fetchCandidates()
})

watch(() => adminContext.datasourceId, async () => {
  if (activeTab.value === 'mask') await fetchCandidates()
})

onBeforeUnmount(() => {
  disposed = true
  maskRequestId++
})
</script>

<template>
  <main class="governance-fields-page post-login-page">
    <el-tabs :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane label="标签" name="tags">
        <FieldTagManager v-if="activeTab === 'tags'" :key="`tags-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
      </el-tab-pane>
      <el-tab-pane label="可信度" name="confidence">
        <ConfidenceDashboard v-if="activeTab === 'confidence'" :key="`confidence-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
      </el-tab-pane>
      <el-tab-pane label="反馈审核" name="feedback">
        <FeedbackReview v-if="activeTab === 'feedback'" :key="`feedback-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
      </el-tab-pane>
      <el-tab-pane label="脱敏候选" name="mask">
        <section class="mask-panel">
          <div class="mask-panel__heading">
            <div>
              <span>PII 标签联动</span>
              <h2>待确认脱敏策略</h2>
              <p>确认后将调用现有权限策略接口生成 MASK 策略；拒绝只清除当前候选。</p>
            </div>
            <ShieldCheck :size="22" />
          </div>
          <ErrorState v-if="maskError" :message="maskError" @retry="fetchCandidates" />
          <LoadingState v-else-if="maskLoading" text="正在读取脱敏候选…" />
          <EmptyState v-else-if="!hasDatasource" message="请先选择数据源，再查看该数据源的脱敏候选。" />
          <EmptyState v-else-if="!candidates.length" message="当前数据源暂无待确认的脱敏候选。" />
        <el-table v-else :data="candidates" stripe>
            <el-table-column label="字段" min-width="180">
              <template #default="{ row }"><strong>{{ row.displayName || row.name }}</strong></template>
            </el-table-column>
            <el-table-column prop="fqn" label="资产路径" min-width="280" show-overflow-tooltip />
            <el-table-column label="来源标签" width="150"><template #default="{ row }">{{ maskTag(row) }}</template></el-table-column>
            <el-table-column label="建议策略" width="150"><template #default="{ row }"><el-tag :type="maskStrategy(row) ? 'warning' : 'danger'">{{ maskStrategy(row) || '策略无法识别' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="170" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :loading="candidateBusy[row.entityId]" :disabled="!maskStrategy(row)" @click="confirmCandidate(row)">确认生效</el-button>
                <el-button link type="danger" :loading="candidateBusy[row.entityId]" @click="rejectCandidate(row)">拒绝</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>
  </main>
</template>

<style scoped>
.governance-fields-page { display: grid; gap: 16px; }
.mask-panel { padding: 18px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); box-shadow: var(--do-shadow); }
.mask-panel__heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; color: var(--do-primary-strong); }
.mask-panel__heading span, .mask-panel__heading p { color: var(--do-muted); font-size: 12px; }
.mask-panel__heading h2 { margin: 5px 0; color: var(--do-ink); font-size: 18px; }
.mask-panel__heading p { margin: 0; }
</style>
