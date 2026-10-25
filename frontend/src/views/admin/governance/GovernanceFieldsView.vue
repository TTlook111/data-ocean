<script setup lang="ts">
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FieldTagManager from '../field/FieldTagManager.vue'
import ConfidenceDashboard from '../field/ConfidenceDashboard.vue'
import FeedbackReview from '../field/FeedbackReview.vue'
import GovernanceWorklist from '../../../components/admin/GovernanceWorklist.vue'
import BusinessKnowledgeWorkbench from '../../../components/admin/BusinessKnowledgeWorkbench.vue'
import GovernanceFeedbackQueue from '../../../components/admin/GovernanceFeedbackQueue.vue'
import GovernanceUsageProfiles from '../../../components/admin/GovernanceUsageProfiles.vue'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'

type FieldTab = 'tasks' | 'business' | 'tags' | 'confidence' | 'feedback'

const route = useRoute()
const router = useRouter()
const adminContext = useAdminContextStore()
const iamS1 = useIamS1Store()
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

onMounted(async () => {
  await Promise.all([adminContext.initialize(), iamS1.load()])
})

watch(() => route.query.tab, (tab) => {
  activeTab.value = sanitizeTab(tab)
})
</script>

<template>
  <main class="governance-fields-page post-login-page governance-workspace governance-surface">
    <TaskPageHeader
      title="字段治理"
      description="处理知识缺口，维护业务知识、字段标签与使用反馈。" help="字段业务解释在语义中心的字段解释工作区，脱敏候选在授权配置的字段保护中。"
    />

    <el-tabs :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane label="治理任务" name="tasks"><GovernanceWorklist v-if="activeTab==='tasks'" /></el-tab-pane>
      <el-tab-pane label="业务知识" name="business"><BusinessKnowledgeWorkbench v-if="activeTab==='business'" /></el-tab-pane>
      <el-tab-pane label="标签" name="tags">
        <FieldTagManager embedded v-if="activeTab === 'tags'" :key="`tags-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
      </el-tab-pane>
      <el-tab-pane label="可信度" name="confidence">
        <GovernanceUsageProfiles v-if="activeTab === 'confidence'" :key="`confidence-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
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
.governance-fields-page > :deep(.el-tabs) { padding: 0; border: 0; box-shadow: none; background: transparent; }
.governance-fields-page :deep(.content-panel) { padding: 0; border: 0; box-shadow: none; background: transparent; }
.governance-fields-page :deep(.toolbar) { padding: 0; border: 0; box-shadow: none; background: transparent; }
.governance-fields-page { min-width: 0; display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.governance-fields-page :deep(.el-tabs),
.governance-fields-page :deep(.el-tabs__content),
.governance-fields-page :deep(.el-tab-pane) { min-width: 0; max-width: 100%; }
</style>
