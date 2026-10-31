<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowUpRight, CircleHelp, RefreshCw } from 'lucide-vue-next'
import { useIamWorkspaceSession } from '../composables/useIamWorkspaceSession'
import { useAdminScrollLock } from '../composables/useAdminScrollLock'
import { useAdminContextStore } from '../stores/adminContext'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'
import { getBatchDatasourceReadiness, listSimpleDatasources, type DatasourceReadiness, type DatasourceSimpleItem } from '../api/admin/datasource'
import { listIamS1AccessRequestQueue, type IamS1AccessRequestView } from '../api/iamS1'
import { buildWorkspaceTarget } from '../utils/adminNavigation'
import { getHttpErrorMessage } from '../utils/httpError'
import { positiveId, validWorkbenchReadiness, workbenchSourceAction, workbenchCondition, WORKBENCH_READINESS_LIMIT } from '../utils/workbenchPresentation'
import TaskPageHeader from '../components/admin/TaskPageHeader.vue'
import ErrorState from '../components/common/ErrorState.vue'
import LoadingState from '../components/common/LoadingState.vue'

const router=useRouter(),session=useIamWorkspaceSession(),iam=session.iam,context=useAdminContextStore()
const canViewWorkbench=computed(()=>session.canGlobal('admin:workbench:view'))
const hasScope=(code:string)=>session.ready.value&&(iam.systemAdmin||iam.datasourcesWithFunction(code).length>0)
const canViewSources=computed(()=>hasScope('datasource:view')),canViewIssues=computed(()=>hasScope('governance:issue:view'))
const canViewApprovals=computed(()=>session.canGlobal('security:approval:view'))
const canViewFields=computed(()=>hasScope('governance:field:view'))
const stats=ref<DashboardStats|null>(null),sources=ref<DatasourceSimpleItem[]>([]),readiness=ref<DatasourceReadiness[]>([]),approvals=ref<IamS1AccessRequestView[]>([])
const pendingApprovalCount=ref<number|null>(null),statsError=ref(''),readinessError=ref(''),readinessPartialError=ref(''),approvalError=ref('')
const loading=ref(false),statsLoading=ref(false),readinessLoading=ref(false),approvalLoading=ref(false),directoryKnown=ref(false),workbenchForbidden=ref(false)
const focusQuery=computed(()=>router.currentRoute?.value?.query?.focusSourceId)
const requestedFocus=computed(()=>focusQuery.value==null?undefined:typeof focusQuery.value==='string'&&/^[1-9]\d*$/.test(focusQuery.value)&&positiveId(Number(focusQuery.value))?Number(focusQuery.value):null)
const focusProblem=computed(()=>requestedFocus.value===null?'指定来源标识无效，请重新选择当前有权来源。':directoryKnown.value&&requestedFocus.value!==undefined&&!sources.value.some(s=>s.id===requestedFocus.value)?'指定来源不在当前有权列表中，请重新选择。':'')
const selectedId=ref<number>(),filter=ref<'all'|'attention'|'ready'>('all'),helpOpen=ref(false)
useAdminScrollLock(()=>helpOpen.value)
let statsRequest=0,readinessRequest=0,approvalRequest=0,loadRequest=0
const current=(ticket:number)=>session.current(ticket)&&canViewWorkbench.value&&!workbenchForbidden.value
const pendingLoading=computed(()=>loading.value||statsLoading.value||readinessLoading.value||approvalLoading.value)
const rows=computed(()=>sources.value.map(source=>({source,readiness:readiness.value.find(r=>r.datasourceId===source.id)})))
const unknownCount=computed(()=>sources.value.length-readiness.value.length)
const countsKnown=computed(()=>directoryKnown.value&&!readinessLoading.value&&!readinessError.value&&!unknownCount.value)
const attentionCount=computed(()=>readiness.value.filter(r=>!r.askable).length)
const filtered=computed(()=>rows.value.filter(row=>filter.value==='all'||(row.readiness&&(filter.value==='attention'?!row.readiness.askable:row.readiness.askable))))
const preview=computed(()=>{const list=filtered.value;const focused=list.find(r=>r.source.id===requestedFocus.value);return focused&&!list.slice(0,3).includes(focused)?[...list.slice(0,2),focused]:list.slice(0,3)})
const selected=computed(()=>focusProblem.value?undefined:preview.value.find(row=>row.source.id===(requestedFocus.value??selectedId.value)))
const action=computed(()=>selected.value?workbenchSourceAction(selected.value.source.id,selected.value.readiness):undefined)
const actionDisabledReason=computed(()=>{
 const a=action.value,id=selected.value?.source.id
 if(!a||!id)return '请先选择当前来源。'
 if(a.unavailable)return a.unavailable
 if(a.capability&&!session.can(a.capability,id))return `没有此来源的${{'metadata:release:view':'元数据版本查看','knowledge:view':'知识查看','governance:issue:view':'治理问题查看','datasource:view':'数据源查看'}[a.capability]||'目标工作区查看'}能力；准备回执不自动授予处理权限。`
 if(a.globalAny&&!a.globalAny.some(code=>session.canGlobal(code)))return '没有授权工作区查看能力；准备回执不会增加权限。'
 return ''
})
const issueCountText=computed(()=>!statsLoading.value&&!statsError.value&&canViewIssues.value&&Number.isSafeInteger(stats.value?.openIssues)&&Number(stats.value?.openIssues)>=0?String(stats.value!.openIssues):'—')
const preferred=computed(()=>{
 const s=context.currentSnapshot
 if(!context.initialized||context.loading||context.routeSyncing||context.routeSyncError||!s||s.id!==context.snapshotId||s.datasourceId!==context.datasourceId||!context.datasourceId||!session.can('metadata:view',context.datasourceId))return undefined
 return {datasourceId:context.datasourceId,snapshotId:s.id,name:context.currentDatasource?.name,version:s.snapshotVersion}
})
function clearData(){stats.value=null;sources.value=[];readiness.value=[];approvals.value=[];pendingApprovalCount.value=null;directoryKnown.value=false;statsError.value='';readinessError.value='';readinessPartialError.value='';approvalError.value=''}
async function loadStats(){const ticket=session.capture(),request=++statsRequest;stats.value=null;statsError.value='';statsLoading.value=true
 try{const result=await getDashboardStats();if(!current(ticket)||request!==statsRequest)return
  if(!result.data||!Number.isSafeInteger(result.data.openIssues)||result.data.openIssues<0)throw new Error('工作台统计回执不完整')
  stats.value=result.data
 }catch(error){if(!current(ticket)||request!==statsRequest)return
  statsError.value=getHttpErrorMessage(error,'工作台统计加载失败')
  if((error as {response?:{status?:number}})?.response?.status===403){workbenchForbidden.value=true;loadRequest++;loading.value=false;readinessLoading.value=false;approvalLoading.value=false;readinessRequest++;approvalRequest++;sources.value=[];readiness.value=[];approvals.value=[];pendingApprovalCount.value=null;helpOpen.value=false}
 }finally{if(session.current(ticket)&&request===statsRequest)statsLoading.value=false}}
