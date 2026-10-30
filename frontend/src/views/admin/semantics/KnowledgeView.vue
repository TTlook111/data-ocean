<script setup lang="ts">
/**
 * 语义知识工作区（列表 + 审核队列）
 *
 * 替代旧的 KnowledgeDashboard.vue 和 ReviewPage.vue：
 * - 审核队列不再是孤立页面，而是本工作区的一个 Tab，URL 由 `?tab=review` 恢复。
 * - 列表同时呈现状态、版本、覆盖表，用于判断「知识准备情况」而不只是文档标题。
 * - 数据源范围沿用全局 ScopeBar（路由上下文为 datasource），页面内不再另建选择器。
 *   未限定数据源时展示全部文档并给出说明。
 *
 * 后端事实：文档发布主链为 DRAFT -> PENDING_REVIEW -> APPROVED -> PUBLISHED，
 * 审核、文档发布和数据源级 RAG build 是三个独立的人工确认阶段。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, FileText, MessageSquareText, Plus, RefreshCw, Sparkles, X } from 'lucide-vue-next'
import {
  approveDoc,
  confirmRagBuild,
  generateFromSnapshot,
  listKnowledgeDocs,
  getKnowledgeDoc, listSourceSnapshots,
  listRagBuilds,
  rejectDoc,
  type RagIndexBuildSummary,
  type KnowledgeDocItem,
} from '../../../api/admin/knowledge'
import {
  getBatchDatasourceReadiness,
  type DatasourceReadiness,
} from '../../../api/admin/datasource'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import { getPublishedSnapshot, type VersionHistoryItem } from '../../../api/admin/versioning'
import { knowledgeStatusLabel } from '../../../utils/enumLabels'
import { findDomainHome, resolveReadinessAction } from '../../../utils/adminNavigation'
import { useAdminContextStore, type AdminDatasourceOption } from '../../../stores/adminContext'
import { useAuthStore } from '../../../stores/auth'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import { pendingKnowledgeWrites, trackKnowledgeWrite } from '../../../composables/knowledgeWriteTracker'
import { useIamS1Store } from '../../../stores/iamS1'
import KnowledgeSourcePreview from '../../../components/admin/KnowledgeSourcePreview.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import TitleHelp from '../../../components/admin/TitleHelp.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'
import ManualJoinPathPanel from './ManualJoinPathPanel.vue'

const route = useRoute()
const router = useRouter()
// 未知 readiness 状态码的安全落点：当前业务域的首个工作区
const domainHome = computed(() => findDomainHome(String(route.meta.domainKey || '')))
const context = useAdminContextStore()

const iamS1 = useIamS1Store(),auth=useAuthStore()

/**
 * 知识文档的四个功能码都属于「源」范围，同一绑定上必须同时有功能与该文档数据源的负责范围。
 *
 * 这里只做粗粒度按钮可用性：页面不知道每份文档的具体归属，真正的判定由后端按
 * 文档真实 datasourceId 完成（KNOWLEDGE_DOCUMENT 解析器 + 同绑定校验）。
 * 判定值只来自 Java 的 S1 能力摘要，不读取旧 permissions / roles / JWT authority。
 */
const canManage = computed(
  () => iamS1.systemAdmin || iamS1.datasourcesWithFunction('knowledge:manage').length > 0,
)

const MANAGE_HINT = '没有“维护知识文档”能力：需要角色拥有 knowledge:manage 并负责目标数据源。'
const APPROVE_HINT = '没有“审核知识文档”能力：需要角色拥有 knowledge:approve 并负责目标数据源。审核权不自动带来维护或发布权。'
const PUBLISH_HINT = '没有“发布知识文档”能力：需要角色拥有 knowledge:publish 并负责目标数据源。'
const GENERATE_HINT = '生成快照目录需要当前数据源的知识维护与元数据查看能力；仍可手动新建或编辑已授权文档。'

function canViewSnapshots(id?: number) {
  return iamS1.systemAdmin || (id != null && iamS1.datasourcesWithFunction('metadata:view').includes(id))
}
function canGenerateForDatasource(id?: number) {
  return id != null && canViewSnapshots(id)
    && (iamS1.systemAdmin || iamS1.datasourcesWithFunction('knowledge:manage').includes(id))
}

function canPublishForDatasource(id: number) {
  return iamS1.systemAdmin
    || iamS1.datasourcesWithFunction('knowledge:publish').includes(id)
}
const canViewManualJoins = computed(
  () => iamS1.systemAdmin || (context.datasourceId != null && iamS1.datasourcesWithFunction('lineage:view').includes(context.datasourceId)),
)
const canManageManualJoins = computed(
  () => iamS1.systemAdmin || (context.datasourceId != null && iamS1.datasourcesWithFunction('lineage:manage').includes(context.datasourceId)),
)

