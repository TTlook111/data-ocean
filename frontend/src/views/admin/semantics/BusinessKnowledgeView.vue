<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAdminContextStore } from '../../../stores/adminContext'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessKnowledgeWorkbench from '../../../components/admin/BusinessKnowledgeWorkbench.vue'
const context = useAdminContextStore(),
  router = useRouter()
</script>
<template>
  <!-- 事实列表和阅读器已有自己的内容表面，外层只组织页头与工作面，给长正文保留宽度。 -->
  <main class="business-knowledge-page post-login-page governance-workspace">
    <!-- 导航承担语义工作区归属，页头保留事实治理任务及与治理内页共用修订的说明。 -->
    <TaskPageHeader
      title="业务知识治理"
      description="确认数据源用途、表粒度、关系与指标口径；此处与治理任务使用同一份修订和审核记录。"
    >
      <template #actions>
        <el-button
          :disabled="!context.datasourceId"
          @click="
            router.push({ path: '/admin/semantics/knowledge', query: { datasourceId: context.datasourceId } })
          "
        >
          返回知识文档
        </el-button>
      </template>
    </TaskPageHeader>
    <BusinessKnowledgeWorkbench />
  </main>
</template>
<style scoped>
/* 页面仅负责纵向节奏，不再为子工作面的列表或阅读器重复添加整页容器。 */
.business-knowledge-page {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.business-knowledge-page :deep(.task-page-header) {
  margin-bottom: 0;
}
</style>