async function loadReadiness(){const ticket=session.capture(),request=++readinessRequest;readinessLoading.value=true;sources.value=[];readiness.value=[];directoryKnown.value=false;readinessError.value='';readinessPartialError.value=''
 try{const result=await listSimpleDatasources();if(!current(ticket)||request!==readinessRequest)return
  if(!Array.isArray(result.data)||result.data.some(s=>!s||!positiveId(s.id)||typeof s.name!=='string')||new Set(result.data.map(s=>s.id)).size!==result.data.length)throw new Error('数据源目录回执不完整')
  sources.value=result.data.filter(s=>session.can('datasource:view',s.id));directoryKnown.value=true
  if(!sources.value.length)return
  const ids=sources.value.slice(0,WORKBENCH_READINESS_LIMIT).map(s=>s.id),response=await getBatchDatasourceReadiness(ids)
  if(!current(ticket)||request!==readinessRequest)return
  if(!Array.isArray(response.data)||response.data.some(r=>!r||!ids.includes(r.datasourceId))||new Set(response.data.map(r=>r.datasourceId)).size!==response.data.length)throw new Error('准备情况回执错属或不完整')
  readiness.value=response.data.filter(validWorkbenchReadiness)
  if(readiness.value.length<sources.value.length)readinessPartialError.value=`部分数据源的准备情况尚未确认：${sources.value.length-readiness.value.length} 个未知；不推算完整分类。`
 }catch(error){if(current(ticket)&&request===readinessRequest)readinessError.value=getHttpErrorMessage(error,'准备情况暂不可用')}
 finally{if(session.current(ticket)&&request===readinessRequest)readinessLoading.value=false}}