const activeTab = ref(String(route.query.tab || 'documents'))
const docs = ref<KnowledgeDocItem[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const actionLoading = ref(false)
const datasources = ref<AdminDatasourceOption[]>([])
const readinessDatasources = computed(() => datasources.value.filter(item => iamS1.systemAdmin || iamS1.datasourcesWithFunction('datasource:view').includes(item.id)))
const generatableDatasources = computed(() => datasources.value.filter(item => canGenerateForDatasource(item.id)))
const publishedSnapshot = ref<VersionHistoryItem | null>(null)
const ragBuilds = ref<RagIndexBuildSummary[]>([])
const ragBuildsLoading = ref(false)
const ragBuildActionDatasourceId = ref<number | null>(null)
const ragBuildError = ref('')
const page = ref(Number(route.query.page) || 1)
const pageSize = 20

const filters = reactive({ status: '', keyword: '' })
const stats = reactive({ total: 0, published: 0, indexing: 0, pending: 0, draft: 0 })
/** 统计口径来自服务端 total；失败时记录错误而不静默保留旧值（§11.3） */
const statsError = ref('')
const statsLoading = ref(true)
/**
 * 每个数据源的知识准备情况。
 *
 * 开发指导 §7.11 要求列表页展示「每个数据源的知识准备情况」，而不只是文档标题。
 * 直接复用批量就绪度接口的 `knowledgeReady` / `publishedKnowledgeDocId` /
 * `knowledgeVersion` —— 这些是后端算好的事实，前端不得重新拼装（§3.1）。
 */
const readinessList = ref<DatasourceReadiness[]>([])
const readinessLoading = ref(false)
const readinessError = ref('')
const readinessWarning = ref('')
const datasourcesError = ref('')
const publishedSnapshotError = ref('')
const publishedSnapshotLoading = ref(false)
const snapshotsError = ref('')
const snapshotsLoading = ref(false)
let requestId = 0
let disposed = false
let scopeEpoch = 0
let statsRequestId = 0
let publishedRequestId = 0
let ragRequestId = 0
let snapshotRequestId = 0
let generationRequestId = 0
onBeforeUnmount(() => { closeOwnedConfirm(); closeBuild(); disposed = true; scopeEpoch++; requestId++; snapshotRequestId++; generationRequestId++ })

const datasourceId = computed(() => context.datasourceId)
const scopeError=computed(()=>{
  if(iamS1.errorMessage)return iamS1.errorMessage
  const raw=route.query.datasourceId
  if(raw!=null && (typeof raw!=='string' || !Number.isSafeInteger(Number(raw)) || Number(raw)<=0 || Number(raw)!==datasourceId.value))return '指定数据源尚未核验，未改用其他范围。'
  if(iamS1.loaded && datasourceId.value && !iamS1.systemAdmin && !iamS1.datasourcesWithFunction('knowledge:view').includes(datasourceId.value))return '没有当前数据源的knowledge:view。'
  return ''
})
const scopeReady=computed(()=>iamS1.loaded && !scopeError.value && !context.routeSyncing && !context.loading)
const actionError=ref(''),reviewComment=ref('')
const confirmVisible=ref(false),confirmClass=`knowledge-list-confirm-${Math.random().toString(36).slice(2)}`
let confirmSequence=0,actionSequence=0,readinessSequence=0,datasourceSequence=0,buildSequence=0
function closeOwnedConfirm(){confirmSequence++;document.querySelector<HTMLButtonElement>(`.${confirmClass}`)?.click();confirmVisible.value=false}
function scopeTicket(){return {epoch:scopeEpoch,identity:auth.token}}
function current(t:ReturnType<typeof scopeTicket>){return !disposed && t.epoch===scopeEpoch && auth.token===t.identity && scopeReady.value}
const currentScope=current
function canOn(code:string,source:number){return scopeReady.value && (iamS1.systemAdmin || iamS1.datasourcesWithFunction(code).includes(source))}
function canReviewDoc(item:KnowledgeDocItem){return canOn('knowledge:approve',item.datasourceId)}
const buildVisible=ref(false),buildSnapshotId=ref<number>(),buildSource=ref<number>()
const buildOptions=ref<{id:number;label:string}[]>([]),buildOptionsLoading=ref(false),buildOptionsError=ref('')
const unknownBuild=computed(()=>pendingKnowledgeWrites.value.find(x=>x.identity===auth.token && x.source===(buildVisible.value ? buildSource.value : datasourceId.value) && x.kind==='rag-build'))
const currentBuild=computed(()=>ragBuilds.value.find(x=>x.status==='ACTIVE'))
async function openBuild(row?:DatasourceReadiness){
  const source=row?.datasourceId || datasourceId.value
  if(!source || !canOn('knowledge:publish',source) || ragBuildActionDatasourceId.value!=null)return
  const t=scopeTicket(),seq=++buildSequence;buildSource.value=source;buildSnapshotId.value=row?.publishedSnapshotId || (publishedSnapshot.value?.datasourceId===source ? publishedSnapshot.value.snapshotId : undefined);buildVisible.value=true;buildOptions.value=[];buildOptionsError.value='';buildOptionsLoading.value=true
  try{
    // The visible document page/filter is not the complete set of build sources.
    const candidates=new Map<number,KnowledgeDocItem>()
    let sourcePage=1
    while(true){
      const result=await listKnowledgeDocs({datasourceId:source,status:'PUBLISHED',page:sourcePage,pageSize})
      if(!current(t) || seq!==buildSequence || !buildVisible.value)return
      const records=result.data?.records || [],total=result.data?.total || 0
      const previousSize=candidates.size
      for(const doc of records)if(doc.datasourceId===source && doc.status==='PUBLISHED')candidates.set(doc.id,doc)
      if(sourcePage*pageSize>=total)break
      if(candidates.size===previousSize)throw new Error('已发布文档来源分页未完整返回，请重试或核对精确来源ID。')
      sourcePage++
    }
    const results=await Promise.all([...candidates.values()].map(d=>listSourceSnapshots(d.id)))
    if(!current(t) || seq!==buildSequence || !buildVisible.value)return
    const options=new Map<number,string>()
    for(const r of results)for(const value of r.data || [])if(['PUBLISHED','EXPIRED'].includes(value.status || ''))options.set(value.snapshotId,`快照 #${value.snapshotId} · v${value.snapshotVersion} · ${value.status==='EXPIRED'?'曾发布历史来源':'已发布'}`)
    buildOptions.value=[...options].map(([id,label])=>({id,label}))
  }catch(cause){if(current(t) && seq===buildSequence)buildOptionsError.value=apiError(cause,'已发布文档来源读取失败；可核对后填写精确来源ID')}
  finally{if(current(t) && seq===buildSequence)buildOptionsLoading.value=false}
}
async function confirmBuild(){
  const source=buildSource.value,snapshot=buildSnapshotId.value
  if(!buildVisible.value || !source || !snapshot || !Number.isSafeInteger(snapshot) || snapshot<=0 || !canOn('knowledge:publish',source) || ragBuildActionDatasourceId.value!=null || unknownBuild.value)return
  const t=scopeTicket(),seq=++buildSequence;ragBuildActionDatasourceId.value=source;actionError.value=''
  try{
    await trackKnowledgeWrite({identity:t.identity,source,snapshotId:snapshot,kind:'rag-build',started:Date.now()},()=>confirmRagBuild(source,snapshot))
    if(!current(t) || seq!==buildSequence)return
    buildVisible.value=false;ElMessage.success('构建请求已接受；仅新build核验通过后才切换，旧build资格由后端判断。');await Promise.all([loadRagBuilds(),loadReadiness()])
  }catch(cause){if(current(t) && seq===buildSequence)actionError.value=apiError(cause,'构建请求未完成')+'；请重读构建记录核对原来源；旧build不证明本次成功，结果未知不自动重发。'}
  finally{if(current(t) && seq===buildSequence)ragBuildActionDatasourceId.value=null}
}
function closeBuild(){buildSequence++;buildVisible.value=false;buildOptionsLoading.value=false;buildOptions.value=[]}

const canGenerateSnapshot = computed(() => canGenerateForDatasource(datasourceId.value))
// A single-source count uses the same authorized records shown below, not datasource:view readiness.
const activeIndexSummary = computed(() => {
  if (!datasourceId.value) return { value: '未汇总', hint: '选择单个数据源，查看该来源的活动索引。' }
  if (!scopeReady.value) return { value: '核验中', hint: '正在核验知识查看范围。' }
  if (ragBuildsLoading.value) return { value: '读取中', hint: '正在读取当前来源的构建记录。' }
  if (ragBuildError.value) return { value: '读取失败', hint: '活动索引状态未知，请刷新构建记录。' }
  return { value: String(ragBuilds.value.filter(row => row.status === 'ACTIVE').length), hint: '按当前来源可见的构建记录统计；活动状态不代表所有业务口径已验证。' }
})
const buildBlockedReason = computed(() => {
  if (!scopeReady.value) return '正在核验知识范围，暂不能准备构建。'
  if (!datasourceId.value) return '请先选择一个数据源。'
  if (!canPublishForDatasource(datasourceId.value)) return '需要当前数据源的 knowledge:publish（发布知识文档）能力；可继续查看已授权的构建记录。'
  if (unknownBuild.value) return '已有构建请求执行中或结果未知，请刷新构建记录核对，不能重复提交。'
  if (ragBuildActionDatasourceId.value != null) return '正在提交构建请求。'
  return ''
})
const datasourceName = (id: number) => datasources.value.find((item) => item.id === id)?.name || `数据源 #${id}`
const readinessAction = (row: DatasourceReadiness) => {
  const reason = row.blockReasons?.[0]
  if (!reason) return null
  return {
    label: reason.actionText || '去处理',
    target: resolveReadinessAction(reason.code, {
      datasourceId: row.datasourceId,
      snapshotId: row.publishedSnapshotId,
    }),
  }
}

/** 后端以 JSON 数组字符串保存覆盖表名，展示时做一次安全解析 */
function parseTableNames(value?: string): string[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return value.split(',').map((item) => item.trim()).filter(Boolean)
  }
}

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

