<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref } from 'vue'
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

// 列表填充布局：宽屏且窗口高度足够时，「采集任务」tab 始终铺满内容区，
// 少量数据时表体留白留在表格容器内，不再把空白留在页面下方；窄屏或矮窗口退回页面滚动。
const wideEnough = ref(false)
let mediaQuery: MediaQueryList | undefined
function syncMedia() { wideEnough.value = Boolean(mediaQuery?.matches) }
onMounted(() => {
  mediaQuery = window.matchMedia('(min-width: 1200px) and (min-height: 700px)')
  syncMedia()
  mediaQuery.addEventListener('change', syncMedia)
})
onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', syncMedia)
})
const fillLayout = computed(() => activeTab.value === 'tasks' && wideEnough.value)
provide('collectionListFill', fillLayout)

function selectTab(tab: string) {
  router.replace({ query: { ...route.query, tab } })
}

function openSyncDialog() {
  syncTaskRef.value?.openSyncDialog()
}
</script>

<template>
  <div class="admin-page collections-page" :class="{ 'admin-list-fill': fillLayout }">
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
.collections-page { display: grid; gap: 14px; min-width: 0; }
.collections-page :deep(.task-page-header) { align-items: center; margin-bottom: 0; }
.collections-page :deep(.task-page-header h1) { font-size: 28px; line-height: 1.2; letter-spacing: -.025em; }
.collections-page :deep(.task-page-header__copy) { flex: 1 1 auto; min-width: 0; }

.collections-page__tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.collections-page__tabs :deep(.el-tab-pane) { padding-top: 0; }
.collections-page__tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--do-line); }
.collections-page__tabs :deep(.el-tabs__item) { height: 44px; padding: 0 2px; margin-right: 28px; color: var(--do-muted); font-size: 14px; font-weight: 600; }
.collections-page__tabs :deep(.el-tabs__item.is-active) { color: var(--do-primary-strong); }
.collections-page__tabs :deep(.el-tabs__item:hover) { color: var(--do-primary); }
.collections-page__tabs :deep(.el-tabs__active-bar) { height: 2px; border-radius: 2px; background: var(--do-primary); }

@media (min-width: 1200px) and (min-height: 700px) {
  .collections-page.admin-list-fill { display: flex; flex-direction: column; min-height: 0; }
  .collections-page.admin-list-fill .collections-page__tabs { display: flex; flex: 1 1 auto; flex-direction: column; min-height: 0; }
  .collections-page.admin-list-fill .collections-page__tabs :deep(.el-tabs__header) { flex: 0 0 auto; }
  .collections-page.admin-list-fill .collections-page__tabs :deep(.el-tabs__content) { flex: 1 1 auto; min-height: 0; }
  .collections-page.admin-list-fill .collections-page__tabs :deep(.el-tab-pane) { height: 100%; min-height: 0; }
}
</style>
