<script setup lang="ts">
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FieldTagManager from '../field/FieldTagManager.vue'
import ConfidenceDashboard from '../field/ConfidenceDashboard.vue'
import FeedbackReview from '../field/FeedbackReview.vue'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'

type FieldTab = 'tags' | 'confidence' | 'feedback'

const route = useRoute()
const router = useRouter()
const adminContext = useAdminContextStore()
const iamS1 = useIamS1Store()
const ALLOWED_TABS: FieldTab[] = ['tags', 'confidence', 'feedback']

function sanitizeTab(value: unknown): FieldTab {
  const raw = String(value || '')
  return (ALLOWED_TABS as string[]).includes(raw) ? raw as FieldTab : 'tags'
}


const activeTab = ref<FieldTab>(sanitizeTab(route.query.tab))

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
      description="管理字段分类标签、可靠性参考和反馈处理。" help="字段业务解释在语义中心的字段解释工作区，脱敏候选在授权配置的字段保护中。"
    />

    <el-tabs :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane label="标签" name="tags">
        <FieldTagManager embedded v-if="activeTab === 'tags'" :key="`tags-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
      </el-tab-pane>
      <el-tab-pane label="可信度" name="confidence">
        <ConfidenceDashboard v-if="activeTab === 'confidence'" :key="`confidence-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
      </el-tab-pane>
      <el-tab-pane label="反馈审核" name="feedback">
        <FeedbackReview v-if="activeTab === 'feedback'" :key="`feedback-${adminContext.datasourceId || 0}-${adminContext.snapshotId || 0}`" />
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
