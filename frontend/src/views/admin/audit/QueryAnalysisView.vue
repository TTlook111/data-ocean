<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import AuditLogList from './AuditLogList.vue'
import SlowQueryList from './SlowQueryList.vue'

const route = useRoute()
const router = useRouter()
const tab = ref(String(route.query.tab || 'audit'))

function selectTab(value: string) {
  tab.value = value
  router.replace({ query: { ...route.query, tab: value } })
}

watch(() => route.query.tab, (value) => { tab.value = String(value || 'audit') })
</script>

<template>
  <div class="admin-page query-analysis-page">
    <TaskPageHeader title="查询分析" description="围绕查询审计、慢查询和统计观察问数效果。性能页不显示无效的数据源筛选器，具体筛选能力以接口为准。" />
    <el-tabs :model-value="tab" @update:model-value="selectTab">
      <el-tab-pane label="查询审计" name="audit"><AuditLogList /></el-tab-pane>
      <el-tab-pane label="性能分析" name="performance"><SlowQueryList /></el-tab-pane>
    </el-tabs>
  </div>
</template>
