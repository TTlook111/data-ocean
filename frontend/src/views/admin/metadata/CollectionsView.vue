<script setup lang="ts">
import { ref, watch } from 'vue'
import { Play } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import SyncTask from './SyncTask.vue'
import SyncSchedule from './SyncSchedule.vue'

const route = useRoute()
const router = useRouter()
const activeTab = ref(String(route.query.tab || 'tasks'))
const syncTaskRef = ref<InstanceType<typeof SyncTask>>()

function selectTab(tab: string) {
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } })
}

function openSyncDialog() {
  syncTaskRef.value?.openSyncDialog()
}

watch(() => route.query.tab, (value) => {
  activeTab.value = String(value || 'tasks')
})
</script>

<template>
  <div class="admin-page collections-page">
    <TaskPageHeader
      title="采集任务"
      description="采集任务成功只代表任务完成，生成的快照仍需治理检查与审核发布；同步计划是作用于全部数据源的全局配置。"
    >
      <template #actions>
        <el-button v-if="activeTab === 'tasks'" type="primary" :icon="Play" @click="openSyncDialog">发起全量采集</el-button>
      </template>
    </TaskPageHeader>
    <el-tabs :model-value="activeTab" class="collections-page__tabs" @update:model-value="selectTab">
      <el-tab-pane label="采集任务" name="tasks"><SyncTask ref="syncTaskRef" /></el-tab-pane>
      <el-tab-pane label="同步计划" name="schedule"><SyncSchedule /></el-tab-pane>
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
