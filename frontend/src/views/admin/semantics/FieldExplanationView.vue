<script setup lang="ts">
/**
 * 字段业务解释工作面。
 *
 * 权威字段解释（FIELD_SEMANTIC）与术语、知识文档不同：这里维护可审核、可复用的
 * 字段业务含义。页面沿用公共 ScopeBar，并由 FieldSemanticWorkspace 处理表/字段定位。
 */
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import FieldSemanticWorkspace from '../governance/FieldSemanticWorkspace.vue'
import {computed} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {useAdminContextStore} from '../../../stores/adminContext'
import {useIamS1Store} from '../../../stores/iamS1'
const context=useAdminContextStore(),route=useRoute(),router=useRouter(),iam=useIamS1Store()
const canReturn=computed(()=>!!context.datasourceId&&!!context.snapshotId&&(!route.query.datasourceId||Number(route.query.datasourceId)===context.datasourceId)&&(!route.query.snapshotId||Number(route.query.snapshotId)===context.snapshotId)&&iam.canOnDatasource('governance:field:view',context.datasourceId))
</script>

<template>
  <main class="field-explanation-page post-login-page">
    <TaskPageHeader
      title="字段解释"
      description="维护字段的业务名称、含义、单位和口径；保存、AI 候选、审核与问数收录状态分开呈现。"
    ><template #actions><el-button :disabled="!canReturn" @click="router.push({path:'/admin/governance/fields',query:{tab:'tasks',datasourceId:context.datasourceId,snapshotId:context.snapshotId}})">治理任务</el-button></template></TaskPageHeader>
    <FieldSemanticWorkspace />
  </main>
</template>

<style scoped>
.field-explanation-page { display: grid; gap: 16px; min-width: 0; }
</style>