/**
 * 切换 Tab。
 *
 * 用 `push` 而不是 `replace`：开发指导 §15 要求「URL 刷新、前进和后退恢复」，
 * 只有 push 才能让浏览器后退回到上一个 Tab。全站 Tab 统一切换语义。
 */
function selectTab(tab: string) {
  activeTab.value = tab
  page.value = 1
  router.push({ query: { ...route.query, tab, page: undefined } })
  loadDocs()
}

async function loadDocs() {
  if(!scopeReady.value){docs.value=[];total.value=0;loading.value=false;return}
  const current = ++requestId
  const scope=scopeTicket()
  loading.value = true
  error.value = ''
  try {
    const status = activeTab.value === 'review' ? 'PENDING_REVIEW' : (filters.status || undefined)
    const result = await listKnowledgeDocs({
      datasourceId: datasourceId.value,
      status,
      page: page.value,
      pageSize,
    })
    if (!currentScope(scope) || current !== requestId) return
    docs.value = result.data?.records || []
    total.value = result.data?.total || 0
  } catch (cause) {
    if (!currentScope(scope) || current !== requestId) return
    docs.value = []
    error.value = apiError(cause, '知识文档加载失败')
  } finally {
    if (!disposed && current === requestId) loading.value = false
  }
}

/** 各状态全量计数：分别按状态取 total，避免用当前分页数据推断全局 */
async function loadStats() {
  if (disposed || !scopeReady.value) {statsLoading.value=false;return}
  const sequence = ++statsRequestId
  const epoch = scopeEpoch
  const source = datasourceId.value
  const current = () => !disposed && scopeReady.value && epoch === scopeEpoch && source === datasourceId.value && sequence === statsRequestId
  statsLoading.value = true
  statsError.value = ''
  try {
    const base = { datasourceId: datasourceId.value, page: 1, pageSize: 1 }
    const [all, published, indexing, pending, draft] = await Promise.all([
      listKnowledgeDocs(base),
      listKnowledgeDocs({ ...base, status: 'PUBLISHED' }),
      listKnowledgeDocs({ ...base, status: 'INDEXING' }),
      listKnowledgeDocs({ ...base, status: 'PENDING_REVIEW' }),
      listKnowledgeDocs({ ...base, status: 'DRAFT' }),
    ])
    if (!current()) return
    Object.assign(stats, {
      total: all.data?.total || 0,
      published: published.data?.total || 0,
      indexing: indexing.data?.total || 0,
      pending: pending.data?.total || 0,
      draft: draft.data?.total || 0,
    })
  } catch (cause) {
    // 统计失败不阻断主列表，但必须说明统计不可用——不能让用户把「统计失败」
    // 读成「全都是 0」（§11.3、§18）。
    if (current()) statsError.value = apiError(cause, '状态统计加载失败')
  } finally {
    if (current()) statsLoading.value = false
  }
}

async function loadDatasources() {
  const seq=++datasourceSequence,epoch=scopeEpoch
  datasourcesError.value = ''
  try {
    await context.initialize()
    if(disposed || seq!==datasourceSequence || epoch!==scopeEpoch)return
    datasources.value = context.datasources
  } catch (cause) {
    if(disposed || seq!==datasourceSequence || epoch!==scopeEpoch)return
    datasources.value = []
    datasourcesError.value = apiError(cause, '数据源列表加载失败')
  }
  await loadReadiness()
}

/** 批量取各数据源的知识就绪度；知识准备情况总览的数据来源 */
async function loadReadiness() {
  const seq=++readinessSequence,t=scopeTicket()
  const accept=()=>current(t) && seq===readinessSequence
  if (!readinessDatasources.value.length) {
    readinessList.value = []
    readinessError.value = ''; readinessWarning.value = ''; readinessLoading.value = false
    return
  }
  readinessLoading.value = true
  readinessError.value = ''
  readinessWarning.value = ''
  try {
    const result = await getBatchDatasourceReadiness(readinessDatasources.value.map((item) => item.id))
    if(!accept())return
    readinessList.value = result.data
    if (result.failedDatasourceIds.length) {
      readinessWarning.value = `部分数据源就绪度加载失败：${result.failedDatasourceIds.join('、')}`
    }
  } catch (cause) {
    if(!accept())return
    readinessList.value = []
    readinessError.value = cause instanceof Error ? cause.message : '数据源知识准备情况加载失败'
  } finally {
    if(!accept())return
    readinessLoading.value = false
  }
}

/** 判断当前数据源是否已有正式发布快照，用作生成完整快照目录的输入。 */
async function loadPublishedSnapshot() {
  if (disposed) return
  const sequence = ++publishedRequestId
  const epoch = scopeEpoch
  const source = datasourceId.value
  const current = () => !disposed && scopeReady.value && epoch === scopeEpoch && source === datasourceId.value && sequence === publishedRequestId
  publishedSnapshotError.value = ''
  if (!source || (!iamS1.systemAdmin && !iamS1.datasourcesWithFunction('metadata:release:view').includes(source))) { publishedSnapshot.value = null; publishedSnapshotLoading.value = false; return }
  publishedSnapshotLoading.value = true
  try {
    const result = await getPublishedSnapshot(source)
    if (current()) publishedSnapshot.value = result.data || null
  } catch (cause) {
    if (!current()) return
    publishedSnapshot.value = null
    publishedSnapshotError.value = apiError(cause, '已发布快照加载失败')
  } finally {
    if (current()) publishedSnapshotLoading.value = false
  }
}

async function loadRagBuilds() {
  if (disposed) return
  const sequence = ++ragRequestId
  const epoch = scopeEpoch
  const source = datasourceId.value
  const current = () => !disposed && scopeReady.value && epoch === scopeEpoch && source === datasourceId.value && sequence === ragRequestId
  ragBuildError.value = ''
  if (!source || !canOn('knowledge:view',source)) { ragBuilds.value = []; ragBuildsLoading.value = false; return }
  ragBuildsLoading.value = true
  try {
    const result = await listRagBuilds(source)
    if (current()) ragBuilds.value = result.data || []
  } catch (cause) {
    if (!current()) return
    ragBuilds.value = []
    ragBuildError.value = apiError(cause, 'RAG 构建状态加载失败')
  } finally {
    if (current()) ragBuildsLoading.value = false
  }
}


function ragStatusLabel(status:string){return ({ACTIVE:'活动中',QUEUED:'已排队',BUILDING:'构建中',VALIDATING:'核验中',FAILED:'构建失败',VERIFIED:'核验通过',CLEANUP_PENDING:'已替换，待引用结束后清理',FAILED_CLEANUP_PENDING:'失败，待清理',CLEANED:'已清理',SUPERSEDED:'已被更新代际替代'} as Record<string,string>)[status] || status}
const visibleDocs = computed(() => {
  if (activeTab.value === 'review') return docs.value
  const keyword = filters.keyword.trim().toLowerCase()
  if (!keyword) return docs.value
  return docs.value.filter((doc) =>
    [doc.title, ...parseTableNames(doc.tableNames)].some((value) => value?.toLowerCase().includes(keyword)),
  )
})

const emptyMessage = computed(() => {
  if (activeTab.value === 'review') return '当前没有待审核的知识文档，审核队列是空的。'
  if (!datasourceId.value) return '还没有任何知识文档。选择数据源后可以从快照生成知识。'
  if (!canGenerateSnapshot.value) return '当前筛选条件下没有知识文档。有维护能力时可手动新建；按快照生成还需要元数据查看能力。'
  if (publishedSnapshotError.value) return '已发布快照状态加载失败，暂时无法判断是否可以生成知识。'
  if(!iamS1.systemAdmin && !iamS1.datasourcesWithFunction('metadata:release:view').includes(datasourceId.value!))return '当前账号不能读取正式快照摘要；这不代表没有快照。可手动新建或在生成窗口明确选择有权快照。'
  if (!publishedSnapshot.value) return '当前数据源还没有正式发布的快照。按快照生成需要已发布来源。'
  return '当前筛选条件下没有知识文档。'
})

