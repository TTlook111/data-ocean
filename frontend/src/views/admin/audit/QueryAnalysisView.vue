<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import AuditLogList from './AuditLogList.vue'
import SlowQueryList from './SlowQueryList.vue'

const route = useRoute()
const router = useRouter()
const validTab = (value: unknown) => (value === 'performance' ? 'performance' : 'audit')
const tab = ref(validTab(route.query.tab))

function selectTab(value: string) {
  tab.value = validTab(value)
  router.replace({ query: { ...route.query, tab: value } })
}

watch(
  () => route.query.tab,
  (value) => {
    tab.value = validTab(value)
  },
)
</script>

<template>
  <div class="admin-page query-analysis-page">
    <TaskPageHeader
      title="查询分析"
      eyebrow="运营与平台 / 查询观察"
      description-mode="inline"
      description="从已记录的查询观察使用情况与性能，按当前权限核对原始审计证据。"
    />
    <el-tabs :model-value="tab" @update:model-value="selectTab">
      <el-tab-pane label="查询审计" name="audit"><AuditLogList v-if="tab === 'audit'" /></el-tab-pane>
      <el-tab-pane label="性能分析" name="performance">
        <SlowQueryList v-if="tab === 'performance'" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