async function loadApprovals(){const ticket=session.capture(),request=++approvalRequest;approvalLoading.value=true;pendingApprovalCount.value=null;approvals.value=[];approvalError.value=''
 try{const result=await listIamS1AccessRequestQueue('PENDING',1,2);if(!current(ticket)||request!==approvalRequest)return
  const data=result.data
  if(!Number.isSafeInteger(data?.total)||data.total<0)throw new Error('审批总数未确认')
  if(!Array.isArray(data.records)||data.records.length>2||data.records.length>data.total||new Set(data.records.map(r=>r.id)).size!==data.records.length||data.records.some(r=>!positiveId(r.id)||!positiveId(r.datasourceId)||!positiveId(r.metadataSnapshotId)||r.status!=='PENDING'||!Array.isArray(r.requestedColumns)||!r.requestedColumns.every(c=>typeof c==='string')||typeof r.purpose!=='string'||typeof r.tableName!=='string'))throw new Error('审批预览回执不完整')
  pendingApprovalCount.value=data.total;approvals.value=data.records
 }catch(error){if(current(ticket)&&request===approvalRequest)approvalError.value=getHttpErrorMessage(error,'审批队列读取失败')}
 finally{if(session.current(ticket)&&request===approvalRequest)approvalLoading.value=false}}
async function load(preserve=false){const ticket=session.capture(),request=++loadRequest;loading.value=true;workbenchForbidden.value=false;clearData();if(!preserve){selectedId.value=undefined;filter.value='all'}
 if(!canViewWorkbench.value||!session.ready.value){loading.value=false;return}
 await Promise.all([loadStats(),...(canViewSources.value?[loadReadiness()]:[]),...(canViewApprovals.value?[loadApprovals()]:[])])
 if(session.current(ticket)&&request===loadRequest)loading.value=false
}
async function reload(){if(pendingLoading.value)return;if(!session.ready.value){await iam.load(true);return}await load(true)}
function allowed(){return current(session.capture())&&!loading.value}
function persistFocus(id?:number){const query={...(router.currentRoute?.value?.query||{})};delete query.focusSourceId;if(id)query.focusSourceId=String(id);void router.replace?.({path:'/admin/workbench',query})}
function selectSource(id:number){if(allowed()&&preview.value.some(row=>row.source.id===id)){selectedId.value=id;persistFocus(id)}}
function changeFilter(value:'all'|'attention'|'ready'){if(allowed()){filter.value=value;persistFocus()}}
function openPrimary(id:number){if(!allowed()||id!==selected.value?.source.id||actionDisabledReason.value||!action.value)return;void router.push(action.value.target)}
function navigateSource(kind:'fields'|'knowledge',id:number){if(!allowed()||selected.value?.source.id!==id||!selected.value.readiness)return
 const r=selected.value.readiness
 if(kind==='fields'){const snapshot=r.latestCollectedSnapshotId||r.publishedSnapshotId;if(!snapshot||!session.can('governance:field:view',id))return;void router.push({path:'/admin/governance/fields',query:{datasourceId:String(id),snapshotId:String(snapshot),tab:'tasks'}})}
 else if(session.can('knowledge:view',id))void router.push({path:'/admin/semantics/knowledge',query:{datasourceId:String(id),tab:'documents'}})
}
function openWorkspace(path:string,code?:string,global=false){if(!allowed()||(code&&!(global?session.canGlobal(code):hasScope(code))))return;void router.push(buildWorkspaceTarget(path,iam,preferred.value||{}))}
function fieldEntry(){openWorkspace('/admin/governance/fields','governance:field:view')}
function sourceSubtitle(r?:DatasourceReadiness){if(!r)return '准备回执读取未确认';if(r.publishedSnapshotId)return `已发布${r.snapshotVersion!=null?' v'+r.snapshotVersion:''} · 快照 #${r.publishedSnapshotId}`;if(r.latestCollectedSnapshotId)return `最新采集${r.latestCollectedSnapshotVersion!=null?' v'+r.latestCollectedSnapshotVersion:''} · 尚未发布`;return '暂无采集记录'}
const dimensions=[['connection','连接'],['metadata','元数据'],['governance','治理'],['knowledge','知识索引'],['permission','授权配置']] as const
function sourceLabel(row:typeof rows.value[number]){return `选择${row.source.name}。${dimensions.map(([key,label])=>label+workbenchCondition(row.readiness,key).text).join('，')}`}
watch(()=>preview.value.map(row=>row.source.id),ids=>{if(!ids.includes(selectedId.value!))selectedId.value=ids[0]},{flush:'sync'})
watch(session.key,()=>{statsRequest++;readinessRequest++;approvalRequest++;statsLoading.value=false;readinessLoading.value=false;approvalLoading.value=false;selectedId.value=undefined;filter.value='all';helpOpen.value=false;void load()},{immediate:true,flush:'sync'})
</script>

