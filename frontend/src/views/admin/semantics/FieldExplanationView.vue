<script setup lang="ts">
/**
 * 字段业务解释工作面。
 *
 * 权威字段解释（FIELD_SEMANTIC）与术语、知识文档不同：这里维护可审核、可复用的
 * 字段业务含义。页面沿用公共 ScopeBar，并由 FieldSemanticWorkspace 处理表/字段定位。
 */
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import FieldSemanticWorkspace from '../governance/FieldSemanticWorkspace.vue'
</script>

<template>
  <main class="field-explanation-page post-login-page">
    <TaskPageHeader
      title="字段解释"
      description="维护字段的业务名称、含义、单位和口径；保存、AI 候选、审核与问数收录状态分开呈现。"
    />
    <FieldSemanticWorkspace />
  </main>
</template>

<style scoped>
.field-explanation-page { display: grid; gap: 16px; min-width: 0; }
</style>
