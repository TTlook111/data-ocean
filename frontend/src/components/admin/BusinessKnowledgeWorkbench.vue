<script setup lang="ts">
import { computed,onBeforeUnmount,reactive,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ElMessage,ElMessageBox } from 'element-plus'
import { useAdminContextStore } from '../../stores/adminContext'
import { useIamS1Store } from '../../stores/iamS1'
import { listBusinessKnowledge,saveBusinessKnowledge,actBusinessKnowledge,governanceError,businessSourceStatus,type BusinessSourceItem,type BusinessSourceStatus,type BusinessKnowledge,type BusinessKnowledgeDraft } from '../../api/admin/governanceWorkflow'
import { listSnapshotTables,listSnapshotTableColumns } from '../../api/admin/governance'
import GovernanceHistoryDialog from './GovernanceHistoryDialog.vue'
import {generateFromSnapshot} from '../../api/admin/knowledge'
const context=useAdminContextStore(),iam=useIamS1Store(),route=useRoute(),router=useRouter()
const ready=computed(()=>!!context.snapshotId&&!!context.datasourceId&&(!route.query.datasourceId||Number(route.query.datasourceId)===context.datasourceId)&&(!route.query.snapshotId||Number(route.query.snapshotId)===context.snapshotId))
const canView=computed(()=>ready.value&&iam.canOnDatasource('knowledge:view',context.datasourceId))
const canManage=computed(()=>canView.value&&iam.canOnDatasource('knowledge:manage',context.datasourceId))
const canApprove=computed(()=>canView.value&&iam.canOnDatasource('knowledge:approve',context.datasourceId))
const rows=ref<BusinessKnowledge[]>([]),loading=ref(false),busy=ref(false),error=ref(''),dialog=ref(false)
const showHistory=ref(false)
const historicalCount=computed(()=>rows.value.filter(row=>['SUPERSEDED_DRAFT','SUPERSEDED'].includes(row.status)).length)
const visibleRows=computed(()=>showHistory.value?rows.value:rows.value.filter(row=>!['SUPERSEDED_DRAFT','SUPERSEDED'].includes(row.status)))
const historyId=ref<number>()
const sourceInfo=ref<BusinessSourceStatus>(),sourceLoading=ref(false),sourceError=ref(''),sourceConfirmed=ref(false)
const sourcePage=ref(1)
const options=ref<Array<{value:string;label:string}>>([]),selectedTables=ref<string[]>([])
const form=reactive<BusinessKnowledgeDraft>({kind:'DATASOURCE',knowledgeKey:'',title:'',body:{},dependencies:[],evidence:'',unresolvedItems:[]})
const unresolved=ref('')
const kinds:Record<string,string>={DATASOURCE:'数据源用途',TABLE:'表用途与粒度',RELATION:'表关系',METRIC:'指标口径'}
const labels:Record<string,string>={description:'业务说明',scope:'适用业务范围',grain:'一行数据代表什么',joinCondition:'完整关联条件',cardinality:'关系基数（如一对多）',aggregationNotes:'聚合与重复计数注意事项',formula:'计算公式',conditions:'必要筛选条件',limitations:'使用限制'}
const bodyKeys=computed(()=>form.kind==='DATASOURCE'?['description','scope','limitations']:form.kind==='TABLE'?['description','grain','limitations']:form.kind==='RELATION'?['description','joinCondition','cardinality','aggregationNotes','limitations']:['description','formula','conditions','limitations'])
const statusLabels:Record<string,string>={NOT_APPLICABLE:'已记录不适用',SUPERSEDED_DRAFT:'已由新草稿替代',DRAFT:'待人工补充',PENDING_REVIEW:'待审核',APPROVED:'待复查',VALID:'知识复查通过',REJECTED:'已退回',REVOKED:'已撤回',SUPERSEDED:'历史版本'}
let epoch=0,operation=0,sourceRequest=0,disposed=false
async function load(){const ticket=++epoch,snapshot=context.snapshotId;rows.value=[];error.value='';dialog.value=false;historyId.value=undefined;sourceRequest++
  showHistory.value=false
  if(!canView.value||!snapshot)return;loading.value=true
  try{const result=await listBusinessKnowledge(snapshot);if(!disposed&&ticket===epoch&&snapshot===context.snapshotId)rows.value=result.data}
  catch{if(!disposed&&ticket===epoch)error.value='业务知识读取失败，请重试。'}finally{if(!disposed&&ticket===epoch)loading.value=false}
}
async function open(row?:BusinessKnowledge){if(!canView.value||!context.snapshotId||!row&&!canManage.value)return
  sourceRequest++;sourceLoading.value=false;form.expectedRevisionId=row?.id;form.expectedVersion=row?.rowVersion;form.confirmedSourceHash=undefined;sourceInfo.value=undefined;sourceError.value='';sourceConfirmed.value=false
  form.kind=row?.kind||'DATASOURCE';form.knowledgeKey=row?row.knowledgeKey.replace(/^[^:]+:/,''):crypto.randomUUID();form.title=row?.title||''
  form.evidenceReference=row?.evidenceConfirmation?.reference||'';form.evidenceConfirmed=false;form.body={...(row?.body||{})};form.dependencies=[...(row?.dependencies||[])];form.evidence=row?.evidence||'';unresolved.value=(row?.unresolvedItems||[]).join('\n')
  options.value=form.dependencies.map(value=>({value,label:value.replace(/^(table:|column:)/,'')}));selectedTables.value=form.dependencies.filter(d=>d.startsWith('table:')).map(d=>d.slice(6));dialog.value=true
  if(!canManage.value)return
  const snapshot=context.snapshotId,ticket=epoch
  try{const result=await listSnapshotTables(snapshot);if(disposed||ticket!==epoch||!dialog.value)return
    options.value=result.data.filter(t=>!['BLOCKED','DEPRECATED'].includes(t.governanceStatus||'')).map(t=>({value:`table:${t.tableName}`,label:t.tableName}))
    await loadColumnOptions()
  }catch{if(snapshot===context.snapshotId&&!disposed&&ticket===epoch)ElMessage.error('元数据范围读取失败，已有依赖保持不变')}
}
async function checkSources(){if(!form.expectedRevisionId)return;const snapshot=context.snapshotId,id=form.expectedRevisionId,ticket=epoch,request=++sourceRequest;sourceLoading.value=true;sourceError.value=''
  try{const dependencies=new Set(form.dependencies);for(const dep of form.dependencies)if(dep.startsWith('column:'))dependencies.add(`table:${dep.slice(7).split('.')[0]}`)
    const result=await businessSourceStatus(id,[...dependencies]);if(disposed||ticket!==epoch||request!==sourceRequest||snapshot!==context.snapshotId||id!==form.expectedRevisionId||!dialog.value)return;sourceInfo.value=result.data;sourceConfirmed.value=false;sourcePage.value=1}
  catch(error){if(!disposed&&ticket===epoch&&request===sourceRequest)sourceError.value=governanceError(error,'来源核对未完成，请重试')}
  finally{if(!disposed&&ticket===epoch&&request===sourceRequest)sourceLoading.value=false}
}
function describeSource(item:BusinessSourceItem){return item.reviewedSemantics?.qualified?`${item.reviewedSemantics.body||''}\n语义来源编号：${item.reviewedSemantics.sourceIds?.join(' / ')||'未记录'}`:`${item.dataType||'表结构'} · 原始说明：${item.comment||item.tableComment||'未填写'}${item.columnName?'；目前没有可用的已审核字段解释':''}`}
const allSourcePairs=computed(()=>{const data=sourceInfo.value;if(!data)return [];return [...new Set([...data.original,...data.current].map(item=>item.dependency))].map(dependency=>({dependency,before:data.original.find(item=>item.dependency===dependency),after:data.current.find(item=>item.dependency===dependency)}))})
const sourcePairs=computed(()=>allSourcePairs.value.slice((sourcePage.value-1)*10,sourcePage.value*10))
async function loadColumnOptions(){if(!context.snapshotId||!dialog.value)return;const snapshot=context.snapshotId,ticket=epoch,names=[...selectedTables.value]
  try{const responses=await Promise.all(names.map(name=>listSnapshotTableColumns(snapshot,name)))
    if(disposed||ticket!==epoch||!dialog.value||names.join()!==selectedTables.value.join())return
    options.value=[...options.value.filter(o=>o.value.startsWith('table:')),...responses.flatMap((r,i)=>r.data.filter(c=>!['BLOCKED','DEPRECATED'].includes(c.governanceStatus||'')).map(c=>({value:`column:${names[i]}.${c.columnName}`,label:`${names[i]}.${c.columnName}`})))]
  }catch{if(!disposed&&snapshot===context.snapshotId&&ticket===epoch)ElMessage.error('字段依赖读取失败，请重试')}
}
async function save(ai=false){if(!canManage.value||!context.snapshotId||!form.title.trim()||!form.dependencies.length)return;const snapshot=context.snapshotId,action=++operation;busy.value=true
  const deps=new Set(form.dependencies);for(const dep of form.dependencies)if(dep.startsWith('column:'))deps.add(`table:${dep.slice(7).split('.')[0]}`)
  const payload={...form,body:Object.fromEntries(bodyKeys.value.map(k=>[k,form.body[k]||''])),dependencies:[...deps],unresolvedItems:unresolved.value.split('\n').map(s=>s.trim()).filter(Boolean)}
  payload.confirmedSourceHash=sourceConfirmed.value?sourceInfo.value?.currentHash:undefined
  try{await saveBusinessKnowledge(snapshot,payload,ai);if(snapshot!==context.snapshotId||disposed||action!==operation)return;ElMessage.success(ai?'AI 候选已保存，请编辑并人工确认':'新修订已保存，仍需审核与复查');dialog.value=false;await load()}
  catch(error){if(snapshot===context.snapshotId&&!disposed&&action===operation)ElMessage.error(governanceError(error,'保存未完成，请核对来源、内容与权限；AI 不可用时可人工保存'))}
  finally{if(!disposed&&action===operation)busy.value=false}
}
function openDocuments(){if(!canView.value)return;router.push({path:'/admin/semantics/knowledge',query:{datasourceId:context.datasourceId,snapshotId:context.snapshotId}})}
async function generateDocuments(){if(!canManage.value||!context.snapshotId||!context.datasourceId)return;const snapshot=context.snapshotId,ds=context.datasourceId,action=++operation;busy.value=true
  try{const result=await generateFromSnapshot(ds,snapshot);if(disposed||snapshot!==context.snapshotId||ds!==context.datasourceId||action!==operation)return;ElMessage.success(`已生成 ${result.data?.length||0} 篇主题草稿，仍需审核发布和单独确认 RAG 构建`);openDocuments()}
  catch(error){if(!disposed&&snapshot===context.snapshotId&&action===operation)ElMessage.error(governanceError(error,'文档生成未完成，请检查来源和权限'))}finally{if(!disposed&&action===operation)busy.value=false}
}
async function action(row:BusinessKnowledge,command:string){if(busy.value)return;const snapshot=context.snapshotId,scopeOperation=operation;let note=''
  if(['APPROVE','REJECT','REVOKE','NOT_APPLICABLE'].includes(command)){try{note=(await ElMessageBox.prompt('请填写业务核对依据或处理原因','处理业务知识',{inputType:'textarea',inputValidator:v=>!!v?.trim()||'请填写原因'})).value}catch{return}}
  if(snapshot!==context.snapshotId||disposed||scopeOperation!==operation)return;const action=++operation;busy.value=true
  try{await actBusinessKnowledge(row.id,row.rowVersion,command,note);if(snapshot===context.snapshotId&&!disposed&&action===operation){ElMessage.success(command==='RECHECK'?'知识已复查；文档发布和 RAG 生效仍需分别核对':'操作成功');await load()}}
  catch(error){if(snapshot===context.snapshotId&&!disposed&&action===operation)ElMessage.error(governanceError(error,'操作未完成，可能存在待确认项、来源变化或并发修订'))}
  finally{if(!disposed&&action===operation)busy.value=false}
}
watch(()=>[context.snapshotId,context.datasourceId,canView.value],()=>{operation++;busy.value=false;load()},{immediate:true})
onBeforeUnmount(()=>{disposed=true;epoch++})
</script>
<template>
  <section class="business-workbench">
    <div class="business-heading"><div><h3>业务知识</h3><p>先确认数据源用途、表粒度、关系和指标，再生成可追溯的知识文档。</p></div><div><el-button :disabled="!canView||busy" @click="load">刷新</el-button><el-button :disabled="!canView||busy" @click="openDocuments">文档与构建</el-button><el-button :disabled="!canManage||busy" @click="generateDocuments">生成主题文档</el-button><el-button type="primary" :disabled="!canManage||busy" @click="open()">补充业务知识</el-button></div></div>
    <el-checkbox v-if="canView&&!error&&historicalCount" v-model="showHistory">显示历史修订（{{historicalCount}}）</el-checkbox>
    <p v-if="!ready">请先选择数据源与快照。</p><p v-else-if="!canView">没有当前范围的知识查看权限。</p>
    <p v-else-if="error" role="alert">{{error}}</p>
    <el-table v-else :data="visibleRows" v-loading="loading" empty-text="尚无业务知识，请补充已有资料，或让 AI 整理首轮候选。">
      <el-table-column prop="title" label="知识主题" min-width="180" />
      <el-table-column label="类型" width="140"><template #default="{row}">{{kinds[row.kind]}}</template></el-table-column>
      <el-table-column label="阶段" width="130"><template #default="{row}">{{row.effectiveStatus==='NEEDS_REVIEW'?'来源需复核':statusLabels[row.status]||row.status}}</template></el-table-column>
      <el-table-column label="待确认" width="100"><template #default="{row}">{{row.unresolvedItems.length}} 项</template></el-table-column>
      <el-table-column label="操作" min-width="270"><template #default="{row}">
        <el-button link type="primary" :disabled="busy" @click="open(row)">{{canManage?'查看与修订':'查看'}}</el-button>
        <el-button link :disabled="busy" @click="historyId=row.id">操作记录</el-button>
        <el-button v-if="row.status==='DRAFT'" link :disabled="!canManage||busy" @click="action(row,'SUBMIT')">提交审核</el-button>
        <el-button v-if="['DRAFT','REJECTED'].includes(row.status)" link :disabled="!canManage||busy" @click="action(row,'NOT_APPLICABLE')">标记不适用</el-button>
        <template v-if="row.status==='PENDING_REVIEW'"><el-button link :disabled="!canApprove||busy" @click="action(row,'APPROVE')">通过</el-button><el-button link :disabled="!canApprove||busy" @click="action(row,'REJECT')">退回</el-button></template>
        <el-button v-if="row.status==='APPROVED'" link :disabled="!canManage||busy" @click="action(row,'RECHECK')">系统复查</el-button>
        <el-button v-if="row.status==='VALID'" link type="danger" :disabled="!canManage||!canApprove||busy" @click="action(row,'REVOKE')">撤回</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="dialog" title="业务知识修订" width="min(760px,94vw)" :close-on-click-modal="false">
      <el-form label-position="top" :disabled="busy||!canManage">
        <div class="business-grid"><el-form-item label="知识类型"><el-select v-model="form.kind" :disabled="!!form.expectedRevisionId"><el-option v-for="(label,key) in kinds" :key="key" :value="key" :label="label" /></el-select></el-form-item><el-form-item label="主题"><el-input v-model="form.title" maxlength="200" /></el-form-item></div>
        <el-form-item label="关联表"><el-select v-model="selectedTables" multiple filterable @change="loadColumnOptions"><el-option v-for="o in options.filter(o=>o.value.startsWith('table:'))" :key="o.value" :label="o.label" :value="o.label" /></el-select></el-form-item>
        <el-form-item label="知识依赖的表与字段"><el-select v-model="form.dependencies" multiple filterable><el-option v-for="o in options" :key="o.value" :label="o.label" :value="o.value" /></el-select></el-form-item>
        <el-form-item v-for="key in bodyKeys" :key="key" :label="labels[key]"><el-input v-model="form.body[key]" type="textarea" :rows="2" maxlength="16000" /></el-form-item>
        <el-form-item label="依据引用（资料名称、版本及章节，或平台记录位置）"><el-input v-model="form.evidenceReference" maxlength="2000" placeholder="例如：订单业务手册 v2，第 3.1 节" /></el-form-item>
        <el-form-item label="原始资料摘录与人工确认说明"><el-input v-model="form.evidence" type="textarea" :rows="3" maxlength="8000" /></el-form-item>
        <el-checkbox v-model="form.evidenceConfirmed">我已核对上述原始资料，并确认其支持当前修订；AI 推断不能替代依据</el-checkbox>
        <p v-if="['RELATION','METRIC'].includes(form.kind)">公式与关联条件请使用真实表名.字段名；首期不支持别名、子查询或窗口，业务解释填写在使用限制中。</p>
        <el-form-item label="尚未确认的问题（每行一项）"><el-input v-model="unresolved" type="textarea" :rows="2" placeholder="有疑问时保留，明确后再提交审核" /></el-form-item>
      </el-form>
      <section v-if="form.expectedRevisionId" class="business-source-review"><el-button :loading="sourceLoading" @click="checkSources">核对已保存修订的来源</el-button><p v-if="sourceError" role="alert">{{sourceError}}</p>
        <template v-if="sourceInfo"><p>{{sourceInfo.sourceChanged?'依赖字段、审核或结构来源已经变化，请核对业务口径。':'依赖来源与本修订保存时一致。'}}</p><p v-if="sourceInfo.reason" role="alert">{{sourceInfo.reason}}</p>
          <el-collapse><el-collapse-item v-for="pair in sourcePairs" :key="pair.dependency" :title="pair.dependency.replace(/^(table:|column:)/,'')" :name="pair.dependency"><div class="business-grid"><div><strong>保存时的依据</strong><p class="business-source-text">{{pair.before?describeSource(pair.before):'本次新增的依赖。'}}</p></div><div><strong>当前依据</strong><p class="business-source-text">{{pair.after?describeSource(pair.after):sourceInfo.reason?'当前来源无法完整核验，请先处理上方原因。':'本次不再纳入此依赖。'}}</p></div></div></el-collapse-item></el-collapse>
          <el-pagination v-if="allSourcePairs.length>10" v-model:current-page="sourcePage" :total="allSourcePairs.length" :page-size="10" layout="prev,pager,next,total" />
          <el-checkbox v-if="sourceInfo.sourceChanged&&sourceInfo.currentHash&&canManage" v-model="sourceConfirmed">已核对上述来源变化，并相应确认或修订业务口径</el-checkbox>
        </template>
      </section>
      <template #footer><el-button :disabled="busy" @click="dialog=false">关闭</el-button><template v-if="canManage"><el-button :loading="busy" :disabled="!form.title.trim()||!form.dependencies.length" @click="save(true)">AI 整理候选</el-button><el-button type="primary" :loading="busy" :disabled="!form.title.trim()||!form.dependencies.length" @click="save(false)">保存新修订</el-button></template></template>
    </el-dialog>
    <GovernanceHistoryDialog v-if="historyId" :id="historyId" kind="BUSINESS" @close="historyId=undefined" />
  </section>
</template>
<style scoped>
.business-workbench{display:grid;gap:16px;min-width:0}.business-heading{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px}.business-heading h3{margin:0}.business-heading p{font-size:13px;color:var(--do-ink-muted)}.business-grid{display:grid;grid-template-columns:1fr 2fr;gap:16px}.business-workbench :deep(.el-select){width:100%}@media(max-width:640px){.business-grid{grid-template-columns:1fr}}
.business-source-review{border-top:1px solid var(--do-line);padding-top:12px}.business-source-text{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}
</style>
