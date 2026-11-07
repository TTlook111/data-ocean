<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
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
// 筛选只改变已授权目录的展示；未知准备状态保留独立入口，不归入已就绪。
type SourceFilter = 'all' | 'attention' | 'ready' | 'unknown'
const selectedId=ref<number>(),filter=ref<SourceFilter>('all'),helpOpen=ref(false)
// 保留来源表的局部引用，键盘移动不会在全局页面寻找或聚焦其他工作区的对象。
const sourceTable=ref<HTMLElement>()
useAdminScrollLock(()=>helpOpen.value)
let statsRequest=0,readinessRequest=0,approvalRequest=0,loadRequest=0
// 来源选择代次独立于读取代次，后续选择或筛选会使旧键盘导航失去聚焦资格。
let sourceSelectionRevision=0
const current=(ticket:number)=>session.current(ticket)&&canViewWorkbench.value&&!workbenchForbidden.value
const pendingLoading=computed(()=>loading.value||statsLoading.value||readinessLoading.value||approvalLoading.value)
const rows=computed(()=>sources.value.map(source=>({source,readiness:readiness.value.find(r=>r.datasourceId===source.id)})))
const unknownCount=computed(()=>sources.value.length-readiness.value.length)
const countsKnown=computed(()=>directoryKnown.value&&!readinessLoading.value&&!readinessError.value&&!unknownCount.value)
const attentionCount=computed(()=>readiness.value.filter(r=>!r.askable).length)
// 分布只使用已核验回执和有权目录；分母包含未读来源，不把局部样本当作全量就绪。
const readyCount=computed(()=>readiness.value.filter(r=>r.askable).length)
const readinessDistribution=computed(()=>[
 {key:'ready' as const,label:'基础条件满足',count:readyCount.value},
 {key:'attention' as const,label:'需关注',count:attentionCount.value},
 {key:'unknown' as const,label:'待确认',count:unknownCount.value},
])
const distributionLabel=computed(()=>readinessDistribution.value.map(item=>`${item.label} ${item.count} 个`).join('，'))
// 来源目录不再截断为三个预览；精确 URL 和当前筛选始终作用于同一组可见对象。
const visibleSources=computed(()=>rows.value.filter(row=>filter.value==='all'
 ||(filter.value==='unknown'?!row.readiness:row.readiness&&(filter.value==='attention'?!row.readiness.askable:row.readiness.askable))))
// URL 定位优先于本地默认项；详情、动作和列表高亮均以核验后的 selected 为准。
const selected=computed(()=>focusProblem.value?undefined:visibleSources.value.find(row=>row.source.id===(requestedFocus.value??selectedId.value)))
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
// 返回路由导航本身的完成信号，不能把 Vue 一轮渲染当作 URL 已更新。
function persistFocus(id?:number){const query={...(router.currentRoute?.value?.query||{})};delete query.focusSourceId;if(id)query.focusSourceId=String(id);return router.replace?.({path:'/admin/workbench',query})}
// 只有可见且有权的精确来源能成为当前对象；导航回执保留发起身份和选择代次。
function selectSource(id:number){
 if(!allowed()||!visibleSources.value.some(row=>row.source.id===id))return
 const revision=++sourceSelectionRevision,ticket=session.capture()
 selectedId.value=id
 return {revision,ticket,navigation:persistFocus(id)}
}
// 切筛选清理 URL 定位，并取消之前尚未完成的键盘聚焦请求。
function changeFilter(value:SourceFilter){if(allowed()){sourceSelectionRevision++;filter.value=value;void persistFocus()}}
// 完整目录用方向键移动精确来源，Tab 只停在当前行，避免大目录阻断到下一步操作。
async function handleSourceKey(event:KeyboardEvent,id:number){
 const index=visibleSources.value.findIndex(row=>row.source.id===id)
 if(index<0||!allowed())return
 const last=visibleSources.value.length-1
 const destinations:Record<string,number>={ArrowUp:Math.max(0,index-1),ArrowDown:Math.min(last,index+1),Home:0,End:last}
 const destination=destinations[event.key]
 if(destination===undefined)return
 event.preventDefault()
 const target=visibleSources.value[destination]
 if(!target)return
 const origin=event.currentTarget,selection=selectSource(target.source.id)
 if(!selection)return
 try{
  // 路由守卫与 replace 都可能异步，完成导航后再等待对应选中态渲染。
  await selection.navigation
  await nextTick()
 }catch{return}
 // 身份、选择、可见目标和原焦点都仍属于本次动作时才移动焦点，迟到导航不能夺焦。
 if(selection.revision!==sourceSelectionRevision||!session.current(selection.ticket)||!allowed()
  ||selected.value?.source.id!==target.source.id||document.activeElement!==origin)return
 sourceTable.value?.querySelector<HTMLButtonElement>(`[data-source-choice="${target.source.id}"]`)?.focus()
}
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
// 新筛选不包含原对象时只选择该筛选中的首项；显式无权 URL 仍由 selected 拒绝。
watch(()=>visibleSources.value.map(row=>row.source.id),ids=>{sourceSelectionRevision++;if(!ids.includes(selectedId.value!))selectedId.value=ids[0]},{flush:'sync'})
watch(session.key,()=>{statsRequest++;readinessRequest++;approvalRequest++;statsLoading.value=false;readinessLoading.value=false;approvalLoading.value=false;selectedId.value=undefined;filter.value='all';helpOpen.value=false;void load()},{immediate:true,flush:'sync'})
</script>