const emptyAction = computed(() => {
  if (activeTab.value === 'review') return ''
  if (!canGenerateSnapshot.value) return canManage.value ? '手动新建文档' : ''
  if (publishedSnapshotError.value) return ''
  if (!publishedSnapshot.value && datasourceId.value && (iamS1.systemAdmin || iamS1.datasourcesWithFunction('metadata:release:view').includes(datasourceId.value))) return '去元数据版本'
  return '生成快照目录'
})

function handleEmptyAction() {
  if (!canGenerateSnapshot.value) { if (canManage.value) goCreate(); return }
  if (!publishedSnapshot.value && datasourceId.value && (iamS1.systemAdmin || iamS1.datasourcesWithFunction('metadata:release:view').includes(datasourceId.value))) {
    router.push({ path: '/admin/releases', query: { datasourceId: String(datasourceId.value) } })
    return
  }
  openGenerateDialog()
}

function goDetail(id: number) {
  router.push({ name: 'admin-semantic-knowledge-detail', params: { id }, query:{datasourceId:docs.value.find(x=>x.id===id)?.datasourceId} })
}

function goCreate() {
  if (!canManage.value) { ElMessage.warning(MANAGE_HINT); return }
  router.push({ name: 'admin-semantic-knowledge-new' })
}

function goAsk() {
  if (!iamS1.queryUse) return
  router.push('/query')
}

async function reviewDocument(item:KnowledgeDocItem,rejecting:boolean){
  if(actionLoading.value || !canReviewDoc(item) || item.status!=='PENDING_REVIEW' || pendingKnowledgeWrites.value.some(x=>x.identity===auth.token && x.docId===item.id))return
  const t=scopeTicket(),seq=++actionSequence,value={...item},confirm=++confirmSequence;actionLoading.value=true;confirmVisible.value=true;actionError.value=''
  try{
    if(rejecting){const result=await ElMessageBox.prompt(`请说明驳回「${value.title}」的原因`,'审核驳回',{confirmButtonText:'确认驳回',cancelButtonText:'取消',inputValue:reviewComment.value,inputValidator:v=>!!v?.trim() || '驳回原因不能为空',cancelButtonClass:confirmClass});if(current(t))reviewComment.value=result.value}
    else await ElMessageBox.confirm(`确认审核已保存的「${value.title}」v${value.currentVersion}？发布与构建仍独立。`,'审核通过',{confirmButtonText:'确认',cancelButtonText:'取消',cancelButtonClass:confirmClass})
    if(!current(t) || seq!==actionSequence || !canReviewDoc(value))return
    const latest=(await getKnowledgeDoc(value.id)).data
    if(!current(t) || seq!==actionSequence || !canReviewDoc(value))return
    if(!latest || latest.id!==value.id || latest.datasourceId!==value.datasourceId || latest.version!==value.version || latest.currentVersion!==value.currentVersion || latest.status!=='PENDING_REVIEW'){actionError.value='已保存文档版本发生变化，请重读后审核；本次未执行。';return}
    await trackKnowledgeWrite({identity:t.identity,source:value.datasourceId,docId:value.id,version:value.version,kind:rejecting?'reject':'approve',started:Date.now()},()=>rejecting?rejectDoc(value.id,reviewComment.value):approveDoc(value.id))
    if(current(t) && seq===actionSequence)await Promise.all([loadDocs(),loadStats()])
  }catch(cause){if(cause!=='cancel' && cause!=='close' && current(t) && seq===actionSequence)actionError.value=apiError(cause,'审核未完成')+'；原意见保留，不会自动重试，结果未知请核对原文档。'}
  finally{if(current(t) && seq===actionSequence)actionLoading.value=false;if(confirm===confirmSequence)confirmVisible.value=false}
}
async function approve(item:KnowledgeDocItem){return reviewDocument(item,false)}
async function reject(item:KnowledgeDocItem){return reviewDocument(item,true)}

/* ---------------- 快照目录生成 ---------------- */

const generateDialogVisible = ref(false)
const generateLoading = ref(false)
const generationReady = ref(false)
const generateForm = reactive<{ datasourceId?: number; snapshotId?: number }>({})
const snapshots = ref<SnapshotItem[]>([])
const unknownGeneration=computed(()=>pendingKnowledgeWrites.value.find(x=>x.identity===auth.token && x.source===generateForm.datasourceId && x.snapshotId===generateForm.snapshotId && x.kind==='generate-group'))
const generatedDocs = ref<Array<{ id: number; title: string; tableNames: string[] }>>([])

function openGenerateDialog() {
  if (!canGenerateSnapshot.value) { ElMessage.warning(GENERATE_HINT); return }
  generateForm.datasourceId = datasourceId.value
  generateForm.snapshotId = context.snapshotId
  snapshots.value = []
  generatedDocs.value = []
  generateDialogVisible.value = true
  if (generateForm.datasourceId) loadSnapshots(generateForm.datasourceId)
}

watch(() => generateForm.datasourceId, () => {
  snapshotRequestId++
  generationRequestId++
  generateLoading.value = false
  snapshotsLoading.value = false
  generatedDocs.value = []
  snapshots.value = []
  snapshotsError.value = ''
  generateForm.snapshotId = undefined
}, { flush: 'sync' })
watch(() => generateForm.snapshotId, () => { generationRequestId++; generateLoading.value = false; generatedDocs.value = [] }, { flush: 'sync' })
watch(generateDialogVisible, (visible) => {
  if (!visible) { snapshotRequestId++; generationRequestId++; snapshotsLoading.value = false; generateLoading.value = false; snapshots.value = []; snapshotsError.value = '' }
}, { flush: 'sync' })

async function loadSnapshots(id: number) {
  if (disposed) return
  const sequence = ++snapshotRequestId,epoch=scopeEpoch
  const current = () => !disposed && epoch===scopeEpoch && generateDialogVisible.value && sequence === snapshotRequestId && generateForm.datasourceId === id
  snapshotsError.value = ''
  if (!canViewSnapshots(id)) { snapshots.value = []; snapshotsLoading.value = false; snapshotsError.value = GENERATE_HINT; return }
  snapshotsLoading.value = true
  try {
    const result = await listSnapshots({ datasourceId: id, page: 1, size: 50 })
    if (current()) snapshots.value = result.data?.records || []
  } catch (cause) {
    if (!current()) return
    snapshots.value = []
    snapshotsError.value = apiError(cause, '快照列表加载失败')
  } finally {
    if (current()) snapshotsLoading.value = false
  }
}

function onGenerateDatasourceChange(id?: number) {
  generateForm.snapshotId = undefined
  if (id) loadSnapshots(id)
  else { snapshotRequestId++; snapshots.value = [] }
}

