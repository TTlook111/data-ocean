<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAdminContextStore } from '../../stores/adminContext'
import { useIamS1Store } from '../../stores/iamS1'
import { pauseGovernance,governanceError,discoverGovernance,listGovernanceTasks,retryGovernance,getGovernanceRules,type GovernanceTaskRow } from '../../api/admin/governanceWorkflow'
import { runSemanticCheck } from '../../api/admin/fieldSemantic'
const context=useAdminContextStore(), iam=useIamS1Store(), router=useRouter(),route=useRoute()
const rows=ref<GovernanceTaskRow[]>([]),total=ref(0),page=ref(1),loading=ref(false),busy=ref(false),error=ref('')
const summary=ref({queued:0,running:0,failed:0}),rules=ref<Array<{code:string;name:string;target:string;requirement:string;version:number}>>([]),rulesError=ref('')
const ready=computed(()=>!!context.snapshotId&&!!context.datasourceId&&(!route.query.datasourceId||Number(route.query.datasourceId)===context.datasourceId)&&(!route.query.snapshotId||Number(route.query.snapshotId)===context.snapshotId))
const canView=computed(()=>ready.value&&iam.canOnDatasource('governance:field:view',context.datasourceId))
const canStart=computed(()=>canView.value&&iam.canOnDatasource('governance:check',context.datasourceId)&&iam.canOnDatasource('governance:field:manage',context.datasourceId)&&iam.canOnDatasource('knowledge:manage',context.datasourceId)&&iam.canOnDatasource('knowledge:view',context.datasourceId))
const canCheck=computed(()=>canView.value&&iam.canOnDatasource('governance:check',context.datasourceId))
let epoch=0,operation=0,disposed=false,timer:ReturnType<typeof setTimeout>|undefined
const stages:Record<string,string>={NEEDS_CLASSIFICATION:'待补充标签',NEEDS_GOVERNANCE:'待治理',HUMAN_EDITING:'人工补充',AWAITING_REVIEW:'待审核',AWAITING_RECHECK:'待复查',ROUND_COMPLETED:'本轮完成',NOT_APPLICABLE:'不适用'}
const aiLabels:Record<string,string>={QUEUED:'AI 待处理',AI_RUNNING:'AI 处理中',AI_READY:'候选已就绪',AI_FAILED:'AI 未完成',HUMAN_READY:'人工接管'}
async function load(){
  const ticket=++epoch,snapshot=context.snapshotId
  if(timer)clearTimeout(timer)
  rows.value=[];total.value=0;summary.value={queued:0,running:0,failed:0};error.value=''
  if(!canView.value||!snapshot){loading.value=false;return}
  loading.value=true
  try{const result=await listGovernanceTasks(snapshot,page.value)
    if(disposed||ticket!==epoch||snapshot!==context.snapshotId)return
    rows.value=result.data.records;total.value=result.data.total
    summary.value=result.data.aiSummary||{queued:0,running:0,failed:0}
    if(summary.value.queued||summary.value.running)timer=setTimeout(load,5000)
  }catch{if(!disposed&&ticket===epoch)error.value='治理任务读取失败，请重试。'}
  finally{if(!disposed&&ticket===epoch)loading.value=false}
}
async function start(ai:boolean){if(!canStart.value||!context.snapshotId)return;const snapshot=context.snapshotId,action=++operation;busy.value=true
  try{const result=await discoverGovernance(snapshot,ai);if(snapshot!==context.snapshotId||disposed||action!==operation)return;ElMessage.success(`已扫描 ${result.data.scanned} 个字段，新增 ${result.data.created} 项治理任务`);page.value=1;await load()}
  catch(error){if(snapshot===context.snapshotId&&!disposed&&action===operation)ElMessage.error(governanceError(error,'任务创建失败，请检查权限或重试'))}
  finally{if(!disposed&&action===operation)busy.value=false}
}
async function check(){if(!canCheck.value||!context.snapshotId)return;const snapshot=context.snapshotId,action=++operation;busy.value=true
  try{const response=await runSemanticCheck(snapshot);if(snapshot!==context.snapshotId||disposed||action!==operation)return
    if(response.data.status!=='SUCCESS')throw new Error();ElMessage.success('复查完成，未通过项继续保留');await load()
  }catch{if(snapshot===context.snapshotId&&!disposed&&action===operation)ElMessage.error('复查未完成，请重试')}
  finally{if(!disposed&&action===operation)busy.value=false}
}
async function retry(row:GovernanceTaskRow){if(!canStart.value)return;const action=++operation;busy.value=true;try{await retryGovernance(row.id);if(!disposed&&action===operation)await load()}catch{if(!disposed&&action===operation)ElMessage.error('重试失败，字段可能已有新修订')}finally{if(!disposed&&action===operation)busy.value=false}}
async function pause(){if(!canStart.value||!context.snapshotId)return;const snapshot=context.snapshotId,action=++operation;busy.value=true
 try{await pauseGovernance(snapshot);if(!disposed&&action===operation&&snapshot===context.snapshotId){ElMessage.success('本轮 AI 已暂停，可人工接管');await load()}}
 catch{if(!disposed&&action===operation)ElMessage.error('暂停未完成，请刷新后核对任务状态')}
 finally{if(!disposed&&action===operation)busy.value=false}
}
async function loadRules(active:unknown){if(!(Array.isArray(active)?active.length:!!active)||!canView.value||rules.value.length)return;rulesError.value='';try{rules.value=(await getGovernanceRules()).data}catch{rulesError.value='检查规则说明读取失败，请重新展开重试。'}}
function open(row:GovernanceTaskRow){
  if(row.targetType==='BUSINESS'){router.push({path:'/admin/governance/fields',query:{tab:'business',datasourceId:context.datasourceId,snapshotId:context.snapshotId}});return}
  router.push({path:row.stage==='NEEDS_CLASSIFICATION'?'/admin/governance/fields':'/admin/semantics/field-explanations',query:{...(row.stage==='NEEDS_CLASSIFICATION'?{tab:'tags'}:{}),datasourceId:context.datasourceId,snapshotId:context.snapshotId,tableName:row.tableName,columnMetaId:row.columnMetaId}})
}
watch(()=>[context.snapshotId,context.datasourceId,canView.value],()=>{operation++;busy.value=false;page.value=1;load()},{immediate:true})
onBeforeUnmount(()=>{disposed=true;epoch++;if(timer)clearTimeout(timer)})
</script>
<template>
  <section class="workflow-list">
    <div class="workflow-actions"><div><h3>治理任务</h3><p>发现字段、数据源用途、表粒度和关系缺口，先由 AI 生成候选，再审核补充并复查。</p></div><div>
      <el-button :disabled="!canView||busy" @click="load">刷新</el-button>
      <el-button v-if="summary.queued||summary.running" :disabled="!canStart||busy" @click="pause">暂停 AI</el-button>
      <el-button :disabled="!canCheck||busy" @click="check">复查治理结果</el-button>
      <el-button :disabled="!canStart||busy" @click="start(false)">人工接管</el-button>
      <el-button type="primary" :disabled="!canStart||busy" :loading="busy" @click="start(true)">开始治理</el-button>
    </div></div>
    <p v-if="!ready">请先选择数据源与快照。</p><p v-else-if="!canView">没有当前范围的字段查看权限。</p>
    <div v-else-if="error" role="alert">{{error}} <el-button @click="load">重试</el-button></div>
    <template v-else>
      <p class="workflow-counts">共 {{total}} 项任务 · AI 待处理 {{summary.queued}} · 处理中 {{summary.running}} · 首轮失败 {{summary.failed}}</p>
      <el-collapse @change="loadRules"><el-collapse-item title="本次会检查什么" name="rules"><p v-if="rulesError" role="alert">{{rulesError}}</p><ul><li v-for="rule in rules" :key="rule.code"><strong>{{rule.name}}</strong> · {{rule.target}}：{{rule.requirement}}</li></ul></el-collapse-item></el-collapse>
      <el-table :data="rows" v-loading="loading" empty-text="当前还没有治理任务，选择开始治理后会按规则发现知识缺口。">
        <el-table-column label="治理对象" min-width="210"><template #default="{row}"><strong>{{row.targetType==='BUSINESS'?row.tableName:`${row.tableName}.${row.columnName}`}}</strong><p>{{row.origin==='FEEDBACK'?'来自已确认的问数反馈':'来自知识完整性检查'}}</p></template></el-table-column>
        <el-table-column label="当前阶段" min-width="120"><template #default="{row}">{{stages[row.stage]||row.stage}}</template></el-table-column>
        <el-table-column label="AI 首轮" min-width="120"><template #default="{row}">{{aiLabels[row.aiStatus]||row.aiStatus}}</template></el-table-column>
        <el-table-column prop="reason" label="处理提示" min-width="250" />
        <el-table-column label="操作" width="160"><template #default="{row}"><el-button link type="primary" @click="open(row)">处理</el-button><el-button v-if="row.aiStatus==='AI_FAILED'" link :disabled="!canStart||busy" @click="retry(row)">重试 AI</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="total>20" v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next,total" @current-change="load" />
    </template>
  </section>
</template>
<style scoped>
.workflow-list{min-width:0;display:grid;gap:16px}.workflow-actions{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px}.workflow-actions h3{margin:0 0 6px}.workflow-actions p,.workflow-list :deep(td p){font-size:12px;color:var(--do-ink-muted);margin:4px 0}.workflow-actions>div:last-child{display:flex;flex-wrap:wrap;gap:8px}
</style>