<template>
  <main class="admin-page workbench-page">
    <!-- 页头只保留标题、帮助与刷新，把持续性业务状态放回对应工作区域。 -->
    <TaskPageHeader title="工作台">
      <template #actions>
        <button type="button" class="plain help-button" aria-label="查看准备条件与个人权限说明" @click="helpOpen=true"><CircleHelp :size="17" aria-hidden="true" />准备条件</button>
        <el-button :icon="RefreshCw" :loading="pendingLoading" @click="reload">刷新</el-button>
      </template>
    </TaskPageHeader>

    <!-- 身份、工作台能力和服务端 403 先于任何业务摘要；晚回数据不能越过这层门禁。 -->
    <LoadingState v-if="!iam.loaded && !iam.errorMessage" text="正在读取当前能力…" />
    <ErrorState v-else-if="!session.ready.value" :message="iam.errorMessage || '正在读取当前能力，请稍候。'" @retry="reload" />
    <section v-else-if="!canViewWorkbench || workbenchForbidden" class="noaccess" role="alert">
      <h2>{{ workbenchForbidden ? statsError : '没有“查看工作台”功能' }}</h2>
      <p>当前没有工作台查看能力；未展示摘要或准备回执，无权不表示数量为0。</p>
      <el-button v-if="workbenchForbidden" @click="reload">重新核验</el-button>
    </section>
    <LoadingState v-else-if="loading" text="正在读取当前身份与负责范围…" />
    <template v-else>
      <!-- 每项摘要使用独立权限和真实口径；未知值用占位显示，不借来源权推导审批或治理权。 -->
      <section v-if="canViewSources || canViewIssues || canViewApprovals" class="workbench-stats" aria-label="当前有权工作摘要">
        <button v-if="canViewSources" type="button" class="metric" data-metric="sources" @click="openWorkspace('/admin/data-sources','datasource:view')">
          <span class="metric-label">有权数据源<ArrowUpRight :size="15" aria-hidden="true" /></span>
          <strong>{{ directoryKnown ? sources.length : '—' }}</strong><span class="metric-scope">当前可查看目录</span>
        </button>
        <button v-if="canViewIssues" type="button" class="metric" data-metric="issues" @click="openWorkspace('/admin/governance/issues','governance:issue:view')">
          <span class="metric-label">未解决治理问题<ArrowUpRight :size="15" aria-hidden="true" /></span>
          <strong>{{ issueCountText }}</strong><span class="metric-scope">问题查看能力对应的负责范围</span>
        </button>
        <button v-if="canViewApprovals" type="button" class="metric" data-metric="approvals" @click="openWorkspace('/admin/access/iam-approvals','security:approval:view',true)">
          <span class="metric-label">待审批申请<ArrowUpRight :size="15" aria-hidden="true" /></span>
          <strong>{{ pendingApprovalCount ?? '—' }}</strong><span class="metric-scope">负责范围申请待审批</span>
        </button>
      </section>
      <ErrorState v-if="statsError && !workbenchForbidden" :message="statsError" @retry="reload" />
      <ErrorState v-if="readinessError && canViewSources" :message="'准备情况暂不可用：'+readinessError" @retry="reload" />
      <ErrorState v-if="approvalError && canViewApprovals" :message="approvalError" @retry="reload" />

      <!-- 三段分布完整包含未知来源；准备条件与个人问数许可保持分离，不虚构趋势。 -->
      <section v-if="canViewSources && directoryKnown && sources.length" class="readiness-overview" aria-labelledby="readiness-title">
        <div class="section-heading"><h2 id="readiness-title">数据准备分布</h2><span>{{ readiness.length }} / {{ sources.length }} 个来源已核验</span></div>
        <div class="readiness-bar" role="img" :aria-label="distributionLabel">
          <span v-for="item in readinessDistribution" :key="item.key" :class="item.key" :style="{ flexGrow: item.count }" :hidden="!item.count" />
        </div>
        <div class="readiness-legend">
          <button v-for="item in readinessDistribution" :key="item.key" type="button" :class="item.key" :data-readiness-segment="item.key" :aria-pressed="filter===item.key" @click="changeFilter(item.key)"><i aria-hidden="true" /><span>{{ item.label }}</span><strong>{{ item.count }}</strong></button>
          <span class="readiness-note">授权已配置不代表你已获查询权</span>
        </div>
      </section>
      <div v-if="readinessPartialError && canViewSources" class="notice" role="status"><span>{{ readinessPartialError }}</span><button type="button" class="plain" @click="reload">重新读取</button></div>

      <!-- 来源目录与下一步共用同一精确对象，完整目录可滚动查看，不再固定截断三个来源。 -->
      <section v-if="canViewSources && (sources.length || !directoryKnown)" class="source-panel">
        <div class="section-heading source-heading"><h2>来源与下一步</h2><button type="button" class="plain" @click="openWorkspace('/admin/data-sources','datasource:view')">管理数据源<ArrowUpRight :size="14" /></button></div>
        <div class="source-tools">
          <div class="source-tabs" role="group" aria-label="筛选准备情况">
            <button v-for="item in (['all','attention','ready','unknown'] as const)" :key="item" type="button" :data-source-filter="item" :aria-pressed="filter===item" @click="changeFilter(item)">{{ {all:'全部',attention:'需关注',ready:'基础条件满足',unknown:'待确认'}[item] }}<b>{{ item==='all' ? (directoryKnown?sources.length:'—') : item==='unknown' ? (directoryKnown?unknownCount:'—') : countsKnown ? item==='attention' ? attentionCount : readyCount : '—' }}</b></button>
          </div>
        </div>
        <div ref="sourceTable" class="source-table" aria-label="负责范围内的数据源准备情况">
          <div class="source-columns" aria-hidden="true"><span>数据源</span><span v-for="[,label] in dimensions" :key="label">{{ label }}</span></div>
          <!-- 单行保留 Tab 入口，方向键和首尾键按当前筛选范围移动，不额外读取其他来源。 -->
          <button v-for="row in visibleSources" :key="row.source.id" type="button" class="source-choice" :data-source-choice="row.source.id" :aria-label="sourceLabel(row)" :aria-pressed="selected?.source.id===row.source.id" :tabindex="selected?.source.id===row.source.id || (!selected && row.source.id===visibleSources[0]?.source.id) ? 0 : -1" aria-keyshortcuts="ArrowUp ArrowDown Home End" @click="selectSource(row.source.id)" @keydown="handleSourceKey($event,row.source.id)">
            <span class="source-name"><strong :title="row.source.name">{{ row.source.name }}</strong><small>{{ sourceSubtitle(row.readiness) }}</small></span>
            <span v-for="[key] in dimensions" :key="key" class="condition" :class="workbenchCondition(row.readiness,key).tone">{{ workbenchCondition(row.readiness,key).text }}</span>
          </button>
          <div v-if="!visibleSources.length" class="emptyplot">{{ directoryKnown?'这个筛选下没有已确认匹配的来源；未知不会归为已满足。':'数据源目录未确认，不能显示为空。' }}</div>
        </div>
        <!-- 当前来源的状态、版本与操作集中在列表下方，避免固定右栏挤压五项准备事实。 -->
        <section v-if="selected && action" class="next-step" data-source-detail>
          <div class="next-step-main">
            <div class="detail-kicker"><span>当前来源 · 下一步</span><span>#{{ selected.source.id }}</span></div>
            <h3>{{ selected.source.name }}</h3>
            <span class="tag" :class="{attention:selected.readiness && !selected.readiness.askable}">{{ !selected.readiness?'准备情况待确认':selected.readiness.askable?'基础条件满足':selected.readiness.stageLabel }}</span>
            <h4>{{ selected.readiness?.blockReasons[0]?.message || (selected.readiness ? selected.readiness.knowledgeReady?'核对当前准备情况':'可继续完善知识索引':'准备回执尚未确认') }}</h4>
            <p>{{ action.description }}</p>
            <p v-if="selected.readiness?.ragNotice" class="cap-hint">{{ selected.readiness.ragNotice }}</p>
          </div>
          <div class="next-step-actions">
            <dl class="scope-facts">
              <div><dt>已发布元数据</dt><dd>{{ selected.readiness ? selected.readiness.publishedSnapshotId ? `${selected.readiness.snapshotVersion!=null?'v'+selected.readiness.snapshotVersion+' · ':''}#${selected.readiness.publishedSnapshotId}` : '尚未发布' : '待确认' }}</dd></div>
              <div><dt>最新采集</dt><dd>{{ selected.readiness ? selected.readiness.latestCollectedSnapshotId ? `${selected.readiness.latestCollectedSnapshotVersion!=null?'v'+selected.readiness.latestCollectedSnapshotVersion+' · ':''}#${selected.readiness.latestCollectedSnapshotId}` : '暂无采集记录' : '待确认' }}</dd></div>
            </dl>
            <button type="button" class="primary" data-source-primary :disabled="!!actionDisabledReason" @click="openPrimary(selected.source.id)">{{ action.label }}<ArrowUpRight :size="15" /></button>
            <p v-if="actionDisabledReason" class="cap-hint" role="status">{{ actionDisabledReason }}</p>
            <div class="more">
              <button v-if="selected.readiness && (selected.readiness.latestCollectedSnapshotId || selected.readiness.publishedSnapshotId) && session.can('governance:field:view',selected.source.id)" type="button" class="plain" @click="navigateSource('fields',selected.source.id)">字段治理<ArrowUpRight :size="13" /></button>
              <button v-if="selected.readiness && session.can('knowledge:view',selected.source.id)" type="button" class="plain" @click="navigateSource('knowledge',selected.source.id)">知识文档<ArrowUpRight :size="13" /></button>
            </div>
          </div>
        </section>
        <div v-else class="selection-empty"><h3>选择一个来源</h3><p :role="focusProblem?'alert':undefined">{{ focusProblem || '在当前有权范围内选中来源，查看准备条件和下一步。' }}</p></div>
      </section>
      <!-- 仅成功读取后的空目录显示空状态；辅助目录缺权不会阻断独立的审核和知识任务。 -->
      <section v-else-if="canViewSources" class="empty-work"><h2>当前已读取范围没有数据源</h2><p>这是成功读取后的空范围。接入及维护能力在数据源专页核验。</p><button type="button" class="primary" @click="openWorkspace('/admin/data-sources','datasource:view')">查看数据源<ArrowUpRight :size="14" /></button></section>
      <section v-else-if="canViewApprovals" class="approval-panel">
        <div class="section-heading"><h2>访问申请预览</h2><button type="button" class="plain" @click="openWorkspace('/admin/access/iam-approvals','security:approval:view',true)">打开申请与审批<ArrowUpRight :size="14" /></button></div>
        <ul v-if="!approvalError && approvals.length" class="bounded-queue"><li v-for="record in approvals" :key="record.id" data-approval-preview><div class="queue-line"><strong>{{ record.datasourceName || '来源 #'+record.datasourceId }} · {{ record.tableName }}</strong><span class="tag">待审批</span></div><small>申请 #{{ record.id }} · 快照 #{{ record.metadataSnapshotId }} · 字段 {{ record.requestedColumns.join('、') }}</small><p>{{ record.purpose }}</p></li></ul>
        <p v-else-if="!approvalError" class="emptyplot">{{ pendingApprovalCount===0?'当前没有待审批申请':'审批预览未完整取得，不能当作队列为空。' }}</p>
        <p v-if="!approvalError" class="queue-limit">仅预览 {{ approvals.length }} 条，队列共 {{ pendingApprovalCount ?? '—' }} 条。查看队列不代表具有批准能力。</p>
      </section>
      <!-- 无来源查看权的角色直接进入自己的合法任务，不补权、不伪造来源数量。 -->
      <section v-if="!canViewSources" class="role-panel">
        <div class="role-intro"><h2>{{ canViewFields ? '继续字段治理' : '当前可用工作区' }}</h2><p>当前没有数据源查看范围；以下入口按各自能力开放。</p><span v-if="preferred" class="scope-label">{{ preferred.name || '来源 #'+preferred.datasourceId }} · 快照 #{{ preferred.snapshotId }}</span></div>
        <div class="role-actions">
          <button v-if="canViewFields" type="button" class="primary" @click="fieldEntry">进入字段治理<ArrowUpRight :size="14" /></button>
          <button v-if="hasScope('knowledge:view')" type="button" class="secondary" @click="openWorkspace('/admin/semantics/knowledge','knowledge:view')">查阅知识文档<ArrowUpRight :size="14" /></button>
          <button v-if="hasScope('metadata:release:view')" type="button" class="secondary" @click="openWorkspace('/admin/releases','metadata:release:view')">核对元数据版本<ArrowUpRight :size="14" /></button>
          <button v-if="hasScope('governance:view')" type="button" class="secondary" @click="openWorkspace('/admin/governance','governance:view')">查看治理总览<ArrowUpRight :size="14" /></button>
        </div>
      </section>
    </template>
    <!-- 帮助保留必要业务区别，页面默认不重复铺开完整说明。 -->
    <el-dialog v-model="helpOpen" title="准备条件与个人权限" width="min(620px,94vw)" append-to-body destroy-on-close>
      <p>后台准备条件与个人业务数据权分开。授权“已配置”只说明来源存在有效查询授权，不代表你本人有权问数；正式提问仍独立重验。</p>
      <p>知识索引独立展示，不新增接口未定义的硬门禁。治理只核对已发布快照的高危阻断，没有发布版不表示质量通过。</p>
      <p>工作台只预览准备情况与可进入的下一步，不执行测试连接、发布、审核、授权或构建。问题摘要是当前角色可查看范围，不是个人分派。</p>
    </el-dialog>
  </main>
