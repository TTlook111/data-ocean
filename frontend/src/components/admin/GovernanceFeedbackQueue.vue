<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue'
import {useRoute} from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAdminContextStore } from '../../stores/adminContext'
import { useIamS1Store } from '../../stores/iamS1'
import { listGovernanceFeedback,reviewGovernanceFeedback,verifyGovernanceFeedback,type GovernanceFeedbackRow } from '../../api/admin/governanceWorkflow'
import GovernanceHistoryDialog from './GovernanceHistoryDialog.vue'
const route=useRoute()
const context=useAdminContextStore(),iam=useIamS1Store(),rows=ref<GovernanceFeedbackRow[]>([]),total=ref(0),page=ref(1),loading=ref(false),error=ref(''),busy=ref(false)
const current=ref<GovernanceFeedbackRow>(),reason=ref('UNKNOWN'),column=ref<number>(),note=ref('')
const historyId=ref<number>(),scenario=ref(''),expectedResult=ref(''),actualResult=ref(''),evidenceReference=ref(''),verificationConfirmed=ref(false)
const verifying=computed(()=>current.value?.followupStatus==='AWAITING_VERIFICATION')
const needsAcceptance=computed(()=>current.value?.feedbackType==='DISLIKE'&&reason.value.startsWith('FIELD_'))
const canView=computed(()=>!!context.datasourceId&&(!route.query.datasourceId||Number(route.query.datasourceId)===context.datasourceId)&&iam.canOnDatasource('governance:field:view',context.datasourceId))
const canManage=computed(()=>canView.value&&iam.canOnDatasource('governance:field:manage',context.datasourceId))
const reasons:Record<string,string>={UNKNOWN:'原因待分析',FIELD_MEANING:'字段含义',FIELD_USAGE:'字段使用条件',RELATION:'表关系',METRIC:'指标口径',SQL:'SQL 实现',PRESENTATION:'结果呈现'}
const states:Record<string,string>={PENDING_ATTRIBUTION:'待归因',PENDING_REVIEW:'待审核',APPLIED:'已计入字段置信度',REVIEWED:'已确认其他原因',REJECTED:'不计入置信度'}
let epoch=0,operation=0,disposed=false
async function load(){const ticket=++epoch,ds=context.datasourceId;rows.value=[];total.value=0;error.value='';current.value=undefined;historyId.value=undefined;if(!canView.value||!ds)return;loading.value=true
  try{const result=await listGovernanceFeedback(ds,page.value);if(!disposed&&ticket===epoch){rows.value=result.data.records;total.value=result.data.total}}
  catch{if(!disposed&&ticket===epoch)error.value='反馈记录读取失败，请重试。'}finally{if(!disposed&&ticket===epoch)loading.value=false}
}
function open(row:GovernanceFeedbackRow){current.value=row;reason.value=row.reasonCode;column.value=row.columnMetaId;note.value='';scenario.value='';expectedResult.value='';actualResult.value='';evidenceReference.value='';verificationConfirmed.value=false}
function closeDialog(visible:boolean){if(!visible)current.value=undefined}
async function review(approved:boolean){const row=current.value,ds=context.datasourceId;if(!row||!canManage.value||!note.value.trim())return;const action=++operation;busy.value=true
  try{await reviewGovernanceFeedback(row.id,{expectedVersion:row.rowVersion,approved,columnMetaId:column.value,reasonCode:reason.value,note:note.value,scenario:scenario.value,expectedResult:expectedResult.value});if(ds===context.datasourceId&&!disposed&&action===operation){ElMessage.success('归因结果已保存');await load()}}
  catch{if(ds===context.datasourceId&&!disposed&&action===operation)ElMessage.error('处理未完成，请检查实际使用证据、知识版本或反馈是否已变化')}
  finally{if(!disposed&&action===operation)busy.value=false}
}
async function verifyFix(){const row=current.value,ds=context.datasourceId;if(!row?.currentRevisionId||!canManage.value||!verificationConfirmed.value)return;const action=++operation;busy.value=true
  try{await verifyGovernanceFeedback(row.id,{expectedVersion:row.rowVersion,targetRevisionId:row.currentRevisionId,actualResult:actualResult.value,evidenceReference:evidenceReference.value,confirmed:verificationConfirmed.value});if(!disposed&&ds===context.datasourceId&&action===operation){ElMessage.success('已记录人工修复验收');await load()}}
  catch{if(!disposed&&ds===context.datasourceId&&action===operation)ElMessage.error('实际结果不符合原标准，或修复版本/反馈已变化；问题保持待验证')}
  finally{if(!disposed&&action===operation)busy.value=false}
}
watch(()=>[context.datasourceId,canView.value],()=>{operation++;busy.value=false;page.value=1;load()},{immediate:true})
onBeforeUnmount(()=>{disposed=true;epoch++})
</script>
<template>
  <section class="feedback-queue"><div class="feedback-heading"><h3>问数反馈归因</h3><el-button :disabled="!canView||busy" @click="load">刷新</el-button></div>
    <p v-if="!canView">请先选择有字段查看权限的数据源。</p><p v-else-if="error" role="alert">{{error}}</p>
    <template v-else><el-table :data="rows" v-loading="loading" empty-text="暂无问数反馈。没有评价不代表知识已验证正确。">
      <el-table-column label="评价" width="100"><template #default="{row}">{{row.feedbackType==='LIKE'?'认可':'有问题'}}</template></el-table-column>
      <el-table-column label="问题方向" width="140"><template #default="{row}">{{reasons[row.reasonCode]||row.reasonCode}}</template></el-table-column>
      <el-table-column prop="comment" label="用户补充" min-width="220" />
      <el-table-column label="处理结果" min-width="180"><template #default="{row}">{{states[row.status]||row.status}}</template></el-table-column>
      <el-table-column label="操作" min-width="190"><template #default="{row}"><el-button v-if="row.status.startsWith('PENDING')" link type="primary" :disabled="!canManage||busy" @click="open(row)">分析与审核</el-button><el-button v-else-if="row.followupStatus==='AWAITING_VERIFICATION'" link type="primary" :disabled="!canManage||busy" @click="open(row)">验证原问题</el-button><span v-else>{{row.followupStatus==='VERIFIED'?'人工核验通过':row.reviewNote}}</span><el-button link :disabled="busy" @click="historyId=row.id">操作记录</el-button></template></el-table-column>
    </el-table><el-pagination v-if="total>20" v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next,total" @current-change="load" /></template>
    <el-dialog :model-value="!!current" :title="verifying?'验证原问题修复':'分析反馈原因'" width="min(640px,94vw)" @update:model-value="closeDialog">
      <p>{{current?.comment||'用户未补充原因，请依据实际查询与业务资料核对。'}}</p>
      <template v-if="verifying"><p>原复现情景：{{current?.resolution?.scenario}}</p><p>原预期结果：{{current?.resolution?.expectedResult}}</p><el-form label-position="top"><el-form-item label="按原情景核对后的实际结果"><el-input v-model="actualResult" type="textarea" maxlength="4000" /></el-form-item><el-form-item label="验收证据记录位置"><el-input v-model="evidenceReference" maxlength="2000" /></el-form-item><el-checkbox v-model="verificationConfirmed">已在平台内核对原问题及实际结果，确认修复；不以版本变化替代验收</el-checkbox></el-form></template>
      <el-form v-else label-position="top"><el-form-item label="确认的问题方向"><el-select v-model="reason"><el-option v-for="(label,key) in reasons" :key="key" :value="key" :label="label" /></el-select></el-form-item>
        <el-form-item v-if="reason.startsWith('FIELD_')" label="实际使用字段"><el-select v-model="column"><el-option v-for="target in current?.targets||[]" :key="target.columnMetaId" :value="target.columnMetaId" :label="target.fieldName" /></el-select></el-form-item>
        <el-form-item label="归因依据与审核意见"><el-input v-model="note" type="textarea" :rows="4" maxlength="8000" /></el-form-item>
        <template v-if="needsAcceptance"><el-form-item label="原问题的复现情景"><el-input v-model="scenario" type="textarea" maxlength="2000" /></el-form-item><el-form-item label="修复后的预期结果（作为固定验收标准）"><el-input v-model="expectedResult" type="textarea" maxlength="4000" /></el-form-item></template>
      </el-form><p class="feedback-hint">只有能够定位到实际使用字段及其语义版本的已确认反馈才计分。表关系、指标和 SQL 问题分别保留原因。</p>
      <template #footer><el-button :disabled="busy" @click="current=undefined">关闭</el-button><el-button v-if="verifying" type="primary" :loading="busy" :disabled="!verificationConfirmed||!actualResult.trim()||!evidenceReference.trim()" @click="verifyFix">确认人工核验</el-button><el-button v-if="!verifying" :disabled="busy||!note.trim()" @click="review(false)">不计入置信度</el-button><el-button v-if="!verifying" type="primary" :loading="busy" :disabled="!note.trim()||(reason.startsWith('FIELD_')&&!column)||(needsAcceptance&&(!scenario.trim()||!expectedResult.trim()))" @click="review(true)">确认归因</el-button></template>
    </el-dialog>
    <GovernanceHistoryDialog v-if="historyId" :id="historyId" kind="FEEDBACK" @close="historyId=undefined" />
  </section>
</template>
<style scoped>.feedback-queue{display:grid;gap:16px;min-width:0}.feedback-heading{display:flex;align-items:center;justify-content:space-between}.feedback-hint{font-size:13px;color:var(--do-ink-muted)}.feedback-queue :deep(.el-select){width:100%}</style>
