<script setup lang="ts">
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FieldTagManager from '../field/FieldTagManager.vue'
import ConfidenceDashboard from '../field/ConfidenceDashboard.vue'
import FeedbackReview from '../field/FeedbackReview.vue'
import GovernanceWorklist from '../../../components/admin/GovernanceWorklist.vue'
import BusinessKnowledgeWorkbench from '../../../components/admin/BusinessKnowledgeWorkbench.vue'
import GovernanceFeedbackQueue from '../../../components/admin/GovernanceFeedbackQueue.vue'
import GovernanceUsageProfiles from '../../../components/admin/GovernanceUsageProfiles.vue'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useGovernanceScope } from '../../../composables/useGovernanceScope'
import ErrorState from '../../../components/common/ErrorState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'

type FieldTab = 'tasks' | 'business' | 'tags' | 'confidence' | 'feedback'

const route = useRoute()
const router = useRouter()
const adminContext = useAdminContextStore()
const { iam: iamS1, auth, generation, scopeIssue, scopeLoading, datasourceIssue, ready, sourceReady, canSource } = useGovernanceScope()
const canView = computed(() => canSource('governance:field:view'))
const initializationError = ref('')
const ALLOWED_TABS: FieldTab[] = ['tasks', 'business', 'tags', 'confidence', 'feedback']

function sanitizeTab(value: unknown): FieldTab {
  if (!value) return 'tasks'
  const raw = String(value || '')
  return (ALLOWED_TABS as string[]).includes(raw) ? raw as FieldTab : 'tags'
}


const activeTab = ref<FieldTab>(sanitizeTab(route.query.tab))
const legacyConfidenceOpen=ref<string[]>([]),legacyFeedbackOpen=ref<string[]>([])

function selectTab(tab: string | number) {
  const next = sanitizeTab(tab)
  activeTab.value = next
  router.replace({ query: { ...route.query, tab: next } })
}

async function initializeScope() {
  const token = auth.token
  initializationError.value = ''
  try { await Promise.all([adminContext.initialize(), iamS1.load()]) } catch (error) { if (token === auth.token) initializationError.value = error instanceof Error ? error.message : '公共范围读取失败，请刷新重试。' }
}
watch(() => [adminContext.initialized, adminContext.loading, auth.token], () => { if (adminContext.initialized && !adminContext.loading) initializationError.value = '' })
onMounted(initializeScope)

watch(() => route.query.tab, (tab) => {
  activeTab.value = sanitizeTab(tab)
})
</script>

<template>
  <main class="governance-fields-page post-login-page governance-workspace governance-fields-v2">
    <TaskPageHeader
      title="字段治理"
      eyebrow="数据治理 / 知识与反馈" description="把知识缺口变成可处理的任务，逐项核对、审核并复查。" description-mode="inline" help="字段业务解释在语义中心的字段解释工作区，脱敏候选在授权配置的字段保护中。"
    />

    <LoadingState v-if="scopeLoading" text="正在核验数据源、快照与能力…" />
    <ErrorState v-else-if="!sourceReady || !canView" :message="datasourceIssue || '没有当前范围的字段查看权限。'" :show-retry="false" />
    <p v-if="initializationError" class="governance-init-error" role="alert">{{ initializationError }} <el-button @click="initializeScope">重试范围</el-button></p>
    <el-tabs v-if="sourceReady && canView && !scopeLoading" :key="generation" :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane label="治理任务" name="tasks"><GovernanceWorklist v-if="activeTab==='tasks'" /></el-tab-pane>
      <el-tab-pane label="业务知识" name="business"><BusinessKnowledgeWorkbench v-if="activeTab==='business' && ready" /><ErrorState v-else-if="activeTab==='business'" :message="scopeIssue" :show-retry="false" /></el-tab-pane>
      <el-tab-pane label="标签" name="tags">
        <ErrorState v-if="activeTab === 'tags' && (!ready || !canSource('metadata:view'))" :message="scopeIssue || '当前账号没有 metadata:view，不能读取标签所需的字段目录。'" :show-retry="false" /><FieldTagManager embedded v-else-if="activeTab === 'tags'" :key="`tags-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
      </el-tab-pane>
      <el-tab-pane label="可信度" name="confidence">
        <ErrorState v-if="activeTab === 'confidence' && (!ready || !canSource('metadata:view'))" :message="scopeIssue || '当前账号没有 metadata:view，不能读取当前字段使用档案目录；下方既有来源记录按源查看。'" :show-retry="false" /><GovernanceUsageProfiles v-else-if="activeTab === 'confidence'" :key="`confidence-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
        <el-collapse v-if="activeTab==='confidence'" v-model="legacyConfidenceOpen"><el-collapse-item title="查看既有来源可信度记录" name="source"><ConfidenceDashboard v-if="legacyConfidenceOpen.includes('source')" :key="`legacy-confidence-${adminContext.datasourceId||0}-${adminContext.snapshotId||0}`" /></el-collapse-item></el-collapse>
      </el-tab-pane>
      <el-tab-pane label="反馈审核" name="feedback">
        <GovernanceFeedbackQueue v-if="activeTab==='feedback'" :key="`feedback-${adminContext.datasourceId || 0}`" />
        <el-collapse v-if="activeTab==='feedback'" v-model="legacyFeedbackOpen"><el-collapse-item title="查看既有字段反馈记录" name="legacy"><FeedbackReview v-if="legacyFeedbackOpen.includes('legacy')" :key="`legacy-feedback-${adminContext.datasourceId||0}-${adminContext.snapshotId||0}`" /></el-collapse-item></el-collapse>
      </el-tab-pane>
    </el-tabs>
  </main>
</template>

<style scoped>
.governance-init-error { margin:0; font-size:13px; color:var(--do-danger); }
.governance-fields-v2 :deep(.task-page-header) { margin:0; }
.governance-fields-v2 :deep(.el-tabs__header) { margin-bottom:24px; }
.governance-fields-page > :deep(.el-tabs) { padding: 0; border: 0; box-shadow: none; background: transparent; }
.governance-fields-page :deep(.content-panel) { padding: 0; border: 0; box-shadow: none; background: transparent; }
.governance-fields-page :deep(.toolbar) { padding: 0; border: 0; box-shadow: none; background: transparent; }
.governance-fields-page { min-width: 0; display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.governance-fields-page :deep(.el-tabs),
.governance-fields-page :deep(.el-tabs__content),
.governance-fields-page :deep(.el-tab-pane) { min-width: 0; max-width: 100%; }
</style>
