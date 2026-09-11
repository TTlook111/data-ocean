<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import SyncTask from './SyncTask.vue'
import SyncSchedule from './SyncSchedule.vue'

const route = useRoute()
const router = useRouter()
const activeTab = ref(String(route.query.tab || 'tasks'))

function selectTab(tab: string) {
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } })
}

watch(() => route.query.tab, (value) => {
  activeTab.value = String(value || 'tasks')
})
</script>

<template>
  <div class="admin-page collections-page">
    <TaskPageHeader
      eyebrow="数据接入"
      title="采集任务"
      description="从这里发起元数据采集、查看任务进度和失败原因；自动同步计划是独立的全局配置。"
    />
    <el-alert
      v-if="activeTab === 'tasks'"
      title="采集成功后下一步是查看新快照"
      description="任务状态为成功只代表采集完成，仍需进入版本发布和治理检查，不能直接视为正式资产。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-tabs :model-value="activeTab" class="collections-page__tabs" @update:model-value="selectTab">
      <el-tab-pane label="采集任务" name="tasks"><SyncTask /></el-tab-pane>
      <el-tab-pane label="同步计划" name="schedule"><SyncSchedule /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.collections-page {
  display: grid;
  gap: 16px;
}

.collections-page__tabs :deep(.el-tab-pane) {
  padding-top: 8px;
}
</style>