async function runGenerate() {
  if(generateLoading.value || unknownGeneration.value)return
  if (!canGenerateForDatasource(generateForm.datasourceId)) { ElMessage.warning(GENERATE_HINT); return }
  if (!generateForm.datasourceId || !generateForm.snapshotId) {
    ElMessage.warning('请选择数据源和快照')
    return
  }
  const source = generateForm.datasourceId
  const snapshot = generateForm.snapshotId
  const sequence = ++generationRequestId,epoch=scopeEpoch,identity=auth.token
  const current = () => !disposed && epoch===scopeEpoch && identity===auth.token && generateDialogVisible.value && source === generateForm.datasourceId && snapshot === generateForm.snapshotId && sequence === generationRequestId
  generateLoading.value = true
  try {
    const result = await trackKnowledgeWrite({identity,source,snapshotId:snapshot,kind:'generate-group',started:Date.now()},()=>generateFromSnapshot(source, snapshot))
    if (!current()) return
    generatedDocs.value = result.data || []
    ElMessage.success(`已生成 ${generatedDocs.value.length} 份完整快照知识目录草稿`)
    await Promise.all([loadDocs(), loadStats()])
  } catch (cause) {
    if (current()) ElMessage.error(apiError(cause, '快照知识目录生成失败'))
  } finally {
    if (current()) generateLoading.value = false
  }
}

onMounted(async () => {
  await iamS1.load()
  await loadDatasources()
  if (disposed) return
  if (datasourcesError.value) { error.value = datasourcesError.value; statsLoading.value = false; return }
  await Promise.allSettled([loadDocs(), loadStats(), loadPublishedSnapshot(), loadRagBuilds()])
})

watch(()=>[datasourceId.value,route.query.datasourceId,auth.token,iamS1.snapshot,iamS1.loaded,iamS1.errorMessage,context.routeSyncing,context.loading],async()=>{
  scopeEpoch++;requestId++;statsRequestId++;publishedRequestId++;ragRequestId++;readinessSequence++;datasourceSequence++;actionSequence++;closeOwnedConfirm();closeBuild()
  Object.assign(stats,{total:0,published:0,indexing:0,pending:0,draft:0});statsError.value='';publishedSnapshotError.value='';ragBuildError.value='';readinessError.value='';readinessWarning.value='';publishedSnapshotLoading.value=false;ragBuildsLoading.value=false;readinessLoading.value=false;snapshotsLoading.value=false
  generateDialogVisible.value=false;generateLoading.value=false;actionLoading.value=false;ragBuildActionDatasourceId.value=null;docs.value=[];total.value=0;publishedSnapshot.value=null;ragBuilds.value=[];readinessList.value=[];page.value=1;actionError.value='';reviewComment.value=''
  if(!scopeReady.value){loading.value=false;statsLoading.value=false;return}
  datasources.value=context.datasources
  await Promise.all([loadDocs(),loadStats(),loadPublishedSnapshot(),loadRagBuilds(),loadReadiness()])
},{flush:'sync'})
useAdminScrollLock(()=>generateDialogVisible.value || buildVisible.value || confirmVisible.value)

watch(() => route.query.tab, (value) => {
  const next = String(value || 'documents')
  if (next !== activeTab.value) {
    activeTab.value = next
    page.value = 1
    loadDocs()
  }
})

watch(() => route.query.page, (value) => {
  const next = Number(value) || 1
  if (next !== page.value) {
    page.value = next
    loadDocs()
  }
})
</script>