</template>

<style scoped>
/* 页面只消费共享令牌，以白色数据面和清晰分隔建立层级，不新增局部色板。 */
.workbench-page { display: flex; flex-direction: column; gap: 22px; width: 100%; max-width: 1440px; min-width: 0; margin: 0 auto; }
.workbench-page :deep(.task-page-header) { margin: 0; }
h2, h3, h4, p { margin: 0; }
button { font: inherit; cursor: pointer; }
button:disabled { cursor: not-allowed; }
button:focus-visible { outline: 3px solid var(--do-primary); outline-offset: 3px; }
button svg { flex-shrink: 0; }
/* 指标组与来源表有裁切边界，焦点内收，避免键盘操作时外轮廓被滚动容器遮住。 */
.metric:focus-visible, .source-choice:focus-visible { outline-offset: -3px; }

/* 操作等级与禁用状态沿用语义色，键盘焦点始终可见。 */
.primary, .secondary { display: inline-flex; justify-content: center; align-items: center; gap: 8px; min-height: 38px; padding: 8px 14px; border-radius: var(--do-radius-sm); font-size: 13px; font-weight: 600; }
.primary { border: 1px solid var(--do-primary); background: var(--do-primary); color: var(--do-surface); }
.primary:hover:not(:disabled) { background: var(--do-primary-strong); border-color: var(--do-primary-strong); }
.primary:disabled { color: var(--do-muted); background: var(--do-soft); border-color: var(--do-line); }
.secondary { color: var(--do-ink); background: var(--do-surface); border: 1px solid var(--do-line); }
.secondary:hover:not(:disabled) { border-color: var(--do-primary); color: var(--do-primary-strong); }
.plain { display: inline-flex; align-items: center; gap: 7px; border: 0; padding: 5px 0; background: transparent; color: var(--do-muted); font-size: 12px; text-align: left; }
.plain:hover { color: var(--do-primary-strong); }
.help-button { margin-right: 8px; }

