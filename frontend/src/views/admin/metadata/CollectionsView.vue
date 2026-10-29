<script setup lang="ts">
import { computed, ref } from 'vue'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAdminContextStore } from '../../../stores/adminContext'
import { Play } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import SyncTask from './SyncTask.vue'
import SyncSchedule from './SyncSchedule.vue'

const route = useRoute()
const router = useRouter()
const iam = useIamS1Store()
const context = useAdminContextStore()
const activeTab = computed(() => route.query.tab === 'schedule' ? 'schedule' : 'tasks')
const canStart = computed(() => iam.loaded && !iam.errorMessage && (iam.systemAdmin || context.datasources.some(source => iam.canOnDatasource('metadata:collect:run', source.id))))
const syncTaskRef = ref<InstanceType<typeof SyncTask>>()

function selectTab(tab: string) {
  router.replace({ query: { ...route.query, tab } })
}

function openSyncDialog() {
  syncTaskRef.value?.openSyncDialog()
}


</script>

<template>
  <div class="admin-page collections-page">
    <TaskPageHeader
      title="采集任务"
      eyebrow="数据接入 / 采集与同步"
      description="把数据库结构收集为可追溯的快照，再进入治理与审核。" description-mode="inline"
      help="采集成功不等于审核发布，也不会自动构建 RAG。同步计划是全局设置，由系统管理员维护。"
    >
      <template #actions>
        <el-button v-if="activeTab === 'tasks' && canStart" type="primary" :icon="Play" @click="openSyncDialog">发起全量采集</el-button>
      </template>
    </TaskPageHeader>
    <el-tabs :model-value="activeTab" class="collections-page__tabs" @update:model-value="selectTab">
      <el-tab-pane label="采集任务" name="tasks"><SyncTask v-if="activeTab === 'tasks'" ref="syncTaskRef" /></el-tab-pane>
      <el-tab-pane label="同步计划" name="schedule" lazy><SyncSchedule /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.collections-page { display: grid; gap: 14px; }
.collections-page :deep(.task-page-header) { align-items: center; margin-bottom: 0; }
.collections-page :deep(.task-page-header h1) { font-size: clamp(28px, 2.4vw, 32px); line-height: 1.2; letter-spacing: -.025em; }
.collections-page :deep(.task-page-header__copy) { flex: 1 1 auto; min-width: 0; }
.collections-page__tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.collections-page__tabs :deep(.el-tab-pane) { padding-top: 0; }
</style>