<template>
  <div class="admin-page knowledge-page">
    <p class="knowledge-page__eyebrow">语义中心 / 知识准备</p>
    <TaskPageHeader
      title="语义知识"
      description="准备并审核业务知识，让用户更准确地问数。文档发布后，需单独确认知识索引构建。"
    >
      <template #actions>
        <el-button @click="router.push({path:'/admin/semantics/business-knowledge',query:{datasourceId:context.datasourceId,snapshotId:context.snapshotId}})">业务知识治理</el-button>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadDocs(); loadStats(); loadReadiness()">刷新</el-button>
        <el-button :icon="Plus" :disabled="!canManage" :title="canManage ? '' : MANAGE_HINT" @click="goCreate">手动新建</el-button>
        <el-button type="primary" :icon="Sparkles" :disabled="!canGenerateSnapshot" :title="canGenerateSnapshot ? '' : GENERATE_HINT" @click="openGenerateDialog">生成快照目录</el-button>
      </template>
    </TaskPageHeader>

    <p class="knowledge-page__purpose">把已核对的业务事实整理成主题文档。审核、发布和检索索引各自留有证据。</p>
    <ol class="knowledge-journey"><li><strong>整理主题</strong><span>结构目录与人工正文</span></li><li><strong>审核依据</strong><span>候选需人工采纳与核对</span></li><li><strong>发布文档</strong><span>固定已审核版本与来源</span></li><li><strong>确认构建</strong><span>隔离核验后切换活动索引</span></li></ol>
    <p v-if="scopeError" role="alert" class="knowledge-write-notice">{{ scopeError }}</p>
    <section v-if="actionError" role="alert" class="knowledge-write-notice">{{ actionError }}<p v-if="reviewComment">保留意见：{{ reviewComment }}</p></section>
    <LoadingState v-if="!scopeReady && !scopeError" text="正在核验账号与知识范围…" />
    <ErrorState v-else-if="statsError" :message="statsError" @retry="loadStats" />
    <LoadingState v-else-if="statsLoading" text="正在读取知识统计..." />
    <section v-else-if="scopeReady" class="knowledge-page__stats">
      <div class="stat"><span>文档总数</span><strong>{{ stats.total }}</strong></div>
      <div class="stat stat--success"><span>已发布文档</span><strong>{{ stats.published }}</strong></div>
      <div class="stat stat--success"><span>已生效知识索引</span><strong>{{ activeIndexSummary.value }}</strong><small>{{ activeIndexSummary.hint }}</small></div>
      <div class="stat stat--warning"><span>待审核</span><strong>{{ stats.pending }}</strong></div>
      <div class="stat"><span>草稿</span><strong>{{ stats.draft }}</strong></div>
    </section>

    <!-- 每个数据源的知识准备情况（开发指导 §7.11） -->
    <details v-if="readinessDatasources.length && activeTab!=='index'" class="knowledge-page__readiness"><summary>数据源准备情况 · 按需核对连接与发布摘要</summary>
      <div class="knowledge-page__readiness-heading">
        <div class="knowledge-page__section-title">
          <TitleHelp label="数据源知识准备情况" text="对照采集、发布和索引版本，确认知识是否已生效。发布文档与构建知识索引是两个独立步骤。" />
          <h2>数据源知识准备情况</h2>
        </div>
        <el-button :icon="RefreshCw" :loading="readinessLoading" @click="loadReadiness">刷新准备情况</el-button>
      </div>
      <el-alert v-if="readinessWarning" type="warning" :closable="false" :title="readinessWarning" />
      <ErrorState v-if="datasourcesError || readinessError" :message="datasourcesError || readinessError" @retry="loadDatasources" />
      <LoadingState v-else-if="readinessLoading" variant="skeleton" :rows="3" />
      <EmptyState
        v-else-if="!readinessList.length"
        message="没有可用的数据源。请先在数据接入中创建数据源，再回到这里准备语义知识。"
      />
      <el-table v-else :data="readinessList" stripe size="small">
        <el-table-column prop="datasourceName" label="数据源" min-width="150" />
        <el-table-column label="知识索引" min-width="165">
          <template #default="{ row }">
            <BusinessStatusBadge
              :status="row.ragBuildId ? (row.ragStale ? 'WARNING' : 'PUBLISHED') : 'DRAFT'"
              :label="row.ragBuildId ? (row.ragStale ? '使用已有索引' : '索引已生效') : '尚未构建索引'"
            />
            <span class="knowledge-page__stage">{{ row.stageLabel || '状态待确认' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="版本对照" min-width="240">
          <template #default="{ row }">
            <div class="knowledge-versions"><span>最新采集 {{ row.latestCollectedSnapshotVersion ? 'v' + row.latestCollectedSnapshotVersion : '—' }}</span><span>当前发布 {{ row.snapshotVersion ? 'v' + row.snapshotVersion : '—' }}</span><span>索引来源 {{ row.ragSourceSnapshotVersion ? 'v' + row.ragSourceSnapshotVersion : '—' }}</span></div>
            <details v-if="row.ragNotice" class="knowledge-version-note"><summary>查看版本说明</summary><p>{{ row.ragNotice }}</p></details>
          </template>
        </el-table-column>
        <el-table-column label="下一步" width="150">
          <template #default="{ row }">
            <RouterLink
              v-if="row.publishedKnowledgeDocId"
              class="knowledge-page__link"
              :to="{ name: 'admin-semantic-knowledge-detail', params: { id: row.publishedKnowledgeDocId } }"
            >查看已发布文档</RouterLink>
            <RouterLink
              v-else-if="readinessAction(row)?.target"
              class="knowledge-page__link"
              :to="readinessAction(row)!.target!"
            >{{ readinessAction(row)!.label }}</RouterLink>
            <!-- 未知状态码不得猜测目标，给出当前业务域的安全落点 -->
            <span v-else-if="readinessAction(row)?.label" class="knowledge-page__muted">
              {{ readinessAction(row)!.label }}
              <RouterLink class="knowledge-page__link" :to="domainHome.path">返回{{ domainHome.label }}</RouterLink>
            </span>
            <span v-else class="knowledge-page__muted">等待后端状态</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              :disabled="!row.publishedSnapshotId || !canPublishForDatasource(row.datasourceId)"
              :loading="ragBuildActionDatasourceId === row.datasourceId"
              :title="canPublishForDatasource(row.datasourceId) ? '' : PUBLISH_HINT"
              @click="openBuild(row)"
            >{{ row.ragBuildId ? '更新索引' : '确认构建索引' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </details>



    <LoadingState v-if="publishedSnapshotLoading" variant="skeleton" :rows="2" />
    <ErrorState v-else-if="publishedSnapshotError" :message="publishedSnapshotError" @retry="loadPublishedSnapshot" />
    <p v-else-if="!datasourceId" class="knowledge-page__scope-note">
      当前未限定数据源，列表展示全部知识文档。使用顶部的数据源范围可以聚焦到单个数据源。
    </p>
    <p v-else-if="publishedSnapshot" class="knowledge-page__scope-note is-ok">
      当前数据源已发布快照 v{{ publishedSnapshot.snapshotVersion }}。先审核并发布知识文档，再单独确认知识索引构建。
    </p>

    <details v-if="datasourceId && publishedSnapshot?.snapshotId && canViewManualJoins" class="knowledge-page__details">
      <summary>表关联配置 · 展开管理</summary>
      <ManualJoinPathPanel
      :datasource-id="datasourceId"
      :snapshot-id="publishedSnapshot!.snapshotId"
      :can-view="canViewManualJoins"
      :can-manage="canManageManualJoins"
    />
    </details>

    <el-tabs :model-value="activeTab" @update:model-value="selectTab">
      <el-tab-pane label="全部文档" name="documents">
        <div class="knowledge-page__toolbar">
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 150px" @change="page = 1; loadDocs()">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待审核" value="PENDING_REVIEW" />
            <el-option label="已批准" value="APPROVED" />
            <el-option label="索引中" value="INDEXING" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="已废弃" value="DEPRECATED" />
          </el-select>
          <el-input v-model="filters.keyword" placeholder="按标题或覆盖表过滤本页" clearable style="width: 240px" />
        </div>
      </el-tab-pane>
      <el-tab-pane label="构建与索引" name="index"><p class="knowledge-page__tab-note">文档发布后，另行确认构建；新索引核验通过后才生效。</p></el-tab-pane>
      <el-tab-pane label="审核队列" name="review">
        <p class="knowledge-page__tab-note">
          审核队列只列出 PENDING_REVIEW 文档。通过后仍需发布文档，再在知识列表显式确认 RAG 构建；符合来源与覆盖要求且构建生效后才可用于检索。
        </p>
      </el-tab-pane>
    </el-tabs>

    <section v-if="datasourceId && activeTab==='index'" class="knowledge-page__readiness knowledge-page__details">
      <div class="knowledge-index-heading"><div><p class="knowledge-page__eyebrow">来源、发布、活动索引</p><h2>知识索引</h2><p>{{ currentBuild ? '当前活动build：'+currentBuild.buildId+' · 来源快照 #'+currentBuild.sourceSnapshotId : ragBuildsLoading ? '正在读取活动索引' : ragBuildError ? '活动索引状态未知' : '尚无活动build记录' }}</p><p>旧build可在来源与授权仍有效时继续使用。来源失效需核对，不把版本落后直接判为不可用。</p></div><div class="knowledge-build-action"><el-button type="primary" :disabled="!!buildBlockedReason" aria-describedby="rag-build-reason" @click="openBuild()">准备构建</el-button><p v-if="buildBlockedReason" id="rag-build-reason" role="status">{{ buildBlockedReason }}</p><small v-else>先核对来源与选项，再单独确认提交。</small></div></div>
      <p v-if="unknownBuild" role="alert" class="knowledge-write-notice">快照 #{{ unknownBuild.snapshotId }} 的构建请求执行中或结果未知。请刷新记录核对；已有旧build不证明本次成功，不会自动重发。</p>
      <div class="knowledge-page__readiness-heading">
        <div>
          <div class="knowledge-page__section-title"><TitleHelp label="知识索引构建记录" text="只有经过授权人员明确确认的构建会排队；旧索引在新索引通过数量核验并切换前保持活动。" /><h3>知识索引构建记录 · {{ datasourceName(datasourceId) }}</h3></div>
        </div>
        <el-button :icon="RefreshCw" :loading="ragBuildsLoading" @click="loadRagBuilds">刷新构建记录</el-button>
      </div>
      <ErrorState v-if="ragBuildError" :message="ragBuildError" @retry="loadRagBuilds" />
      <LoadingState v-else-if="ragBuildsLoading" text="正在读取构建记录…" />
      <p v-else-if="!ragBuilds.length" class="knowledge-page__tab-note">当前来源没有可见的构建记录。</p>
      <div v-else class="rag-build-records">
      <div class="rag-build-cards" aria-label="构建记录摘要">
        <article v-for="row in ragBuilds" :key="row.buildId" class="rag-build-card">
          <strong :class="{'is-active':row.status==='ACTIVE'}">{{ ragStatusLabel(row.status) }}</strong><span>来源快照 #{{ row.sourceSnapshotId }}</span>
          <p>向量核验：{{ row.actualVectorCount ?? '未提供' }} / {{ row.expectedChunkCount ?? '未提供' }}</p>
          <details><summary>技术详情与确认时间</summary><dl><div><dt>Embedding</dt><dd>{{ row.embeddingModel || '未提供' }}</dd></div><div><dt>精确 build</dt><dd>{{ row.buildId }}</dd></div><div><dt>确认时间</dt><dd>{{ row.confirmedAt || '未提供' }}</dd></div><div v-if="row.errorMessage"><dt>错误信息</dt><dd>{{ row.errorMessage }}</dd></div></dl></details>
        </article>
      </div>
      <el-table class="rag-build-table" :data="ragBuilds" stripe size="small">
        <el-table-column label="来源快照" width="110">
          <template #default="{ row }">{{ row.sourceSnapshotId }}</template>
        </el-table-column>
        <el-table-column prop="embeddingModel" label="Embedding" min-width="170" />
        <el-table-column label="向量核验" width="150">
          <template #default="{ row }">{{ row.actualVectorCount }} / {{ row.expectedChunkCount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="170"><template #default="{row}">{{ ragStatusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="buildId" label="精确 build" min-width="220" />
        <el-table-column prop="confirmedAt" label="确认时间" min-width="180" />
        <el-table-column prop="errorMessage" label="错误信息" min-width="220" show-overflow-tooltip />
      </el-table>
      </div>
    </section>

    <LoadingState v-if="!scopeReady && !scopeError" text="正在核验文档查看范围…" />
    <ErrorState v-else-if="activeTab!=='index' && error" :message="error" @retry="loadDocs" />
    <LoadingState v-else-if="activeTab!=='index' && loading" variant="skeleton" :rows="6" />
    <EmptyState
      v-else-if="scopeReady && activeTab!=='index' && !visibleDocs.length"
      :message="emptyMessage"
      :action-text="emptyAction"
      @action="handleEmptyAction"
    />
    <section v-else-if="activeTab!=='index'" class="knowledge-page__card">
      <el-table :data="visibleDocs" v-loading="actionLoading" stripe>
        <el-table-column v-if="activeTab === 'review'" type="expand">
          <template #default="{ row }"><pre class="knowledge-page__review-content">{{ row.content || '文档正文为空' }}</pre></template>
        </el-table-column>
        <el-table-column label="文档" min-width="220">
          <template #default="{ row }">
            <button type="button" class="doc-link" @click="goDetail(row.id)">
              <FileText :size="14" />
              <span>{{ row.title }}</span>
            </button>
            <small class="doc-tables">
              覆盖 {{ parseTableNames(row.tableNames).length }} 张表
              <template v-if="parseTableNames(row.tableNames).length">
                ：{{ parseTableNames(row.tableNames).slice(0, 3).join('、') }}{{ parseTableNames(row.tableNames).length > 3 ? ' 等' : '' }}
              </template>
            </small>
          </template>
        </el-table-column>
        <el-table-column label="数据源" width="150">
          <template #default="{ row }">{{ datasourceName(row.datasourceId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <BusinessStatusBadge :status="row.status" :label="knowledgeStatusLabel(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="版本" width="82">
          <template #default="{ row }">v{{ row.currentVersion }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
            <template v-if="activeTab === 'review' && row.status === 'PENDING_REVIEW'">
              <el-button link type="success" :disabled="!canReviewDoc(row)" :title="canReviewDoc(row) ? '' : APPROVE_HINT" @click="approve(row)"><Check :size="14" />通过</el-button>
              <el-button link type="danger" :disabled="!canReviewDoc(row)" :title="canReviewDoc(row) ? '' : APPROVE_HINT" @click="reject(row)"><X :size="14" />驳回</el-button>
            </template>
            <el-button v-else-if="row.status === 'PUBLISHED' && iamS1.queryUse" link @click="goAsk()">
              <MessageSquareText :size="14" />去问数
            </el-button>
            <el-button v-else-if="row.status === 'DRAFT'" link @click="goDetail(row.id)">继续编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-pagination
      v-if="total > pageSize"
      class="knowledge-page__pager"
      background
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      @current-change="(value: number) => router.replace({ query: { ...route.query, page: value } })"
    />

    <el-dialog v-model="buildVisible" title="明确确认数据源级 RAG 构建" class="knowledge-build-dialog" width="min(680px,94vw)" :close-on-click-modal="ragBuildActionDatasourceId==null" :close-on-press-escape="ragBuildActionDatasourceId==null" :show-close="ragBuildActionDatasourceId==null">
      <p>数据源：{{ buildSource ? datasourceName(buildSource) : '未选择' }}。明确固定来源快照，构建将固定符合条件的文档版本、来源与完整依赖，并核对整组覆盖。</p>
      <p>当前发布或曾发布的历史快照均需核对审核、来源与覆盖条件；可与最新采集版本不同。旧活动索引不会因打开此窗口失效。</p>
      <ErrorState v-if="buildOptionsError" :message="buildOptionsError" />
      <el-select v-if="buildOptions.length" v-model="buildSnapshotId" aria-label="从文档来源选择构建快照" placeholder="从已发布文档来源选择" :disabled="ragBuildActionDatasourceId!=null"><el-option v-for="item in buildOptions" :key="item.id" :value="item.id" :label="item.label" /></el-select>
      <label class="knowledge-build-snapshot">精确来源快照 ID <el-input-number v-model="buildSnapshotId" aria-label="构建来源快照ID" :min="1" :step="1" :precision="0" :disabled="ragBuildActionDatasourceId!=null" /></label>
      <p v-if="!buildOptions.length">未获取到可选文档来源时，请从已核对的文档来源记录填写精确ID；不会调用无权的元数据或数据源接口。</p>
      <p v-if="actionError || unknownBuild" role="alert" class="knowledge-write-notice">{{ actionError || '请求结果待确认，停止重发。' }}</p>
      <template #footer><el-button :disabled="ragBuildActionDatasourceId!=null" @click="closeBuild">取消</el-button><el-button type="primary" :disabled="!buildSnapshotId || !!unknownBuild" :loading="ragBuildActionDatasourceId!=null" @click="confirmBuild">确认构建</el-button></template>
    </el-dialog>
    <el-dialog v-model="generateDialogVisible" :show-close="!generateLoading" :close-on-press-escape="!generateLoading" title="从快照生成完整知识目录" width="min(680px,94vw)" class="knowledge-build-dialog" :close-on-click-modal="!generateLoading">
      <p v-if="unknownGeneration" role="alert" class="knowledge-write-notice">此来源生成请求执行中或结果待确认，请重读文档列表核对，不能重复创建同组草稿。</p>
      <template v-if="!generatedDocs.length">
        <p class="generate-hint">
          按所选 snapshotId 确定性列出该快照的全部表和字段，标出字段来源、治理状态、已确认关系与血缘。
          缺少可靠释义时保留“待确认”。草稿仍需人工审核、发布，再单独确认数据源级 RAG build。
        </p>
        <el-form label-width="88px">
          <el-form-item label="数据源">
            <el-select v-model="generateForm.datasourceId" :disabled="generateLoading" placeholder="选择数据源" style="width: 100%" @change="onGenerateDatasourceChange">
              <el-option v-for="ds in generatableDatasources" :key="ds.id" :label="ds.name" :value="ds.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="快照">
            <ErrorState v-if="snapshotsError" :message="snapshotsError" @retry="generateForm.datasourceId && loadSnapshots(generateForm.datasourceId)" />
            <el-select v-model="generateForm.snapshotId" placeholder="选择元数据快照" style="width: 100%" :disabled="generateLoading || !generateForm.datasourceId || snapshotsLoading" :loading="snapshotsLoading" loading-text="正在加载快照">
              <el-option v-for="item in snapshots" :key="item.id" :label="`v${item.snapshotVersion} · ${item.status}`" :value="item.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <KnowledgeSourcePreview :datasource-id="generateForm.datasourceId" :snapshot-id="generateForm.snapshotId" @ready="generationReady = $event" />
      </template>

      <template v-else>
        <el-alert type="success" :closable="false" show-icon :title="`已生成 ${generatedDocs.length} 份完整快照目录草稿，下一步是检查、编辑并提交审核`" />
        <ul class="generated-list">
          <li v-for="doc in generatedDocs" :key="doc.id">
            <div>
              <strong>{{ doc.title }}</strong>
              <small>覆盖 {{ doc.tableNames?.length ?? 0 }} 张表</small>
            </div>
            <el-button link type="primary" @click="generateDialogVisible = false; goDetail(doc.id)">编辑草稿</el-button>
          </li>
        </ul>
      </template>

      <template #footer>
        <el-button :disabled="generateLoading" @click="generateDialogVisible = false">{{ generatedDocs.length ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!generatedDocs.length" type="primary" :loading="generateLoading" :disabled="!canManage || !generationReady || !!unknownGeneration" @click="runGenerate">
          <Sparkles :size="15" style="margin-right: 6px" />开始生成
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat small { display:block; margin-top:6px; color:var(--do-muted); font-size:11px; line-height:1.5; max-width:24em; }
.rag-build-records { min-width:0; width:100%; max-width:100%; }
.knowledge-index-heading { min-width:0; max-width:100%; }
.knowledge-index-heading > div:first-child { min-width:0; flex:1 1 420px; overflow-wrap:anywhere; }
.knowledge-build-action { max-width:320px; min-width:0; flex:0 1 280px; }
.knowledge-build-action p,.knowledge-build-action small { display:block; margin:8px 0 0; color:var(--do-muted); font-size:12px; line-height:1.6; }
.rag-build-cards { display:none; }
@media(max-width:720px) {
  .rag-build-table { display:none; }
  .rag-build-cards { display:grid; gap:12px; }
  .rag-build-card { min-width:0; padding:14px; border:1px solid var(--do-line); border-radius:10px; background:var(--do-surface); }
  .rag-build-card > strong { display:block; color:var(--do-ink); margin-bottom:6px; }
  .rag-build-card > strong.is-active { color:var(--do-success); }
  .rag-build-card p,.rag-build-card span,.rag-build-card dl { font-size:13px; }
  .rag-build-card summary { min-height:36px; display:list-item; cursor:pointer; font-size:12px; color:var(--do-primary-strong); }
  .rag-build-card summary:focus-visible { outline:2px solid var(--do-primary); outline-offset:2px; }
  .rag-build-card dl { margin:0; overflow-wrap:anywhere; }
  .rag-build-card dt { margin-top:8px; color:var(--do-muted); }
  .rag-build-card dd { margin:3px 0; }
  .knowledge-build-action { max-width:100%; flex-basis:100%; }
}

.knowledge-page__eyebrow{margin:0;color:var(--do-primary-strong);font-size:12px;font-weight:650;}.knowledge-page__purpose{margin:-8px 0 0;color:var(--do-muted);font-size:14px;line-height:1.6;}
.knowledge-journey{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px;padding:16px 0;list-style:none;margin:0;border-block:1px solid var(--do-line);}.knowledge-journey li{display:grid;gap:6px;border-left:2px solid var(--do-line);padding-left:14px;}.knowledge-journey li:first-child{border-color:var(--do-primary);}.knowledge-journey strong{font-size:13px;}.knowledge-journey span{font-size:12px;color:var(--do-muted);line-height:1.5;}
.knowledge-index-heading{display:flex;justify-content:space-between;gap:20px;align-items:start;flex-wrap:wrap;}.knowledge-index-heading h2{margin:6px 0;}.knowledge-index-heading p{max-width:80ch;color:var(--do-muted);font-size:13px;line-height:1.6;}.knowledge-build-snapshot{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:18px 0;}.knowledge-write-notice{border-left:3px solid var(--do-warning);padding:12px;background:var(--do-soft);font-size:13px;line-height:1.6;overflow-wrap:anywhere;}
@media(max-width:760px){.knowledge-page :deep(.task-page-header__actions){width:100%;max-width:100%;min-width:0;flex-wrap:wrap;justify-content:flex-start;gap:8px;}.knowledge-page :deep(.task-page-header__actions .el-button){margin-left:0;}.knowledge-page :deep(.el-table-fixed-column--right){position:static!important;}.knowledge-journey{grid-template-columns:repeat(2,minmax(0,1fr));gap:16px 8px;}}

.knowledge-page { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.knowledge-page > * { min-width: 0; }
.knowledge-page__section-title { position: relative; width: fit-content; max-width: 100%; padding-right: 14px; }
.knowledge-page__details summary { cursor: pointer; padding: 12px 0; font-size: 14px; color: var(--do-ink); }
.knowledge-page__details[open] summary { margin-bottom: 16px; }
.knowledge-page__details { display: block; }

.knowledge-page__stats { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.stat {
  display: grid;
  gap: 4px;
  padding: 14px 16px;
  border-right: 1px solid var(--do-line);
}
.stat span { color: var(--do-muted); font-size: 12px; }
.stat:last-child { border: 0; }
.stat strong { color: var(--do-ink); font-size: 24px; }
.stat--success strong { color: var(--do-success); }
.stat--warning strong { color: var(--do-warning); }

.knowledge-page__scope-note { margin: 0; padding: 10px 12px; border-radius: var(--do-radius-md); background: var(--do-bg); color: var(--do-muted); font-size: 12px; }

.knowledge-page__readiness {
  display: grid;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-glass-data-bg);
  box-shadow: var(--do-shadow);
}
.knowledge-page__readiness-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.knowledge-page__readiness-heading h2, .knowledge-page__readiness-heading h3 { margin: 0; color: var(--do-ink); font-size: 16px; }
.knowledge-page__stage { display: block; margin-top: 6px; color: var(--do-muted); font-size: 12px; }
.knowledge-versions { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 12px; }
.knowledge-version-note { margin-top: 8px; color: var(--do-muted); font-size: 12px; }
.knowledge-version-note summary { cursor: pointer; }
.knowledge-version-note p { margin: 8px 0 0; white-space: normal; line-height: 1.6; }
.knowledge-page__readiness-heading p { margin: 5px 0 0; color: var(--do-muted); font-size: 12px; max-width: 720px; }
.knowledge-page__link { color: var(--do-primary-strong); font-size: 12px; font-weight: 800; }
.knowledge-page__muted { color: var(--do-muted); }
.knowledge-page__scope-note.is-ok { background: var(--do-success-soft); color: var(--do-ink); }
.knowledge-page__tab-note { margin: 0; color: var(--do-muted); font-size: 12px; }

.knowledge-page__toolbar { display: flex; gap: 10px; flex-wrap: wrap; }

.knowledge-page__card {
  border: 1px solid var(--do-glass-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-glass-data-bg);
  box-shadow: var(--do-shadow);
  overflow: hidden;
}

.doc-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  color: var(--do-primary-strong);
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
}

.doc-tables { display: block; margin-top: 4px; color: var(--do-muted); font-size: 11px; }
.knowledge-page__review-content { max-height: 360px; margin: 0; padding: 14px; overflow: auto; white-space: pre-wrap; background: var(--do-bg); }

.knowledge-page__pager { justify-content: flex-end; }

.generate-hint { margin: 0 0 16px; color: var(--do-muted); font-size: 13px; line-height: 1.7; }
.generated-list { display: grid; gap: 8px; margin: 14px 0 0; padding: 0; list-style: none; }
.generated-list li { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); }
.generated-list strong { display: block; color: var(--do-ink); font-size: 13px; }
.generated-list small { color: var(--do-muted); font-size: 11px; }

@media (max-width: 1180px) {
  .knowledge-page__stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