/* 三种有权指标独立成面。目录作为工作起点用深墨紫，问题与审批使用暖纸面和浅紫面。 */
.workbench-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 14px; }
.metric {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  display: grid;
  align-content: start;
  gap: 7px;
  padding: 20px 24px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  color: var(--do-ink);
  text-align: left;
}
.metric[data-metric="sources"] {
  border-color: var(--do-feature-bg);
  background: var(--do-feature-bg);
  color: var(--do-feature-text);
}
/* 轮廓纹样留在数字后方的空白区，宽度变化或按钮聚焦都不遮挡指标。 */
.metric[data-metric="sources"]::before {
  position: absolute;
  z-index: -1;
  inset: 0;
  background: repeating-radial-gradient(ellipse at 112% 132%, transparent 0 19px, color-mix(in srgb, var(--do-brand-lilac) 16%, transparent) 20px 21px, transparent 22px 31px);
  mask-image: linear-gradient(90deg, transparent 25%, black 100%);
  content: '';
}
.metric[data-metric="sources"] :is(.metric-label, .metric-scope) { color: var(--do-feature-muted); }
.metric[data-metric="sources"]:focus-visible { outline-color: var(--do-brand-lilac); }
.metric[data-metric="issues"] { background: color-mix(in srgb, var(--do-brand-warm) 16%, var(--do-surface)); }
.metric[data-metric="approvals"] { background: color-mix(in srgb, var(--do-brand-lilac) 21%, var(--do-surface)); }
.metric:hover { box-shadow: var(--do-shadow-hover); }
.metric-label { display: flex; justify-content: space-between; align-items: center; gap: 20px; color: var(--do-ink); font-size: 14px; font-weight: 600; }
.metric-label svg { box-sizing: content-box; padding: 4px; border: 1px solid color-mix(in srgb, currentColor 26%, transparent); border-radius: 50%; }
.metric strong { font-size: clamp(36px, 3.2vw, 48px); line-height: 1; letter-spacing: -.04em; font-weight: 600; font-variant-numeric: tabular-nums; }
.metric-scope { color: var(--do-muted); font-size: 12px; line-height: 1.5; }