<template>
 <div class="admin-page workbench-page">
  <TaskPageHeader title="工作台" description="在有权范围内，把数据准备与后续工作接起来。" description-mode="inline">
   <template #actions><button class="plain" @click="helpOpen=true">准备条件说明 <CircleHelp :size="14" aria-hidden="true" /></button><el-button :icon="RefreshCw" :loading="pendingLoading" @click="reload">刷新</el-button></template>
  </TaskPageHeader>
  <LoadingState v-if="!iam.loaded && !iam.errorMessage" text="正在读取当前能力…" />
  <ErrorState v-else-if="!session.ready.value" :message="iam.errorMessage || '正在读取当前能力，请稍候。'" @retry="reload" />
  <section v-else-if="!canViewWorkbench || workbenchForbidden" class="noaccess" role="alert"><h2>{{ workbenchForbidden ? statsError : '没有“查看工作台”功能' }}</h2><p>当前没有工作台查看能力；未展示摘要或准备回执，无权不表示数量为0。</p><el-button v-if="workbenchForbidden" @click="reload">重新核验</el-button></section>
  <LoadingState v-else-if="loading" text="正在读取当前身份与负责范围…" />
  <template v-else>
   <ErrorState v-if="readinessError && canViewSources" :message="'准备情况暂不可用：'+readinessError" @retry="reload" />
   <div v-if="readinessPartialError && canViewSources" class="notice" role="status"><span>{{ readinessPartialError }}</span><button class="secondary" @click="reload">重新读取</button></div>
   <section v-if="canViewSources && (sources.length || !directoryKnown)" class="focus-board">
    <header class="board-top"><div><h2>从数据源开始工作</h2><p>先看准备条件，选中来源后核对下一步。</p></div><div class="brief-count"><template v-if="!directoryKnown"><strong>—</strong>来源数量未确认</template><template v-else-if="unknownCount"><strong>{{ unknownCount }}</strong>个准备情况未知</template><template v-else><strong>{{ attentionCount }}</strong>个来源需关注</template></div></header>
    <div class="work-grid">
     <section class="source-area" aria-label="负责范围内的数据源准备预览">
      <div class="source-tools"><div class="source-tabs" role="group" aria-label="筛选准备情况"><button v-for="item in (['all','attention','ready'] as const)" :key="item" :data-source-filter="item" :aria-pressed="filter===item" @click="changeFilter(item)">{{ {all:'全部',attention:'需关注',ready:'基础条件满足'}[item] }} <b>{{ item==='all' ? (directoryKnown?sources.length:'—') : countsKnown ? item==='attention' ? attentionCount : readiness.filter(r=>r.askable).length : '—' }}</b></button></div><button class="plain" @click="openWorkspace('/admin/data-sources','datasource:view')">查看全部 <ArrowUpRight :size="13" /></button></div>
      <div class="source-columns" aria-hidden="true"><span>数据源</span><span v-for="[,label] in dimensions" :key="label">{{ label }}</span></div>
      <button v-for="row in preview" :key="row.source.id" class="source-choice" :data-source-choice="row.source.id" :aria-label="sourceLabel(row)" :aria-pressed="selectedId===row.source.id" @click="selectSource(row.source.id)"><span class="source-name"><strong :title="row.source.name">{{ row.source.name }}</strong><small>{{ sourceSubtitle(row.readiness) }}</small></span><span v-for="[key] in dimensions" :key="key" class="condition" :class="workbenchCondition(row.readiness,key).tone">{{ workbenchCondition(row.readiness,key).text }}</span></button>
      <div v-if="!preview.length" class="emptyplot">{{ directoryKnown?'这个筛选下没有已确认匹配的来源；未知不会归为已满足。':'数据源目录未确认，不能显示为空。' }}</div>
      <div class="row-foot"><CircleHelp :size="13" /><span>基础条件、知识索引分别核对。授权“已配置”不代表你已获查询权。<br>负责范围预览最多3个来源，完整信息在数据源专页查看。</span></div>
     </section>
     <aside v-if="selected && action" class="detail-pane" data-source-detail>
      <div class="detail-kicker"><span>当前来源</span><span>#{{ selected.source.id }}</span></div><h3>{{ selected.source.name }}</h3>
      <div class="detail-tags"><span class="tag" :class="{attention:selected.readiness && !selected.readiness.askable}">{{ !selected.readiness?'准备情况待确认':selected.readiness.askable?'基础条件满足':selected.readiness.stageLabel }}</span></div>
      <h4>{{ selected.readiness?.blockReasons[0]?.message || (selected.readiness ? selected.readiness.knowledgeReady?'核对当前准备情况':'可继续完善知识索引':'准备回执尚未确认') }}</h4><p>{{ action.description }}</p>
      <button class="primary" data-source-primary :disabled="!!actionDisabledReason" @click="openPrimary(selected.source.id)">{{ action.label }} <ArrowUpRight :size="14" /></button>
      <p v-if="actionDisabledReason" class="cap-hint" role="status">{{ actionDisabledReason }}</p>
      <dl class="scope-facts"><div><dt>已发布元数据</dt><dd>{{ selected.readiness ? selected.readiness.publishedSnapshotId ? `${selected.readiness.snapshotVersion!=null?'v'+selected.readiness.snapshotVersion+' · ':''}#${selected.readiness.publishedSnapshotId}` : '尚未发布' : '待确认' }}</dd></div><div><dt>最新采集</dt><dd>{{ selected.readiness ? selected.readiness.latestCollectedSnapshotId ? `${selected.readiness.latestCollectedSnapshotVersion!=null?'v'+selected.readiness.latestCollectedSnapshotVersion+' · ':''}#${selected.readiness.latestCollectedSnapshotId}` : '暂无采集记录' : '待确认' }}</dd></div></dl>
      <p v-if="selected.readiness?.ragNotice" class="cap-hint">{{ selected.readiness.ragNotice }}</p>
      <div class="more"><button v-if="selected.readiness && (selected.readiness.latestCollectedSnapshotId || selected.readiness.publishedSnapshotId) && session.can('governance:field:view',selected.source.id)" class="plain" @click="navigateSource('fields',selected.source.id)">字段治理 <ArrowUpRight :size="13" /></button><button v-if="selected.readiness && session.can('knowledge:view',selected.source.id)" class="plain" @click="navigateSource('knowledge',selected.source.id)">知识文档 <ArrowUpRight :size="13" /></button></div>
     </aside><aside v-else class="detail-pane"><h3>选择一个来源</h3><p :role="focusProblem?'alert':undefined">{{ focusProblem || '在当前有权范围内选中来源，查看准备条件和下一步。' }}</p></aside>
    </div>
    <footer v-if="canViewIssues" class="board-bottom"><div class="left"><b>{{ issueCountText }}</b><span>条未解决治理问题</span><small>· 问题查看能力对应的负责范围</small></div><button class="plain" @click="openWorkspace('/admin/governance/issues','governance:issue:view')">到问题中心查看 <ArrowUpRight :size="13" /></button></footer>
   </section>
   <section v-else-if="canViewSources" class="focus-board empty-work"><h2>当前已读取范围没有数据源</h2><p>这是成功读取后的空范围。到数据源页面查看已有接入入口，是否允许新建仍由专页核验。</p><button class="primary" @click="openWorkspace('/admin/data-sources','datasource:view')">查看数据源 <ArrowUpRight :size="14" /></button></section>
   <section v-else-if="canViewApprovals" class="focus-board"><header class="board-top"><div><h2>负责范围的访问申请</h2><p>先确认申请对象与目的，处理在申请与审批专页完成。</p></div><div class="brief-count"><strong>{{ pendingApprovalCount ?? '—' }}</strong>条待审批申请</div></header><div class="role-board"><section class="task-panel"><ErrorState v-if="approvalError" :message="approvalError" @retry="reload" /><ul v-else-if="approvals.length" class="bounded-queue"><li v-for="record in approvals" :key="record.id" data-approval-preview><div class="queue-line"><strong>{{ record.datasourceName || '来源 #'+record.datasourceId }} · {{ record.tableName }}</strong><span class="tag">待审批</span></div><small>申请 #{{ record.id }} · 快照 #{{ record.metadataSnapshotId }} · 字段 {{ record.requestedColumns.join('、') }}</small><p>{{ record.purpose }}</p></li></ul><div v-else class="emptyplot">{{ pendingApprovalCount===0?'当前没有待审批申请':'审批预览未完整取得，不能当作队列为空。' }}</div><p v-if="!approvalError" class="queue-limit">仅预览{{ approvals.length }}条，队列共{{ pendingApprovalCount ?? '—' }}条。属于可查看队列，不代表已分派给你。</p></section><aside class="detail-pane"><div class="detail-kicker">当前工作</div><h3>核对范围与访问依据</h3><p>在专页核对字段、使用情景、期限和授权限制。查看队列不代表具有批准能力。</p><button class="primary" @click="openWorkspace('/admin/access/iam-approvals','security:approval:view',true)">打开申请与审批 <ArrowUpRight :size="14" /></button><dl class="scope-facts"><div><dt>工作范围</dt><dd>审批负责范围</dd></div><div><dt>元数据选择</dt><dd>不需预先选择快照</dd></div></dl></aside></div></section>
   <section v-else class="focus-board"><div class="role-board"><section class="task-panel"><div class="scope-heading"><div><strong>{{ preferred?.name || '选择当前有权工作范围' }}</strong><small>{{ preferred ? '快照 #'+preferred.snapshotId+' · 已核验范围偏好' : '进入工作区后选择来源与快照，不传旧定位。' }}</small></div></div><template v-if="canViewFields"><h2>围绕业务含义核对字段</h2><p class="lead">先阅读当前有效解释与依据，再决定是否需要新修订。候选、人工审核和正式知识保持各自身份。</p><button class="primary" @click="fieldEntry">进入字段治理 <ArrowUpRight :size="14" /></button></template><template v-else><h2>从当前可查看的工作区开始</h2><p class="lead">当前没有数据源查看范围，不展示来源准备矩阵或将它当作0。其他工作区仍按各自能力开放。</p></template></section><aside class="detail-pane"><h3>按当前能力继续</h3><p>当前没有数据源查看范围；可用入口不会增加连接配置、审核或查询权限。</p><div class="scope-facts"><button v-if="hasScope('knowledge:view')" class="plain" @click="openWorkspace('/admin/semantics/knowledge','knowledge:view')">查阅知识文档 <ArrowUpRight :size="13" /></button><button v-if="hasScope('metadata:release:view')" class="plain" @click="openWorkspace('/admin/releases','metadata:release:view')">核对元数据版本 <ArrowUpRight :size="13" /></button><button v-if="hasScope('governance:view')" class="plain" @click="openWorkspace('/admin/governance','governance:view')">查看治理总览 <ArrowUpRight :size="13" /></button></div></aside></div><footer v-if="canViewIssues" class="board-bottom"><div class="left"><b>{{ issueCountText }}</b><span>条未解决治理问题 · 负责范围</span></div><button class="plain" @click="openWorkspace('/admin/governance/issues','governance:issue:view')">问题中心 <ArrowUpRight :size="13" /></button></footer></section>
   <div v-if="!canViewSources && canViewApprovals && canViewFields" class="approval-strip"><span>也可核对当前有权范围的字段业务含义。</span><button class="plain" @click="fieldEntry">进入字段治理 <ArrowUpRight :size="13" /></button></div>
   <ErrorState v-if="statsError && !workbenchForbidden" :message="statsError" @retry="reload" />
   <div v-if="canViewSources && canViewApprovals" class="approval-strip"><span>{{ approvalError ? '审批摘要未确认：'+approvalError : `${pendingApprovalCount ?? '—'} 条负责范围申请待审批` }}</span><button class="plain" @click="openWorkspace('/admin/access/iam-approvals','security:approval:view',true)">查看申请与审批 <ArrowUpRight :size="13" /></button></div>
  </template>
  <el-dialog v-model="helpOpen" title="准备条件与个人权限" width="620px" append-to-body destroy-on-close><p>后台准备条件与个人业务数据权分开。授权“已配置”只说明来源存在有效查询授权，不代表你本人有权问数；正式提问仍独立重验。</p><p>知识索引独立展示，不新增接口未定义的硬门禁。治理只核对已发布快照的高危阻断，没有发布版不表示质量通过。</p><p>工作台只预览准备情况与可进入的下一步，不执行测试连接、发布、审核、授权或构建。问题摘要是当前角色可查看范围，不是个人分派。</p></el-dialog>
 </div>
