<script setup lang="ts">
import {ref,watch} from 'vue'
import {readGovernanceHistory,type GovernanceHistoryRow} from '../../api/admin/governanceWorkflow'
const props=defineProps<{id:number;kind:'BUSINESS'|'FEEDBACK'}>()
const emit=defineEmits<{close:[]}>()
const rows=ref<GovernanceHistoryRow[]>([]),loading=ref(false),error=ref('')
const actions:Record<string,string>={GOVERNANCE_RECHECKED:'历史知识修订复查记录',RECHECK_AWAITING_VERIFICATION:'知识已复查，原问题待验收',RESOLUTION_VERIFIED:'人工验证原问题修复',DRAFT_CREATED:'建立新修订',SUBMIT:'提交审核',APPROVE:'审核通过',REJECT:'审核退回',RECHECK:'系统复查通过',REVOKE:'撤回有效知识',SUBMITTED:'提交或更新评价',CONFIRMED:'确认反馈归因',REJECTED:'不计入置信度'}
watch(()=>[props.id,props.kind],async(_,__,cleanup)=>{let active=true;cleanup(()=>{active=false});rows.value=[];error.value='';loading.value=true
  try{const result=await readGovernanceHistory(props.kind,props.id);if(active)rows.value=result.data}
  catch{if(active)error.value='操作记录读取失败，请关闭后重试。'}finally{if(active)loading.value=false}
},{immediate:true})
</script>
<template><el-dialog :model-value="true" title="操作与审核记录" width="min(720px,94vw)" @closed="emit('close')" @update:model-value="emit('close')">
  <p v-if="loading" role="status">正在读取真实操作记录…</p><p v-else-if="error" role="alert">{{error}}</p><p v-else-if="!rows.length">暂无操作记录。</p>
  <ol v-else class="governance-history"><li v-for="row in rows" :key="row.id"><div><strong>{{actions[row.action]||row.action}}</strong><time>{{row.createdAt.replace('T',' ')}}</time></div>
    <p>{{row.payload.note||row.payload.comment||(row.payload.origin==='AI'?'AI 整理的待确认候选':row.payload.origin==='SYSTEM'?'规则发现的业务知识缺口':'本次操作已留存来源和版本证据')}}</p>
    <small>操作者编号 {{row.operatorId}}</small>
  </li></ol><p v-if="rows.length>=100">当前展示最近 100 条操作记录。</p>
  <template #footer><el-button @click="emit('close')">关闭</el-button></template>
</el-dialog></template>
<style scoped>.governance-history{list-style:none;padding:0;display:grid;gap:16px}.governance-history li{border-bottom:1px solid var(--do-line);padding-bottom:12px}.governance-history li>div{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px}.governance-history time,.governance-history small{font-size:12px;color:var(--do-ink-muted)}.governance-history p{white-space:pre-wrap;overflow-wrap:anywhere}</style>