/* 分布同时显示文字与数值，未知段与失败原因不被绿色就绪段覆盖。 */
.readiness-overview, .source-panel, .approval-panel, .role-panel, .empty-work, .noaccess { min-width: 0; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.readiness-overview { padding: 20px 24px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-width: 0; }
.section-heading h2 { color: var(--do-ink); font-size: 16px; font-weight: 650; }
.section-heading > span { color: var(--do-muted); font-size: 12px; }
.readiness-bar { display: flex; gap: 3px; height: 9px; margin: 20px 0 14px; border-radius: 6px; overflow: hidden; background: var(--do-soft); }
.readiness-bar span { flex-basis: 0; min-width: 0; border-radius: 2px; }
.readiness-bar .ready, .readiness-legend .ready i { background: var(--do-success); }
.readiness-bar .attention, .readiness-legend .attention i { background: var(--do-warning); }
.readiness-bar .unknown, .readiness-legend .unknown i { background: var(--do-line-strong); }
.readiness-legend { display: flex; align-items: center; flex-wrap: wrap; gap: 9px 24px; }
.readiness-legend button { display: inline-flex; align-items: center; gap: 8px; padding: 4px 0; color: var(--do-muted); background: transparent; border: 0; font-size: 12px; }
.readiness-legend button:hover, .readiness-legend button[aria-pressed=true] { color: var(--do-ink); }
.readiness-legend i { width: 7px; height: 7px; border-radius: 2px; }
.readiness-legend strong { color: var(--do-ink); font-variant-numeric: tabular-nums; }
.readiness-note { margin-left: auto; color: var(--do-muted); font-size: 11px; }
.notice { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 16px; border-left: 3px solid var(--do-warning); color: var(--do-ink); background: var(--do-warning-soft); font-size: 12px; line-height: 1.6; }

/* 表格区域负责自身滚动；完整目录保持可达，选中来源与底部下一步共用精确标识。 */
.source-panel { overflow: hidden; }
.source-heading { padding: 20px 24px 12px; }
.source-tools { padding: 0 24px 14px; }
.source-tabs { display: flex; flex-wrap: wrap; gap: 6px; }
.source-tabs button { display: inline-flex; align-items: center; gap: 7px; padding: 6px 10px; border: 1px solid transparent; border-radius: 6px; color: var(--do-muted); background: transparent; font-size: 12px; }
.source-tabs button:hover { background: var(--do-soft); }
.source-tabs button[aria-pressed=true] { color: var(--do-primary-strong); background: var(--do-primary-soft); border-color: color-mix(in srgb, var(--do-primary) 20%, transparent); }
.source-tabs b { font-weight: 600; font-variant-numeric: tabular-nums; }
.source-table { max-height: 390px; overflow: auto; scrollbar-gutter: stable; }
.source-columns, .source-choice { display: grid; grid-template-columns: minmax(210px, 1.8fr) repeat(5, minmax(85px, 1fr)); gap: 10px; align-items: center; min-width: 740px; padding: 12px 24px; }
.source-columns { position: sticky; top: 0; z-index: 1; color: var(--do-muted); background: var(--do-soft); border-top: 1px solid var(--do-line); border-bottom: 1px solid var(--do-line); font-size: 11px; }
.source-columns span:not(:first-child) { text-align: center; }
.source-choice { width: 100%; min-height: 70px; border: 0; border-bottom: 1px solid var(--do-line); text-align: left; background: var(--do-surface); color: var(--do-ink); }
.source-choice:hover { background: var(--do-soft); }
.source-choice[aria-pressed=true] { background: var(--do-primary-soft); box-shadow: inset 3px 0 var(--do-primary); }
.source-name { min-width: 0; }
.source-name strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; }
.source-name small { display: block; margin-top: 5px; color: var(--do-muted); font-size: 11px; line-height: 1.5; }
.condition { display: flex; justify-content: center; align-items: center; gap: 6px; color: var(--do-muted); font-size: 11px; }
.condition::before { content: ''; width: 5px; height: 5px; flex-shrink: 0; border-radius: 50%; background: var(--do-line-strong); }
.condition.good { color: var(--do-success); }
.condition.good::before { background: var(--do-success); }
.condition.attention { color: var(--do-warning); }
.condition.attention::before { background: var(--do-warning); }

