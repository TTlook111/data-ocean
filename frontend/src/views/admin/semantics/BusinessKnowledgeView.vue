<script setup lang="ts">
import {useRouter} from 'vue-router'
import {useAdminContextStore} from '../../../stores/adminContext'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessKnowledgeWorkbench from '../../../components/admin/BusinessKnowledgeWorkbench.vue'
const context=useAdminContextStore(),router=useRouter()
</script>
<template><main class="business-knowledge-page post-login-page governance-workspace governance-surface">
  <TaskPageHeader title="业务知识治理" description="确认数据源用途、表粒度、关系与指标口径；此处与治理任务使用同一份修订和审核记录。">
    <template #actions><el-button :disabled="!context.datasourceId" @click="router.push({path:'/admin/semantics/knowledge',query:{datasourceId:context.datasourceId}})">返回知识文档</el-button></template>
  </TaskPageHeader><BusinessKnowledgeWorkbench />
</main></template>
<style scoped>.business-knowledge-page{display:grid;gap:16px;min-width:0}</style>