</template>

<style scoped>

.workbench-page{--ink:var(--do-ink);--muted:var(--do-muted);--line:var(--do-line);--primary:var(--do-primary);--strong:var(--do-primary-strong);max-width:1420px;margin:0 auto}
.workbench-page :deep(.task-page-header h1){font-size:28px;font-weight:650;line-height:1.4}.workbench-page :deep(.task-page-header p){font-size:12px;margin-top:4px}.workbench-page :deep(.task-page-header){margin-bottom:20px}
button{font:inherit;cursor:pointer}button:disabled{cursor:not-allowed}button:focus-visible{outline:3px solid var(--do-primary);outline-offset:3px}
.primary,.secondary{display:inline-flex;justify-content:center;align-items:center;gap:8px;border-radius:6px;min-height:37px;padding:8px 12px;font-size:12px;font-weight:550}
.primary{background:var(--do-primary-strong);color:var(--do-surface);border:1px solid var(--do-primary-strong)}.secondary{background:var(--do-surface);border:1px solid var(--do-line);color:var(--do-ink)}
.plain{display:inline-flex;align-items:center;gap:6px;border:0;background:transparent;color:var(--do-muted);padding:5px 0;font-size:12px;text-align:left}.plain:hover{color:var(--do-primary-strong)}
.tag{display:inline-flex;padding:2px 7px;border:1px solid var(--do-line);border-radius:4px;font-size:11px;background:var(--do-soft);color:var(--do-muted)}
.notice{display:flex;align-items:center;justify-content:space-between;gap:16px;border-left:3px solid var(--do-warning);padding:12px 16px;background:var(--do-warning-soft);color:var(--do-ink);margin-bottom:16px;font-size:12px}
.emptyplot{padding:25px 0;color:var(--do-muted);font-size:12px;line-height:1.9}.noaccess{padding:28px;border:1px solid var(--do-line);border-radius:10px;background:var(--do-surface)}.noaccess h2{font-size:20px}.noaccess p{font-size:13px;color:var(--do-muted)}
.tag.attention{color:var(--do-warning);background:var(--do-warning-soft);border-color:var(--do-line)}
.approval-strip{display:flex;justify-content:space-between;align-items:center;gap:20px;border-top:1px solid var(--do-line);padding:16px 4px;margin-top:16px;color:var(--do-muted);font-size:12px}
/* V3 workbench: concrete sources before generic destinations. PC only. */
.wb-head{margin-bottom:18px}.wb-head h1{font-size:27px}.wb-meta{display:flex;align-items:center;gap:12px}.mode-label{display:flex;align-items:center;gap:7px;color:var(--muted);font-size:11px}.mode-label .ico{width:15px;height:15px}.snapshot-line{color:var(--muted);font-size:11px}.focus-board{border:1px solid var(--line);border-radius:10px;overflow:hidden;background:white}.board-top{padding:17px 20px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:20px}.board-top h2{font-size:16px;font-weight:600;margin:0}.board-top p{margin:3px 0 0;color:var(--muted);font-size:11px}.board-top .brief-count{font-size:11px;display:flex;align-items:baseline;gap:6px;color:var(--muted)}.brief-count strong{font-size:24px;color:var(--ink);font-weight:600;font-variant-numeric:tabular-nums}.work-grid{display:grid;grid-template-columns:minmax(0,1fr) 300px}.source-area{padding:17px 20px 0;min-width:0}.source-tools{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:13px}.source-tabs{display:flex;gap:17px;align-items:center}.source-tabs button{border:0;background:transparent;padding:3px 0 7px;color:var(--muted);font-size:11px;border-bottom:2px solid transparent}.source-tabs button[aria-pressed=true]{border-color:var(--primary);color:var(--ink);font-weight:600}.source-tabs b{font-weight:400;color:var(--muted);margin-left:4px}.source-tools .plain{font-size:10px}.source-columns,.source-choice{display:grid;grid-template-columns:minmax(122px,1.75fr) repeat(5,minmax(52px,.8fr));gap:7px;align-items:center}.source-columns{padding:9px 12px;font-size:10px;color:var(--muted);background:var(--do-soft);border-radius:5px;margin-bottom:5px}.source-columns span:not(:first-child){text-align:center}.source-choice{width:100%;min-height:76px;text-align:left;border:1px solid transparent;border-bottom-color:var(--line);background:white;padding:12px;color:var(--ink);border-radius:5px;position:relative}.source-choice:hover{background:var(--do-soft)}.source-choice[aria-pressed=true]{background:var(--do-soft);border-color:var(--do-line-strong);box-shadow:inset 2px 0 var(--strong)}.source-name{min-width:0;display:block}.source-name strong{display:block;font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.source-name small{display:flex;gap:5px;align-items:center;font-size:10px;margin-top:4px;color:var(--muted)}.source-name .ico{width:11px;height:11px}.condition{text-align:center;font-size:10px;color:var(--do-muted)}.condition.good{color:var(--do-success)}.condition.attention{color:var(--do-warning)}.condition.unknown{color:var(--muted)}.row-foot{display:flex;align-items:start;gap:6px;font-size:10px;color:var(--muted);padding:13px 0 15px;line-height:1.85}.row-foot .ico{width:13px;height:13px;margin-top:2px;flex-shrink:0}.detail-pane{border-left:1px solid var(--line);background:var(--do-soft);padding:21px 22px;min-width:0}.detail-kicker{font-size:10px;display:flex;justify-content:space-between;color:var(--muted);margin-bottom:12px}.detail-pane h3{font-size:18px;font-weight:600;line-height:1.45;margin:0 0 7px}.detail-tags{display:flex;gap:7px;flex-wrap:wrap;color:var(--muted);font-size:10px}.detail-pane h4{font-size:16px;line-height:1.55;font-weight:550;margin:19px 0 6px}.detail-pane p{font-size:11px;line-height:1.85;color:var(--muted);margin:0}.detail-pane .primary{width:100%;margin-top:18px;min-height:37px;font-size:11px}.primary:disabled{color:#757a71;background:#e9ece5;border-color:#d9dfd2}.cap-hint{margin:9px 0 0!important;font-size:10px!important;color:var(--amber)!important}.scope-facts{padding-top:16px;margin-top:20px;border-top:1px solid var(--line);display:grid;gap:9px}.scope-facts div{display:flex;justify-content:space-between;align-items:baseline;gap:15px;font-size:10px}.scope-facts dt{color:var(--muted)}.scope-facts dd{margin:0;text-align:right;color:var(--ink)}.detail-pane .more{display:flex;justify-content:space-between;gap:15px;border-top:1px solid var(--line);padding-top:13px;margin-top:17px}.detail-pane .more button{font-size:10px}.board-bottom{padding:14px 20px;border-top:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:22px;background:white}.board-bottom .left{display:flex;align-items:baseline;gap:9px;font-size:11px;color:var(--muted)}.board-bottom b{font-size:20px;color:var(--ink);font-weight:600;font-variant-numeric:tabular-nums}.board-bottom .left small{font-size:10px}.board-bottom .plain{font-size:10px}.below-note{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-top:15px;font-size:10px;color:var(--muted)}.below-note p{margin:0}.below-note button{font-size:10px}.role-board{display:grid;grid-template-columns:minmax(0,1fr) 300px}.task-panel{padding:24px 25px}.task-panel h2{font-size:23px;font-weight:600;margin:8px 0 12px}.task-panel .lead{font-size:12px;color:var(--muted);line-height:1.9;max-width:47em}.scope-heading{display:flex;gap:12px;align-items:center;padding-bottom:17px;border-bottom:1px solid var(--line);margin-bottom:20px}.scope-heading>.ico{width:22px;height:22px;color:var(--sage)}.scope-heading strong{display:block;font-size:14px}.scope-heading small{font-size:11px;color:var(--muted)}.task-checks{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:25px;padding-top:18px;border-top:1px solid var(--line)}.task-checks strong{font-size:12px;display:block;font-weight:550}.task-checks small{display:block;font-size:10px;color:var(--muted);margin-top:6px;line-height:1.7}.bounded-queue{margin:0;padding:0;list-style:none}.bounded-queue li{padding:16px 0;border-bottom:1px solid var(--line)}.queue-line{display:flex;justify-content:space-between;gap:15px;align-items:start}.queue-line strong{font-size:13px;font-weight:550}.bounded-queue p{font-size:11px;line-height:1.7;color:var(--muted);margin:6px 0 0}.bounded-queue small{font-size:10px;color:var(--muted)}.queue-limit{font-size:10px;color:var(--muted);padding-top:13px}.empty-work{padding:28px 24px;background:var(--do-surface)}.empty-work h2{font-size:21px;font-weight:550;margin:7px 0}.empty-work p{font-size:12px;color:var(--muted);line-height:1.9;max-width:52em}.empty-work .primary{margin-top:12px}.status-help{margin-left:auto!important;white-space:nowrap}.status-help .ico{width:12px;height:12px}.status-legend{gap:14px}.help-list{padding:0;list-style:none}.help-list li{padding:9px 0;border-bottom:1px solid var(--line);font-size:12px;line-height:1.85}.help-list b{display:block;color:var(--ink)}

/* Keep the working objects and main action within a typical desktop height. */
.board-top{padding:13px 20px}.source-choice{min-height:66px;padding:10px 12px}.detail-pane{padding:18px 22px}.detail-pane h4{margin-top:14px}.detail-pane .primary{margin-top:14px}.scope-facts{padding-top:12px;margin-top:16px;gap:7px}.detail-pane .more{padding-top:10px;margin-top:12px}.row-foot{padding-bottom:12px}

.workbench-preview .page{padding-bottom:20px}.detail-pane h3,.detail-pane p,.scope-facts dd,.queue-line strong,.bounded-queue p,.bounded-queue small{overflow-wrap:anywhere;min-width:0}

</style>