/* 下一步采用可伸缩双区，不再把事实挤入固定右栏；长名称和权限原因可以完整换行。 */
.next-step { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(260px, 1fr); gap: 32px; padding: 22px 24px; background: var(--do-soft); border-top: 1px solid var(--do-line); }
.next-step-main, .next-step-actions { min-width: 0; }
.detail-kicker { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; color: var(--do-muted); font-size: 11px; }
.detail-kicker span:last-child { font-variant-numeric: tabular-nums; }
.next-step h3 { display: inline; margin-right: 10px; color: var(--do-ink); font-size: 19px; font-weight: 650; overflow-wrap: anywhere; }
.tag { display: inline-flex; align-items: center; padding: 3px 7px; border: 1px solid var(--do-line); border-radius: 5px; color: var(--do-muted); background: var(--do-surface); font-size: 10px; vertical-align: middle; }
.tag.attention { color: var(--do-warning); border-color: color-mix(in srgb, var(--do-warning) 20%, var(--do-line)); }
.next-step h4 { margin: 15px 0 6px; color: var(--do-ink); font-size: 13px; font-weight: 600; line-height: 1.7; }
.next-step p { color: var(--do-muted); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.scope-facts { display: grid; gap: 9px; margin: 0 0 16px; }
.scope-facts div { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; font-size: 12px; }
.scope-facts dt { color: var(--do-muted); }
.scope-facts dd { margin: 0; color: var(--do-ink); font-weight: 550; text-align: right; overflow-wrap: anywhere; }
.next-step-actions > .primary { width: 100%; }
.cap-hint { margin-top: 9px !important; color: var(--do-warning) !important; }
.more { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 18px; margin-top: 9px; }
.selection-empty { padding: 22px 24px; border-top: 1px solid var(--do-line); }
.selection-empty h3 { margin-bottom: 8px; font-size: 15px; }
.selection-empty p { color: var(--do-muted); font-size: 12px; line-height: 1.7; }

/* 无来源角色与审批预览复用简洁任务面；缺权、真实空与读取失败分别呈现。 */
.approval-panel, .role-panel, .empty-work, .noaccess { padding: 22px 24px; }
.bounded-queue { list-style: none; margin: 16px 0 0; padding: 0; }
.bounded-queue li { padding: 14px 0; border-top: 1px solid var(--do-line); }
.queue-line { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 6px; }
.queue-line strong { font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.bounded-queue small, .bounded-queue p, .queue-limit { color: var(--do-muted); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.bounded-queue p { margin-top: 5px; }
.queue-limit { margin-top: 12px; }
.role-panel { display: flex; justify-content: space-between; align-items: center; gap: 24px; }
.role-intro { min-width: 0; }
.role-intro h2, .empty-work h2, .noaccess h2 { color: var(--do-ink); font-size: 17px; font-weight: 650; }
.role-intro p, .empty-work p, .noaccess p { margin: 8px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.8; }
.role-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.scope-label { display: block; margin-top: 8px; color: var(--do-muted); font-size: 11px; }
.empty-work .primary, .noaccess :deep(.el-button) { margin-top: 16px; }
.emptyplot { padding: 22px 24px; color: var(--do-muted); font-size: 12px; line-height: 1.8; }

/* 桌面缩放后的窄内容区允许纵向排列，表格只在自身容器横滚，不遮挡主操作。 */
@media (max-width: 1100px) {
  .next-step { grid-template-columns: minmax(0, 1fr); gap: 20px; }
  .next-step-actions { border-top: 1px solid var(--do-line); padding-top: 18px; }
  .role-panel { align-items: flex-start; flex-direction: column; }
  .role-actions { justify-content: flex-start; }
  .readiness-note { flex-basis: 100%; margin-left: 0; }
}
@media (max-width: 720px) {
  .workbench-stats { grid-template-columns: minmax(0, 1fr); }
  .section-heading { flex-wrap: wrap; }
  .readiness-overview, .source-heading, .next-step, .approval-panel, .role-panel, .empty-work, .noaccess { padding-left: 16px; padding-right: 16px; }
  .source-tools { padding-left: 16px; padding-right: 16px; }
}
</style>
