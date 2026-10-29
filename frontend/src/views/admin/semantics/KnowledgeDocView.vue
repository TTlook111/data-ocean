<script setup lang="ts">
/**
 * 知识文档详情容器
 *
 * 合并旧 SkillsEditor.vue（编辑、AI 生成草稿、提交、发布）与 VersionList.vue（版本、回滚），
 * 文档主链为「草稿 → 待审核 → 已批准 → 已发布」；数据源级 RAG 构建在知识列表单独确认。
 *
 * 后端事实（KnowledgeDocLifecycleService / KnowledgeDocCrudService）：
 * - submitReview 只接受 DRAFT；approve / reject 只接受 PENDING_REVIEW；publish 只接受 APPROVED。
 * - 内容发生变更时 updateDoc 会把状态重置为 DRAFT，因此「编辑已发布文档」等于创建新版本并重走审核。
 * - 审核记录来自 `GET /{id}/review-tasks`（2026-09-12 新增）。此前 `knowledge_review_task`
 *   只写不读、全项目无 Controller 暴露它，作者被驳回后看不到原因。
 * - 索引任务来自 `GET /{id}/vector-tasks`（2026-09-12 新增）。此前文档处于 `INDEXING` 时
 *   只能显示状态，无法显示进度与失败原因。
 * - `knowledge_doc_version.review_status` 自 2026-09-12（V51）起由 approve/reject 真实写入；
 *   V51 之前的历史行被回填为 UNKNOWN 或按审核任务还原，不再是「恒为待审核」的死列。
 * - rollback 仅创建待审核草稿，不会直接改动活动 RAG build。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GitCompareArrows, MessageSquareText, RefreshCw, RotateCcw, Save, Scissors, Send, Sparkles, Upload } from 'lucide-vue-next'
import {
  approveDoc,
  diffVersions,
  getKnowledgeDoc,
  listRagBuilds,
  listReviewTasks,
  listSourceSnapshots,
  listVectorTasks,
  listVersions,
  previewChunks,
  publishDoc,
  rejectDoc,
  rollbackVersion,
  submitReview,
  updateKnowledgeDoc,
  generateDraft,
  generateFromSnapshot,
  getKnowledgeSourceStatus,
  type KnowledgeChunkPreview,
  type KnowledgeDiffLine,
  type KnowledgeDocItem,
  type KnowledgeReviewRecord,
  type KnowledgeSourceSnapshot,
  type KnowledgeVersionItem,
  type RagIndexBuildSummary,
  type VectorIndexTaskItem,
} from '../../../api/admin/knowledge'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import {
  generationSourceLabel,
  generationSourceType,
  knowledgeReviewStatusLabel,
  knowledgeReviewStatusType,
  knowledgeStatusLabel,
  snapshotStatusLabel,
  vectorTaskStatusLabel,
  vectorTaskStatusType,
} from '../../../utils/enumLabels'
import { useAdminContextStore, type AdminDatasourceOption } from '../../../stores/adminContext'
import { useAuthStore } from '../../../stores/auth'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
import { pendingKnowledgeWrites, trackKnowledgeWrite } from '../../../composables/knowledgeWriteTracker'
import { useIamS1Store } from '../../../stores/iamS1'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import KnowledgeSourcePreview from '../../../components/admin/KnowledgeSourcePreview.vue'
import KnowledgeSemanticAssistant from '../../../components/admin/KnowledgeSemanticAssistant.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import LifecycleStepper from '../../../components/admin/LifecycleStepper.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const context = useAdminContextStore()

const iamS1 = useIamS1Store(), auth=useAuthStore()

/**
 * 文档详情页的四个功能码都是「源」范围，后端按**文档真实 datasourceId** 复核归属
 * （KNOWLEDGE_DOCUMENT 解析器：文档不存在 404、归属缺失或版本/来源快照不一致 409）。
 *
 * 这里只做按钮可用性；前端不是安全边界，后端仍会独立拒绝。
 */
const mayView = computed(
  () => iamS1.loaded && !iamS1.errorMessage && (iamS1.systemAdmin || iamS1.datasourcesWithFunction('knowledge:view').length > 0),
)
const mayManage = computed(
  () => iamS1.systemAdmin || (doc.value != null && iamS1.datasourcesWithFunction('knowledge:manage').includes(doc.value.datasourceId)),
)
const mayApprove = computed(
  () => iamS1.systemAdmin || (doc.value != null && iamS1.datasourcesWithFunction('knowledge:approve').includes(doc.value.datasourceId)),
)
const mayPublish = computed(
  () => iamS1.systemAdmin || (doc.value != null && iamS1.datasourcesWithFunction('knowledge:publish').includes(doc.value.datasourceId)),
)
const mayViewSnapshots = computed(
  () => iamS1.systemAdmin || (doc.value != null && iamS1.datasourcesWithFunction('metadata:view').includes(doc.value.datasourceId)),
)
const mayGenerate = computed(() => mayManage.value && mayViewSnapshots.value)
const GENERATE_HINT = '按快照生成目录需要该数据源的知识维护与元数据查看能力；已授权文档仍可手动编辑。'
const canReadSourceSnapshot = computed(() => !!doc.value && iamS1.loaded && !iamS1.errorMessage && (iamS1.systemAdmin || iamS1.datasourcesWithFunction('metadata:view').includes(doc.value.datasourceId)))
const VIEW_HINT = '没有“查看知识文档”能力：需要角色拥有 knowledge:view 并负责该文档的数据源。'
const APPROVE_HINT = '没有“审核知识文档”能力：需要角色拥有 knowledge:approve 并负责该文档的数据源。'
const PUBLISH_HINT = '没有“发布/回滚知识”能力：需要角色拥有 knowledge:publish 并负责该文档的数据源。审核权不自动带来发布权。'

const docId = computed(() => Number(route.params.id))
const activeTab = ref(String(route.query.tab || 'content'))

const doc = ref<KnowledgeDocItem | null>(null)
const versions = ref<KnowledgeVersionItem[]>([])
const datasources = ref<AdminDatasourceOption[]>([])
const snapshots = ref<SnapshotItem[]>([])
const chunks = ref<KnowledgeChunkPreview[]>([])

/** 审核记录与向量化任务：2026-09-12 后端补齐查询接口后才有数据来源 */
const reviewRecords = ref<KnowledgeReviewRecord[]>([])
const reviewLoading = ref(false)
const reviewError = ref('')
const vectorTasks = ref<VectorIndexTaskItem[]>([])
const tasksLoading = ref(false)
const taskError = ref('')

/** 版本列表与来源快照的错误态：接口失败必须渲染错误，不得降级为空数据 */
const versionsError = ref('')
const snapshotError = ref('')

const loading = ref(true)
const error = ref('')
const saving = ref(false)
const actionLoading = ref(false)
const versionsLoading = ref(false)
const chunksLoading = ref(false)
const chunkError = ref('')
const ragBuilds = ref<RagIndexBuildSummary[]>([])
const ragBuildsLoading = ref(false)
const ragBuildsError = ref('')

const title = ref('')
const content = ref('')
let epoch=0, disposed=false, initialized=false
const sequences:Record<string,number>={}
const expectedOwnSave=ref<{id:number;source:number;version:number;title:string;content:string}>()
const savedStamp=ref(''), formBaseVersion=ref(0), actionError=ref(''), reviewComment=ref('')
const formStamp=computed(()=>JSON.stringify([title.value,content.value]))
const dirty=computed(()=>formStamp.value!==savedStamp.value)
const versionConflict=computed(()=>!!doc.value && doc.value.version!==formBaseVersion.value)
const unknownWrite=computed(()=>pendingKnowledgeWrites.value.find(x=>x.identity===auth.token && x.docId===docId.value))
const confirmVisible=ref(false), confirmClass=`knowledge-doc-confirm-${Math.random().toString(36).slice(2)}`
let confirmSequence=0
function invalidateConfirm() {confirmSequence++;document.querySelector<HTMLButtonElement>(`.${confirmClass}`)?.click();confirmVisible.value=false}
function ticket() {return {epoch,id:docId.value,identity:auth.token}}
function current(t:ReturnType<typeof ticket>) {return !disposed && epoch===t.epoch && docId.value===t.id && auth.token===t.identity && mayView.value}
function readTicket(key:string) {const t=ticket(),request=sequences[key]=(sequences[key]||0)+1,version=doc.value?.version;return ()=>current(t) && request===sequences[key] && (key==='doc' || doc.value?.version===version)}
function canReadDocument() {return !!doc.value && mayView.value && (iamS1.systemAdmin || iamS1.datasourcesWithFunction('knowledge:view').includes(doc.value.datasourceId))}
function documentError(cause:unknown,fallback:string) {actionError.value=apiError(cause,fallback);const status=(cause as {response?:{status?:number}})?.response?.status;if(!status || status>=500 || status===408) actionError.value+='；操作结果待确认，请只读核对原文档与版本，不会自动重发。';else if(status===409) actionError.value+='；草稿与审核意见已保留，不会自动重试。';ElMessage.error(actionError.value)}
async function ownedConfirm(message:string,heading:string) {const seq=++confirmSequence;confirmVisible.value=true;try{await ElMessageBox.confirm(message,heading,{type:'warning',confirmButtonText:'确认',cancelButtonText:'取消',cancelButtonClass:confirmClass})}finally{if(seq===confirmSequence)confirmVisible.value=false}}
async function reloadSavedDraft() {const t=ticket();if(dirty.value) {try{await ownedConfirm('重读已保存版本会替换当前未保存输入，确认继续？','重读已保存版本')}catch{return}}if(current(t)) await loadDoc(true)}

const sourceStatus = ref<{ valid: boolean; reasons: string[] }>()
const sourceStatusError = ref('')
async function loadSourceStatus() {
  const accept=readTicket('sourceStatus');sourceStatus.value=undefined;sourceStatusError.value=''
  if(!canReadDocument()) return
  try {const result=await getKnowledgeSourceStatus(docId.value);if(accept())sourceStatus.value=result.data}
  catch(cause){if(accept())sourceStatusError.value=apiError(cause,'来源状态读取失败')}
}

/** 文档流转只到 PUBLISHED；datasource RAG build 是单独确认的索引生命周期。 */
const lifecycleSteps = computed(() => {
  const status = doc.value?.status || 'DRAFT'
  const order = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'PUBLISHED']
  const index = order.indexOf(status)
  return order.map((key, position) => ({
    key,
    label: knowledgeStatusLabel(key),
    done: index > position,
    current: index === position,
    disabled: index < position,
  }))
})

const status = computed(() => doc.value?.status || 'DRAFT')
const canEdit = computed(() => ['DRAFT', 'APPROVED', 'PUBLISHED'].includes(status.value))
const canSubmitReview = computed(() => status.value === 'DRAFT')
const canReview = computed(() => status.value === 'PENDING_REVIEW')
const canPublish = computed(() => status.value === 'APPROVED')
const isPublished = computed(() => status.value === 'PUBLISHED')
const isIndexing = computed(() => status.value === 'INDEXING')
/**
 * 后端 rollback 自 2026-09-12 起已校验「文档为 PUBLISHED」且「目标版本审核已通过」，
 * 前端仍只对已发布文档开放该入口，与后端前置条件保持一致，避免用户点了才被拒绝。
 */
const canRollback = computed(() => status.value === 'PUBLISHED')

const datasourceName = computed(() => {
  const id = doc.value?.datasourceId
  return datasources.value.find((item) => item.id === id)?.name || (id ? `数据源 #${id}` : '—')
})

/** 覆盖表名：后端以 JSON 数组字符串保存 */
const coveredTables = computed<string[]>(() => {
  const raw = doc.value?.tableNames
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return raw.split(',').map((item) => item.trim()).filter(Boolean)
  }
})

/** 下一步说明：让用户明确当前卡在哪一步，以及完成后的去向 */
const nextStepHint = computed(() => {
  if (isIndexing.value) {
    return '检测到旧版文档级索引任务。活动索引以数据源级 RAG build 指针为准。'
  }
  if (status.value === 'APPROVED') {
    return '文档已审核通过。发布文档后，有权人员还需单独确认数据源级 RAG build。'
  }
  if (status.value === 'PENDING_REVIEW') {
    return '等待审核。审核通过后需要发布文档，再单独确认 RAG build。'
  }
  if (status.value === 'PUBLISHED') {
    return '文档已发布；是否进入检索由活动 RAG build 决定。直接修改会创建新版本并回到草稿。'
  }
  if (status.value === 'DEPRECATED') {
    return '文档已废弃，不再参与检索，当前页面保持只读。'
  }
  return '草稿状态。完成编辑后提交审核，审核通过再发布；随后由有权人员确认数据源级 RAG build。'
})

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function selectTab(tab: string) {
  router.push({ query: { ...route.query, tab } })
}

function goAsk() {
  if (!iamS1.queryUse) return
  router.push('/query')
}

async function loadDoc(discardConfirmed=false) {
  const accept=readTicket('doc'), stamp=formStamp.value
  if(!mayView.value){loading.value=false;error.value=iamS1.errorMessage || VIEW_HINT;return}
  const id=docId.value, raw=route.query.datasourceId
  if(!Number.isSafeInteger(id) || id<=0 || (raw!=null && (typeof raw!=='string' || !Number.isSafeInteger(Number(raw)) || Number(raw)<=0))){loading.value=false;error.value='指定文档或数据源标识无效，未改用其他对象';return}
  loading.value=true;error.value=''
  try {
    const result=await getKnowledgeDoc(id)
    if(!accept())return
    const value=result.data
    if(!value || value.id!==id || (raw!=null && value.datasourceId!==Number(raw)))throw new Error('返回文档与指定对象或数据源不一致')
    if(!iamS1.systemAdmin && !iamS1.datasourcesWithFunction('knowledge:view').includes(value.datasourceId))throw new Error(VIEW_HINT)
    doc.value=value
    const own=expectedOwnSave.value
    if(own && own.id===value.id && own.source===value.datasourceId){if(value.version===own.version+1 && value.title===own.title && (value.content || '')===own.content)formBaseVersion.value=value.version;expectedOwnSave.value=undefined}
    if(!dirty.value || (discardConfirmed && formStamp.value===stamp)) {title.value=value.title || '';content.value=value.content || '';formBaseVersion.value=value.version;savedStamp.value=formStamp.value}
    await loadSourceStatus()
    if(!accept())return
    context.selectDatasource(value.datasourceId);context.selectKnowledgeDoc(value.id)
  } catch(cause){if(accept()){error.value=apiError(cause,'文档加载失败')}}
  finally {if(accept())loading.value=false}
}

async function loadVersions() {
  if(!canReadDocument()) return
  const accept=readTicket('loadVersions')

  versionsLoading.value = true
  versionsError.value = ''
  try {
    const loaded = (await listVersions(docId.value)).data || []
    if(!accept())return
    versions.value = loaded
  } catch (cause) {
    if(!accept())return
    versions.value = []
    // 接口失败必须渲染错误态。此前只弹一次 toast 再置空数组，页面最终显示
    // 「暂无版本记录」——把失败说成了空数据（§11.3、§18）。
    versionsError.value = apiError(cause, '版本列表加载失败')
  } finally {
    if(!accept())return
    versionsLoading.value = false
  }
}

async function refreshDocument() {
  await loadDoc()
  await loadVersions()
  if (activeTab.value === 'source') await loadSourceSnapshots()
  if (activeTab.value === 'review') await loadReviewRecords()
  if (activeTab.value === 'chunks') await loadChunks()
}

/**
 * 加载生成草稿可用的来源快照。
 *
 * 这是「AI 生成草稿」对话框的下拉数据源，失败时不能静默置空——
 * 那会让用户以为该数据源没有快照。改为记录错误，由对话框展示。
 */
async function loadSnapshots() {
  if(!canReadDocument()) return
  const accept=readTicket('loadSnapshots')

  if (!doc.value?.datasourceId || !mayViewSnapshots.value) { snapshots.value = []; return }
  snapshotError.value = ''
  try {
    const loaded = (await listSnapshots({ datasourceId: doc.value.datasourceId, page: 1, size: 50 })).data?.records || []
    if(!accept())return
    snapshots.value = loaded
  } catch (cause) {
    if(!accept())return
    snapshots.value = []
    snapshotError.value = apiError(cause, '来源快照加载失败')
  }
}

async function loadChunks() {
  if(!canReadDocument()) return
  const accept=readTicket('loadChunks')

  chunksLoading.value = true
  chunkError.value = ''
  try {
    const loaded = (await previewChunks(docId.value)).data || []
    if(!accept())return
    chunks.value = loaded
  } catch (cause) {
    if(!accept())return
    chunks.value = []
    chunkError.value = apiError(cause, '切分预览失败，请确认 Python 服务可用')
  } finally {
    if(!accept())return
    chunksLoading.value = false
  }
}

async function save() {
  if(!doc.value || !mayManage.value || !canEdit.value || saving.value || actionLoading.value || unknownWrite.value || versionConflict.value)return
  if(!title.value.trim()){ElMessage.warning('文档标题不能为空');return}
  const t=ticket(),value={...doc.value},stamp=formStamp.value
  const payload={title:title.value,content:content.value,version:formBaseVersion.value,changeSummary:'手动编辑保存'}
  saving.value=true;actionError.value=''
  try {
    await trackKnowledgeWrite({identity:t.identity,source:value.datasourceId,docId:value.id,version:value.version,kind:'save',started:Date.now()},()=>updateKnowledgeDoc(value.id,payload))
    if(!current(t))return
    savedStamp.value=stamp;expectedOwnSave.value={id:value.id,source:value.datasourceId,version:payload.version,title:payload.title,content:payload.content}
    await refreshDocument()
  }catch(cause){if(current(t))documentError(cause,'保存失败')}
  finally{if(current(t))saving.value=false}
}
async function mutateDocument(kind:string,allowed:()=>boolean,message:string,heading:string,write:(value:KnowledgeDocItem)=>Promise<unknown>,prompt=false) {
  if(!doc.value || !canReadDocument() || !allowed() || saving.value || actionLoading.value || unknownWrite.value || dirty.value || versionConflict.value)return
  const t=ticket(),value={...doc.value};actionLoading.value=true;actionError.value=''
  try {
    if(prompt){const seq=++confirmSequence;confirmVisible.value=true;try{const result=await ElMessageBox.prompt(message,heading,{confirmButtonText:'确认驳回',cancelButtonText:'取消',inputValue:reviewComment.value,inputValidator:v=>!!v?.trim() || '驳回原因不能为空',cancelButtonClass:confirmClass});if(current(t))reviewComment.value=result.value}finally{if(seq===confirmSequence)confirmVisible.value=false}}
    else await ownedConfirm(message,heading)
    if(!current(t) || !allowed() || dirty.value)return
    const latest=(await getKnowledgeDoc(value.id)).data
    if(!current(t) || !allowed() || dirty.value)return
    if(!latest || latest.id!==value.id || latest.datasourceId!==value.datasourceId || latest.version!==value.version || latest.currentVersion!==value.currentVersion || latest.status!==value.status){actionError.value='已保存修订发生变化，请重读后核对；本次未执行。';return}
    await trackKnowledgeWrite({identity:t.identity,source:value.datasourceId,docId:value.id,version:value.version,kind,started:Date.now()},()=>write(value))
    if(!current(t))return
    await refreshDocument()
  }catch(cause){if(cause!=='cancel' && cause!=='close' && current(t))documentError(cause,heading+'未完成')}
  finally{if(current(t))actionLoading.value=false}
}
async function submit(){return mutateDocument('submit',()=>mayManage.value && status.value==='DRAFT','提交的是已保存版本。确认提交审核？','提交审核',d=>submitReview(d.id))}
async function approve(){return mutateDocument('approve',()=>mayApprove.value && status.value==='PENDING_REVIEW','确认通过已保存修订的审核？文档发布与RAG构建仍独立。','审核通过',d=>approveDoc(d.id))}
async function reject(){return mutateDocument('reject',()=>mayApprove.value && status.value==='PENDING_REVIEW','请说明驳回原因，意见会保留以便作者修订。','审核驳回',d=>rejectDoc(d.id,reviewComment.value),true)}
async function publish(){return mutateDocument('publish',()=>mayPublish.value && status.value==='APPROVED','确认发布已审核文档？不会自动构建或切换RAG。','发布文档',d=>publishDoc(d.id))}

/* ---------------- AI 生成草稿 ---------------- */

const generateVisible = ref(false)
const generateLoading = ref(false)
const generationReady = ref(false)
const semanticAssistant = ref<InstanceType<typeof KnowledgeSemanticAssistant>>()
const generateSnapshotId = ref<number>()
const unknownGroup=computed(()=>pendingKnowledgeWrites.value.find(x=>x.identity===auth.token && x.source===doc.value?.datasourceId && x.snapshotId===generateSnapshotId.value && x.kind==='generate-group'))

function openGenerate() {
  if (!mayGenerate.value) { ElMessage.warning(GENERATE_HINT); return }
  generateSnapshotId.value = context.snapshotId || snapshots.value[0]?.id
  generateVisible.value = true
}

async function runGenerate(){
  if(!mayGenerate.value || !doc.value || !generateSnapshotId.value || generateLoading.value || saving.value || actionLoading.value || unknownWrite.value || unknownGroup.value)return
  if(dirty.value){ElMessage.warning('请先保存当前编辑内容，再生成独立新草稿');return}
  const t=ticket(),value={...doc.value},snapshot=generateSnapshotId.value,stamp=formStamp.value
  generateLoading.value=true;actionError.value=''
  try{
    await trackKnowledgeWrite<unknown>({identity:t.identity,source:value.datasourceId,docId:value.content?.trim()?undefined:value.id,snapshotId:snapshot,kind:value.content?.trim()?'generate-group':'generate',started:Date.now()},()=>value.content?.trim() ? generateFromSnapshot(value.datasourceId,snapshot) : generateDraft(value.id,snapshot))
    if(!current(t) || !generateVisible.value || generateSnapshotId.value!==snapshot)return
    if(value.content?.trim()) {ElMessage.success('已生成独立主题草稿，原文保留');if(formStamp.value===stamp){generateVisible.value=false;await router.push({path:'/admin/semantics/knowledge',query:{datasourceId:value.datasourceId}})}}
    else {generateVisible.value=false;await refreshDocument()}
  }catch(cause){if(current(t))documentError(cause,'目录生成未完成')}
  finally{if(current(t))generateLoading.value=false}
}

/* ---------------- 版本与差异 ---------------- */

const versionPreviewVisible = ref(false)
const previewVersion = ref<KnowledgeVersionItem | null>(null)

function showVersion(item: KnowledgeVersionItem) {
  previewVersion.value = item
  versionPreviewVisible.value = true
}

async function rollback(item:KnowledgeVersionItem){
  if(item.reviewStatus!=='APPROVED' || !versions.value.some(x=>x.versionNo===item.versionNo))return
  return mutateDocument('rollback',()=>mayPublish.value && status.value==='PUBLISHED',`以版本v${item.versionNo}创建新的ROLLBACK草稿，需重新审核发布；不切换活动RAG。`,'版本回滚',d=>rollbackVersion(d.id,item.versionNo))
}

const diffVisible = ref(false)
const diffLoading = ref(false)
const diffLines = ref<KnowledgeDiffLine[]>([])
const diffV1 = ref<number>()
const diffV2 = ref<number>()
const diffError = ref('')

async function openDiff() {
  const t=ticket()
  diffVisible.value = true
  diffError.value = ''
  diffLines.value = []
  if (!versions.value.length) await loadVersions()
  if(!current(t) || !diffVisible.value)return
  diffV1.value = versions.value.length > 1 ? versions.value[1].versionNo : undefined
  diffV2.value = versions.value[0]?.versionNo
}

async function runDiff() {
  if(!canReadDocument()) return
  const accept=readTicket('runDiff')

  if(diffLoading.value)return
  if (!diffV1.value || !diffV2.value) {
    ElMessage.warning('请选择两个版本')
    return
  }
  if (diffV1.value === diffV2.value) {
    ElMessage.warning('两个版本号相同，无法比较')
    return
  }
  diffLoading.value = true
  diffError.value = ''
  try {
    const loaded = (await diffVersions(docId.value, diffV1.value, diffV2.value)).data || []
    if(!accept())return
    diffLines.value = loaded
  } catch (cause) {
    if(!accept())return
    diffLines.value = []
    diffError.value = apiError(cause, '版本差异加载失败')
  } finally {
    if(!accept())return
    diffLoading.value = false
  }
}

/**
 * 版本流转轨迹。
 *
 * `knowledge_doc_version.review_status` 自 2026-09-12（V51）起已由 approve/reject 真实写入，
 * 因此「版本」Tab 可以直接按版本展示审核状态。本时间线仍只呈现流转信息
 * （生成来源 / 变更摘要 / 操作人 / 时间），审核结论与审核意见由审核记录表承载。
 */
const versionTimeline = computed(() =>
  versions.value.map((item) => ({
    versionNo: item.versionNo,
    reviewStatus: item.reviewStatus,
    generationSource: item.generationSource,
    changeSummary: item.changeSummary,
    createdAt: item.createdAt,
    createdBy: item.createdBy,
  })),
)

/** 来源快照：后端按版本返回，含快照版本号/状态/规模（2026-09-12 新增该接口） */
const sourceSnapshots = ref<KnowledgeSourceSnapshot[]>([])
const sourceSnapshotsLoading = ref(false)
const sourceSnapshotsError = ref('')

async function loadSourceSnapshots() {
  if(!canReadDocument()) return
  const accept=readTicket('loadSourceSnapshots')

  sourceSnapshotsLoading.value = true
  sourceSnapshotsError.value = ''
  try {
    const loaded = (await listSourceSnapshots(docId.value)).data || []
    if(!accept())return
    sourceSnapshots.value = loaded
  } catch (cause) {
    if(!accept())return
    sourceSnapshots.value = []
    sourceSnapshotsError.value = cause instanceof Error ? cause.message : '来源快照加载失败'
  } finally {
    if(!accept())return
    sourceSnapshotsLoading.value = false
  }
}

/** chunk 字段名来自 Python 切分结果，展示时优先取已知字段，其余作为附加信息 */
function chunkText(chunk: KnowledgeChunkPreview) {
  return chunk.chunk_text || chunk.content || ''
}
function chunkTitle(chunk: KnowledgeChunkPreview, index: number) {
  const type = chunk.chunk_type || chunk.chunkType || 'CHUNK'
  const order = chunk.chunk_index ?? index
  return `${type} · #${order}`
}
function chunkExtras(chunk: KnowledgeChunkPreview) {
  return Object.entries(chunk).filter(([key]) => !['chunk_text', 'content', 'chunk_type', 'chunkType', 'chunk_index'].includes(key))
}

/** 加载审核记录（后端 2026-09-12 补齐 `GET /{id}/review-tasks` 后才有数据来源） */
async function loadReviewRecords() {
  if(!canReadDocument()) return
  const accept=readTicket('loadReviewRecords')

  reviewLoading.value = true
  reviewError.value = ''
  try {
    const loaded = (await listReviewTasks(docId.value)).data || []
    if(!accept())return
    reviewRecords.value = loaded
  } catch (cause) {
    if(!accept())return
    reviewRecords.value = []
    reviewError.value = cause instanceof Error ? cause.message : '审核记录加载失败'
  } finally {
    if(!accept())return
    reviewLoading.value = false
  }
}

/** 加载向量化任务（后端 2026-09-12 补齐 `GET /{id}/vector-tasks` 后才有数据来源） */
async function loadVectorTasks() {
  if(!canReadDocument()) return
  const accept=readTicket('loadVectorTasks')

  tasksLoading.value = true
  taskError.value = ''
  try {
    const loaded = (await listVectorTasks(docId.value)).data || []
    if(!accept())return
    vectorTasks.value = loaded
  } catch (cause) {
    if(!accept())return
    vectorTasks.value = []
    taskError.value = cause instanceof Error ? cause.message : '索引任务加载失败'
  } finally {
    if(!accept())return
    tasksLoading.value = false
  }
}

async function loadRagBuilds() {
  if(!canReadDocument()) return
  const accept=readTicket('loadRagBuilds')

  const datasourceId = doc.value?.datasourceId
  ragBuildsError.value = ''
  if (!datasourceId) {
    ragBuilds.value = []
    return
  }
  ragBuildsLoading.value = true
  try {
    const loaded = (await listRagBuilds(datasourceId)).data || []
    if(!accept())return
    ragBuilds.value = loaded
  } catch (cause) {
    if(!accept())return
    ragBuilds.value = []
    ragBuildsError.value = apiError(cause, 'RAG 构建状态加载失败')
  } finally {
    if(!accept())return
    ragBuildsLoading.value = false
  }
}

watch(()=>doc.value?.version,(next,previous)=>{
  if(previous==null || next===previous)return
  for(const key of ['loadVersions','loadSourceSnapshots','loadReviewRecords','loadVectorTasks','loadChunks','runDiff','sourceStatus'])sequences[key]=(sequences[key] || 0)+1
  versions.value=[];chunks.value=[];sourceSnapshots.value=[];reviewRecords.value=[];vectorTasks.value=[];diffLines.value=[]
  versionsLoading.value=false;chunksLoading.value=false;sourceSnapshotsLoading.value=false;reviewLoading.value=false;tasksLoading.value=false;diffLoading.value=false;ragBuildsLoading.value=false
},{flush:'sync'})
watch(()=>[diffVisible.value,diffV1.value,diffV2.value],()=>{sequences.runDiff=(sequences.runDiff || 0)+1;diffLoading.value=false;diffLines.value=[]},{flush:'sync'})
useAdminScrollLock(()=>generateVisible.value || versionPreviewVisible.value || diffVisible.value || confirmVisible.value)
async function loadPage(){await loadDoc();if(disposed || !doc.value || !canReadDocument())return;await Promise.all([loadRagBuilds(),loadSnapshots(),loadVersions()]);if(activeTab.value==='source')void loadSourceSnapshots();if(activeTab.value==='review')void loadReviewRecords();if(activeTab.value==='chunks')void loadChunks();if(activeTab.value==='index')void loadVectorTasks()}
function resetPage(){
  epoch++;invalidateConfirm();expectedOwnSave.value=undefined;doc.value=null;title.value='';content.value='';savedStamp.value=formStamp.value;formBaseVersion.value=0;error.value='';actionError.value='';reviewComment.value=''
  versions.value=[];chunks.value=[];reviewRecords.value=[];vectorTasks.value=[];ragBuilds.value=[];sourceSnapshots.value=[];snapshots.value=[];sourceStatus.value=undefined;sourceStatusError.value=''
  generateVisible.value=false;diffVisible.value=false;versionPreviewVisible.value=false;saving.value=false;actionLoading.value=false;generateLoading.value=false
  loading.value=true;versionsLoading.value=false;chunksLoading.value=false;reviewLoading.value=false;tasksLoading.value=false;ragBuildsLoading.value=false;sourceSnapshotsLoading.value=false;diffLoading.value=false;diffLines.value=[];versionsError.value='';snapshotError.value='';ragBuildsError.value='';sourceSnapshotsError.value='';chunkError.value='';reviewError.value='';taskError.value='';diffError.value=''
  if(initialized)void loadPage()
}
onMounted(async()=>{await iamS1.load();if(disposed)return;datasources.value=(iamS1.datasourceCapabilities || []).map(x=>({id:x.datasourceId,name:x.datasourceName}));initialized=true;resetPage()})
watch(()=>[docId.value,route.query.datasourceId,auth.token,iamS1.snapshot,iamS1.loaded,iamS1.errorMessage],resetPage,{flush:'sync'})
onBeforeUnmount(()=>{disposed=true;epoch++;invalidateConfirm()})

watch(() => route.query.tab, (value) => {
  const next = String(value || 'content')
  if (next === activeTab.value) return
  activeTab.value = next
  if (next === 'versions' && !versions.value.length) loadVersions()
  if (next === 'source' && !sourceSnapshots.value.length) loadSourceSnapshots()
  if (next === 'review' && !reviewRecords.value.length) loadReviewRecords()
  // 切分预览与索引状态已拆为两个 Tab（开发指导 §7.11），各自独立加载
  if (next === 'chunks' && !chunks.value.length) loadChunks()
  if (next === 'index') { loadRagBuilds(); loadVectorTasks() }
})
</script>

<template>
  <div class="admin-page knowledge-doc-page">
    <p class="knowledge-doc-page__eyebrow">语义中心 / 可信业务知识</p>
    <ObjectContextSummary
      :title="doc?.title || '知识文档'"
      :description="`数据源：${datasourceName}`"
      back-to="/admin/semantics/knowledge"
      source-label="语义中心 / 语义知识"
    />

    <ErrorState v-if="error" :message="error" @retry="loadDoc" />
    <LoadingState v-else-if="loading" variant="skeleton" :rows="6" />

    <template v-else-if="doc">
      <TaskPageHeader
        eyebrow="知识文档"
        :title="doc.title"
        description="编辑当前知识主题，审核后发布；同一快照的多篇文档共同覆盖业务知识。满足来源与覆盖要求后，在知识列表显式确认 RAG 构建。"
      >
        <template #status>
          <BusinessStatusBadge :status="doc.status" :label="knowledgeStatusLabel(doc.status)" />
          <span class="doc-version">v{{ doc.currentVersion }}</span>
        </template>
        <template #actions>
          <el-button :icon="RefreshCw" :loading="loading" @click="refreshDocument">刷新</el-button>
          <el-button v-if="canEdit && mayManage" :icon="Save" :loading="saving" :disabled="versionConflict || actionLoading || !!unknownWrite" @click="save">
            {{ status === 'DRAFT' ? '保存' : '保存并新建版本' }}
          </el-button>
          <el-button v-if="status === 'DRAFT' && mayManage" :icon="Sparkles" :disabled="!mayGenerate || dirty || saving || actionLoading || !!unknownWrite" :title="mayGenerate ? '' : GENERATE_HINT" @click="openGenerate">按快照生成目录</el-button>
          <el-button v-if="status === 'DRAFT' && mayManage" :icon="Sparkles" :disabled="dirty || saving || actionLoading || !!unknownWrite" title="请先保存当前编辑内容，再补写业务释义" @click="semanticAssistant?.open()">AI 辅助补写</el-button>

          <el-button v-if="canSubmitReview && mayManage" type="primary" :icon="Send" :loading="actionLoading" :disabled="dirty || versionConflict || !!unknownWrite || saving" @click="submit">
            提交审核
          </el-button>
          <template v-else-if="canReview">
            <el-button type="primary" :loading="actionLoading" :disabled="!mayApprove || dirty || saving || !!unknownWrite" :title="mayApprove ? '' : APPROVE_HINT" @click="approve">审核通过</el-button>
            <el-button type="danger" plain :loading="actionLoading" :disabled="!mayApprove || dirty || saving || !!unknownWrite" :title="mayApprove ? '' : APPROVE_HINT" @click="reject">驳回</el-button>
          </template>
          <el-button v-else-if="canPublish" type="primary" :icon="Upload" :loading="actionLoading" :disabled="!mayPublish || dirty || saving || !!unknownWrite" :title="mayPublish ? '' : PUBLISH_HINT" @click="publish">
            发布文档
          </el-button>
          <el-button v-else-if="isPublished && mayPublish" :icon="RefreshCw" @click="router.push({ name: 'admin-semantic-knowledge', query: { datasourceId: String(doc.datasourceId) } })">
            RAG build 状态
          </el-button>
          <el-button v-else-if="isPublished && iamS1.queryUse" type="primary" :icon="MessageSquareText" @click="goAsk">
            进入智能问数
          </el-button>
        </template>
      </TaskPageHeader>

      <section v-if="actionError || unknownWrite" role="alert" class="knowledge-write-notice"><strong>{{ actionError || '此文档有执行中或结果待确认的操作' }}</strong><p v-if="unknownWrite">原文档 #{{ unknownWrite.docId }} · {{ unknownWrite.kind }} · 修订锁 {{ unknownWrite.version }}。请只读核对原文档/版本，未确认前不重发；已有旧build不能证明本次成功。</p><p v-if="reviewComment">保留的审核意见：{{ reviewComment }}</p><el-button @click="refreshDocument">重读当前文档与版本</el-button></section>
      <div class="document-draft-state"><span>{{ dirty ? '有未保存输入，提交/发布前请先保存' : '当前输入与已读保存版本一致' }}</span><span v-if="versionConflict" role="alert">已保存版本已变化；保留当前输入，请核对后重读。</span><el-button link :disabled="saving || actionLoading" @click="reloadSavedDraft">重读已保存版本</el-button></div>
      <section class="knowledge-doc-page__lifecycle">
        <LifecycleStepper :steps="lifecycleSteps" />
        <p class="knowledge-doc-page__hint">{{ nextStepHint }}</p>
      </section>

      <ErrorState v-if="sourceStatusError" :message="sourceStatusError" @retry="loadSourceStatus" />
      <el-alert v-else-if="sourceStatus && !sourceStatus.valid" :title="sourceStatus.reasons.join(' ')" type="warning" :closable="false" show-icon />
      <el-alert v-else-if="sourceStatus?.valid" title="已保存版本的结构与术语来源校验通过；文档发布和 RAG 构建仍需分别确认。" type="success" :closable="false" />
      <el-tabs :model-value="activeTab" @update:model-value="selectTab">
        <!-- 内容 -->
        <el-tab-pane label="内容" name="content">
          <section class="knowledge-doc-page__editor">
            <div class="pane">
              <div class="pane__title">{{ mayManage && canEdit ? '编辑' : '只读' }}</div>
              <el-input
                v-model="title"
                placeholder="文档标题"
                :disabled="!canEdit"
                :readonly="!mayManage"
                aria-label="文档标题" class="pane__title-input"
              />
              <el-input
                v-model="content"
                type="textarea"
                :rows="22"
                :disabled="!canEdit"
                :readonly="!mayManage"
                placeholder="在此编写 skills.md 内容（Markdown 格式）"
                resize="vertical"
                aria-label="知识文档正文" class="pane__textarea"
              />
            </div>
            <details class="pane pane-preview"><summary>查看当前输入预览</summary><pre class="pane__preview">{{ content || '（暂无内容）' }}</pre></details>
          </section>
          <p v-if="!mayManage" class="knowledge-doc-page__locked">当前账号没有维护该文档的能力，内容仅供查看。</p>
          <p v-else-if="!canEdit" class="knowledge-doc-page__locked">
            当前状态（{{ knowledgeStatusLabel(doc.status) }}）不允许直接编辑。
            <template v-if="isIndexing">索引完成后才可以继续修改。</template>
            <template v-else-if="doc.status === 'DEPRECATED'">已废弃文档保持只读。</template>
          </p>
        </el-tab-pane>

        <!-- 来源与覆盖 -->
        <el-tab-pane label="来源与覆盖" name="source">
          <section class="knowledge-doc-page__card">
            <dl class="facts">
              <div><dt>绑定数据源</dt><dd>{{ datasourceName }}</dd></div>
              <div><dt>当前版本</dt><dd>v{{ doc.currentVersion }}</dd></div>
              <div><dt>审核状态</dt><dd>{{ knowledgeReviewStatusLabel(doc.reviewStatus) }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ doc.createdAt || '—' }}</dd></div>
              <div><dt>最近更新</dt><dd>{{ doc.updatedAt || '—' }}</dd></div>
            </dl>
            <h3 class="section-title">覆盖表（{{ coveredTables.length }}）</h3>
            <p v-if="!coveredTables.length" class="muted">
              该文档没有记录覆盖表。手工创建的文档不会自动带上来源范围，可以从快照生成草稿来获得覆盖信息。
            </p>
            <div v-else class="table-tags">
              <el-tag v-for="table in coveredTables" :key="table" type="info">{{ table }}</el-tag>
            </div>
            <h3 class="section-title">来源快照</h3>
            <ErrorState v-if="sourceSnapshotsError" :message="sourceSnapshotsError" @retry="loadSourceSnapshots" />
            <LoadingState v-else-if="sourceSnapshotsLoading" text="正在读取来源快照…" />
            <EmptyState
              v-else-if="!sourceSnapshots.length"
              message="版本记录里没有关联的元数据快照。手工创建的文档不会带来源快照，从快照生成的草稿会带上。"
            />
            <el-table v-else :data="sourceSnapshots" size="small" stripe>
              <el-table-column label="文档版本" width="100">
                <template #default="{ row }">v{{ row.versionNo }}</template>
              </el-table-column>
              <el-table-column label="来源快照" width="110">
                <template #default="{ row }">
                  <RouterLink v-if="canReadSourceSnapshot" class="snapshot-link" :to="{path:'/admin/releases/snapshots/' + row.snapshotId,query:{datasourceId:String(doc?.datasourceId)}}">
                    {{ row.snapshotVersion ? 'v' + row.snapshotVersion : '#' + row.snapshotId }}
                  </RouterLink>
                  <span v-else>#{{ row.snapshotId }} · 无结构查看权</span>
                </template>
              </el-table-column>
              <el-table-column label="快照状态" width="120">
                <template #default="{ row }">
                  <BusinessStatusBadge v-if="row.status" :status="row.status" :label="snapshotStatusLabel(row.status)" />
                  <span v-else class="muted">快照已不存在</span>
                </template>
              </el-table-column>
              <el-table-column label="规模" min-width="150">
                <template #default="{ row }">
                  <span v-if="row.tableCount !== undefined && row.tableCount !== null">
                    {{ row.tableCount }} 表 / {{ row.columnCount }} 字段
                  </span>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
            </el-table>
            <p class="muted">同一个文档的不同版本可能来自不同快照；快照已不存在时只保留其 ID。</p>
          </section>
        </el-tab-pane>

        <!-- 审核记录 -->
        <el-tab-pane label="审核记录" name="review">
          <section class="knowledge-doc-page__card">
            <dl class="facts">
              <div><dt>当前审核状态</dt>
                <dd>
                  <el-tag :type="knowledgeReviewStatusType(doc.reviewStatus)" size="small">
                    {{ knowledgeReviewStatusLabel(doc.reviewStatus) }}
                  </el-tag>
                </dd>
              </div>
              <div><dt>文档状态</dt><dd>{{ knowledgeStatusLabel(doc.status) }}（{{ doc.status }}）</dd></div>
            </dl>
            <div class="card-heading">
              <div>
                <h3>审核意见</h3>
                <p>来自审核任务表，含审核人、审核结果与审核意见。驳回后依据这里的原因修改内容。</p>
              </div>
            </div>
            <ErrorState v-if="reviewError" :message="reviewError" @retry="loadReviewRecords" />
            <LoadingState v-else-if="reviewLoading" text="正在读取审核记录…" />
            <EmptyState
              v-else-if="!reviewRecords.length"
              message="还没有审核记录。文档提交审核并被批准或驳回后，这里会显示审核人、审核时间与审核意见。"
            />
            <el-table v-else :data="reviewRecords" stripe size="small">
              <el-table-column label="版本" width="90">
                <template #default="{ row }">{{ row.versionNo ? 'v' + row.versionNo : '—' }}</template>
              </el-table-column>
              <el-table-column label="审核结果" width="110">
                <template #default="{ row }">
                  <el-tag :type="knowledgeReviewStatusType(row.reviewStatus)" size="small">
                    {{ knowledgeReviewStatusLabel(row.reviewStatus) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="reviewerName" label="审核人" width="120">
                <template #default="{ row }">{{ row.reviewerName || (row.reviewerId ? '用户 #' + row.reviewerId : '—') }}</template>
              </el-table-column>
              <el-table-column prop="reviewComment" label="审核意见" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ row.reviewComment || '（未填写）' }}</template>
              </el-table-column>
              <el-table-column prop="reviewedAt" label="审核时间" width="175">
                <template #default="{ row }">{{ row.reviewedAt || row.submittedAt || '—' }}</template>
              </el-table-column>
            </el-table>

            <div class="card-heading">
              <div>
                <h3>版本流转</h3>
                <p>按文档版本查看生成来源、修订摘要和审核状态；审核意见在上表保留。</p>
              </div>
            </div>
            <ErrorState v-if="versionsError" :message="versionsError" @retry="loadVersions" />
            <LoadingState v-else-if="versionsLoading" text="正在读取版本流转…" />
            <EmptyState v-else-if="!versionTimeline.length" message="暂无版本记录。保存或生成草稿后会出现流转轨迹。" />
            <el-table v-else :data="versionTimeline" stripe size="small">
              <el-table-column label="版本" width="90"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
              <el-table-column label="审核状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="knowledgeReviewStatusType(row.reviewStatus)" size="small">
                    {{ knowledgeReviewStatusLabel(row.reviewStatus) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="生成来源" width="120">
                <template #default="{ row }">
                  <el-tag :type="generationSourceType(row.generationSource)" size="small">
                    {{ generationSourceLabel(row.generationSource) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="changeSummary" label="变更摘要" min-width="200" show-overflow-tooltip />
              <el-table-column prop="createdBy" label="操作人" width="100">
                <template #default="{ row }">{{ row.createdBy ? '用户 #' + row.createdBy : '—' }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" label="时间" width="175" />
            </el-table>
          </section>
        </el-tab-pane>

        <!-- 版本 -->
        <el-tab-pane label="版本" name="versions">
          <section class="knowledge-doc-page__card">
            <div class="card-heading">
              <div>
                <h3>版本历史</h3>
                <p>
                  回滚仅对已发布文档开放，目标版本须已审核通过；操作会以该版本内容创建新的 ROLLBACK 草稿，历史版本保留。
                  该草稿仍需重新提交审核、发布，并在知识列表显式确认 RAG 构建；创建草稿时当前活动索引保持不变。
                </p>
              </div>
              <div class="card-heading__actions">
                <el-button :icon="GitCompareArrows" @click="openDiff">版本差异</el-button>
                <el-button :icon="RefreshCw" :loading="versionsLoading" @click="loadVersions">刷新</el-button>
              </div>
            </div>
            <LoadingState v-if="versionsLoading" variant="skeleton" :rows="4" />
            <ErrorState v-else-if="versionsError" :message="versionsError" @retry="loadVersions" />
            <EmptyState v-else-if="!versions.length" message="暂无版本记录。保存或生成草稿后会产生版本。" />
            <el-table v-else :data="versions" stripe>
              <el-table-column label="版本" width="90"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
              <el-table-column label="生成来源" width="120">
                <template #default="{ row }">
                  <el-tag :type="generationSourceType(row.generationSource)" size="small">
                    {{ generationSourceLabel(row.generationSource) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="来源快照" width="110">
                <template #default="{ row }">
                  <RouterLink v-if="row.metadataSnapshotId && canReadSourceSnapshot" class="snapshot-link" :to="{path:'/admin/releases/snapshots/' + row.metadataSnapshotId,query:{datasourceId:String(doc?.datasourceId)}}">
                    #{{ row.metadataSnapshotId }}
                  </RouterLink>
                  <span v-else-if="row.metadataSnapshotId" class="muted">#{{ row.metadataSnapshotId }} · 无结构查看权</span>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column prop="changeSummary" label="变更摘要" min-width="200" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="创建时间" width="175" />
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="showVersion(row)">查看</el-button>
                  <el-button
                    v-if="canRollback && mayPublish && row.versionNo !== doc.currentVersion"
                    link
                    type="warning"
                    :loading="actionLoading"
                    @click="rollback(row)"
                  >
                    <RotateCcw :size="14" />回滚
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </el-tab-pane>

        <!-- 切分与索引 -->
        <el-tab-pane label="切分预览" name="chunks">
          <section class="knowledge-doc-page__card">
            <div class="card-heading">
              <div>
                <h3>切分预览</h3>
                <p>模拟发布时的切片逻辑，用于发布前确认 RAG 会看到哪些内容。</p>
              </div>
              <el-button :icon="Scissors" :loading="chunksLoading" @click="loadChunks">重新预览</el-button>
            </div>
            <ErrorState v-if="chunkError" :message="chunkError" @retry="loadChunks" />
            <LoadingState v-else-if="chunksLoading" variant="skeleton" :rows="4" />
            <EmptyState
              v-else-if="!chunks.length"
              message="还没有切分结果。点击「重新预览」按当前内容模拟一次切片。"
              action-text="开始预览"
              @action="loadChunks"
            />
            <ul v-else class="chunk-list">
              <li v-for="(chunk, index) in chunks" :key="index">
                <header>
                  <strong>{{ chunkTitle(chunk, index) }}</strong>
                  <span v-if="chunk.related_table" class="muted">关联表：{{ chunk.related_table }}</span>
                </header>
                <pre>{{ chunkText(chunk) }}</pre>
                <small v-if="chunkExtras(chunk).length" class="muted">
                  {{ chunkExtras(chunk).map(([key, value]) => `${key}=${value}`).join(' · ') }}
                </small>
              </li>
            </ul>
          </section>
        </el-tab-pane>

        <el-tab-pane label="索引状态" name="index">
          <section class="knowledge-doc-page__card">
            <div class="card-heading">
              <div>
                <h3>数据源级 RAG build</h3>
                <p>文档发布不会自动写入或切换向量。只有经过授权人员明确确认并通过核验的 build 才会成为活动索引。</p>
              </div>
              <el-button v-if="mayPublish" type="primary" :icon="RefreshCw" @click="router.push({ name: 'admin-semantic-knowledge', query: { datasourceId: String(doc.datasourceId) } })">
                管理 RAG build
              </el-button>
            </div>
            <dl class="facts">
              <div><dt>文档状态</dt><dd>{{ knowledgeStatusLabel(doc.status) }}</dd></div>
              <div><dt>当前版本</dt><dd>v{{ doc.currentVersion }}</dd></div>
              <div><dt>活动 RAG build</dt><dd>{{ ragBuilds.find((item) => item.status === 'ACTIVE')?.buildId || '尚无活动 build' }}</dd></div>
              <div><dt>RAG 来源快照</dt><dd>{{ ragBuilds.find((item) => item.status === 'ACTIVE')?.sourceSnapshotId || '—' }}</dd></div>
            </dl>
            <ErrorState v-if="ragBuildsError" :message="ragBuildsError" @retry="loadRagBuilds" />
            <LoadingState v-else-if="ragBuildsLoading" text="正在读取 RAG build…" />
            <el-table v-else-if="ragBuilds.length" :data="ragBuilds" stripe size="small">
              <el-table-column prop="buildId" label="buildId" min-width="250" />
              <el-table-column prop="sourceSnapshotId" label="来源快照" width="110" />
              <el-table-column prop="embeddingModel" label="Embedding" min-width="160" />
              <el-table-column prop="status" label="状态" width="170" />
              <el-table-column label="向量数" width="130"><template #default="{ row }">{{ row.actualVectorCount }} / {{ row.expectedChunkCount }}</template></el-table-column>
              <el-table-column prop="errorMessage" label="错误信息" min-width="220" show-overflow-tooltip />
            </el-table>
            <EmptyState v-else message="该数据源尚无 RAG build。前往语义知识页查看快照版本并明确确认构建。" />
            <p class="muted">旧版文档级 vector_index_task 仅保留历史记录，不会自动启动或切换活动 RAG。</p>
            <ErrorState v-if="taskError" :message="taskError" @retry="loadVectorTasks" />
            <LoadingState v-else-if="tasksLoading" text="正在读取索引任务…" />
            <EmptyState
              v-else-if="!vectorTasks.length"
              message="没有旧版单文档向量任务。当前 build 状态见上方数据源级记录。"
            />
            <el-table v-else :data="vectorTasks" stripe size="small">
              <el-table-column label="任务" width="80">
                <template #default="{ row }">#{{ row.id }}</template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag :type="vectorTaskStatusType(row.status)" size="small">{{ vectorTaskStatusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="版本" width="90">
                <template #default="{ row }">{{ row.knowledgeVersionNo ? 'v' + row.knowledgeVersionNo : '—' }}</template>
              </el-table-column>
              <el-table-column label="开始" width="170">
                <template #default="{ row }">{{ row.startedAt || '—' }}</template>
              </el-table-column>
              <el-table-column label="结束" width="170">
                <template #default="{ row }">{{ row.finishedAt || '—' }}</template>
              </el-table-column>
              <el-table-column prop="errorMessage" label="失败原因" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">{{ row.errorMessage || '—' }}</template>
              </el-table-column>
            </el-table>
          </section>
        </el-tab-pane>
      </el-tabs>
    </template>

    <KnowledgeSemanticAssistant v-if="doc" ref="semanticAssistant" :doc="doc" :disabled="dirty || saving || actionLoading" @saved="refreshDocument" />
    <!-- 快照生成 -->
    <el-dialog v-model="generateVisible" :show-close="!generateLoading" :close-on-press-escape="!generateLoading" :close-on-click-modal="!generateLoading" title="生成快照字段目录" width="480px">
      <p v-if="unknownGroup" role="alert" class="knowledge-write-notice">同源同快照的整组目录生成执行中或结果未知；列表与详情共用记录，请核对文档列表，不重复生成。</p>
      <p class="muted">
        选择一个元数据快照，系统会按该快照的稳定表/字段 ID 生成完整目录。缺少可靠业务释义时标为“待确认”；结果会写入草稿新版本。
      </p>
      <ErrorState v-if="snapshotError" :message="snapshotError" @retry="loadSnapshots" />
      <EmptyState
        v-else-if="!snapshots.length"
        message="该数据源没有可用的元数据快照。请先完成采集并发布快照，再生成知识草稿。"
      />
      <el-select v-else v-model="generateSnapshotId" placeholder="选择快照" style="width: 100%">
        <el-option
          v-for="item in snapshots"
          :key="item.id"
          :value="item.id"
          :label="`v${item.snapshotVersion} · ${item.status}`"
        />
      </el-select>
      <KnowledgeSourcePreview :datasource-id="doc?.datasourceId" :snapshot-id="generateSnapshotId" @ready="generationReady = $event" />
      <template #footer>
        <el-button :disabled="generateLoading" @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generateLoading" :disabled="!generationReady || !!unknownWrite || !!unknownGroup" @click="runGenerate">生成目录</el-button>
      </template>
    </el-dialog>

    <!-- 版本内容预览 -->
    <el-dialog v-model="versionPreviewVisible" :title="`版本 ${previewVersion?.versionNo} 内容`" width="760px">
      <pre class="dialog-pre">{{ previewVersion?.content || '（空内容）' }}</pre>
    </el-dialog>

    <!-- 版本差异 -->
    <el-dialog v-model="diffVisible" title="版本差异" width="820px">
      <div class="diff-toolbar">
        <el-select v-model="diffV1" placeholder="旧版本" style="width: 150px">
          <el-option v-for="item in versions" :key="item.versionNo" :label="`v${item.versionNo}`" :value="item.versionNo" />
        </el-select>
        <GitCompareArrows :size="18" />
        <el-select v-model="diffV2" placeholder="新版本" style="width: 150px">
          <el-option v-for="item in versions" :key="item.versionNo" :label="`v${item.versionNo}`" :value="item.versionNo" />
        </el-select>
        <el-button type="primary" :loading="diffLoading" @click="runDiff">比较</el-button>
      </div>
      <ErrorState v-if="diffError" :message="diffError" @retry="runDiff" />
      <LoadingState v-else-if="diffLoading" variant="skeleton" :rows="5" />
      <EmptyState v-else-if="!diffLines.length" message="选择两个不同版本后点击比较。" />
      <ul v-else class="diff-list">
        <li v-for="(line, index) in diffLines" :key="index" :class="`is-${line.type.toLowerCase()}`">
          <span class="diff-marker">{{ line.type === 'ADD' ? '+' : line.type === 'DELETE' ? '-' : ' ' }}</span>
          <code>{{ line.content || ' ' }}</code>
        </li>
      </ul>
    </el-dialog>
  </div>
</template>

<style scoped>
.knowledge-doc-page__eyebrow {margin:0;color:var(--do-primary-strong);font-size:12px;font-weight:650;}
.knowledge-write-notice {border:1px solid var(--do-line);border-left:3px solid var(--do-warning);padding:14px;line-height:1.6;font-size:13px;overflow-wrap:anywhere;}.knowledge-write-notice p {margin:6px 0;}
.document-draft-state {display:flex;align-items:center;flex-wrap:wrap;gap:8px 16px;color:var(--do-muted);font-size:12px;border-bottom:1px solid var(--do-line);padding-bottom:12px;}

.knowledge-doc-page { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.knowledge-doc-page > * { min-width: 0; }
.knowledge-doc-page :deep(.object-context-summary) { flex-wrap: wrap; }

.doc-version { color: var(--do-muted); font-size: 12px; }

.knowledge-doc-page__lifecycle {
  display: grid;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.knowledge-doc-page__hint { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.7; }

.knowledge-doc-page :deep(.task-page-header) { flex-wrap: wrap; }
.knowledge-doc-page :deep(.task-page-header__copy) { flex: 1 1 280px; }
.knowledge-doc-page :deep(.task-page-header__actions) { flex: 1 1 480px; min-width: 0; max-width: 100%; justify-content: flex-start; }

.knowledge-doc-page__card {
  display: grid;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.knowledge-doc-page__editor { display:grid;grid-template-columns:minmax(0,1fr);gap:12px; }.knowledge-doc-page__editor:has(.pane-preview[open]){grid-template-columns:repeat(2,minmax(0,1fr));}.pane-preview:not([open]){padding:8px 0;border:0;background:transparent;box-shadow:none;}.pane-preview summary{cursor:pointer;color:var(--do-primary-strong);font-size:13px;}

.pane {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}

.pane__title { color: var(--do-muted); font-size: 12px; font-weight: 800; text-transform: uppercase; }
.pane__textarea :deep(.el-textarea__inner) { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 13px; line-height: 1.6; }
.pane__preview {
  flex: 1;
  max-height: 560px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-radius: var(--do-radius-md);
  background: var(--do-bg);
  color: var(--do-ink);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.knowledge-doc-page__locked { margin: 12px 0 0; padding: 10px 12px; border-radius: var(--do-radius-md); background: var(--do-bg); color: var(--do-muted); font-size: 12px; }

.facts { display: grid; gap: 8px; margin: 0; }
.facts > div { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 10px; }
.facts dt { color: var(--do-muted); font-size: 12px; }
.facts dd { margin: 0; color: var(--do-ink); font-size: 13px; word-break: break-word; }

.section-title { margin: 6px 0 0; color: var(--do-ink); font-size: 13px; }
.muted { margin: 0; color: var(--do-muted); font-size: 12px; line-height: 1.7; }
.table-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.snapshot-link { color: var(--do-primary-strong); font-size: 12px; font-weight: 800; }

.card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.card-heading h3 { margin: 0; color: var(--do-ink); font-size: 14px; }
.card-heading p { margin: 4px 0 0; color: var(--do-muted); font-size: 12px; }
.card-heading__actions { display: flex; gap: 8px; }

.chunk-list { display: grid; gap: 12px; margin: 0; padding: 0; list-style: none; }
.chunk-list li { display: grid; gap: 8px; padding: 12px 14px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); }
.chunk-list header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.chunk-list strong { color: var(--do-ink); font-size: 12px; }
.chunk-list pre { max-height: 220px; margin: 0; overflow: auto; color: var(--do-ink); font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }

.dialog-pre { max-height: 520px; margin: 0; padding: 14px; overflow: auto; border-radius: var(--do-radius-md); background: var(--do-bg); color: var(--do-ink); font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }

.diff-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.diff-list { display: grid; gap: 2px; max-height: 460px; margin: 0; padding: 0; overflow: auto; list-style: none; font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
.diff-list li { display: grid; grid-template-columns: 18px minmax(0, 1fr); gap: 6px; padding: 2px 6px; border-radius: 4px; }
.diff-list li.is-add { background: var(--do-success-soft); }
.diff-list li.is-delete { background: var(--do-danger-soft); }
.diff-marker { color: var(--do-muted); text-align: center; }
.diff-list code { white-space: pre-wrap; word-break: break-word; }

@media (max-width: 1180px) {
  .knowledge-doc-page :deep(.task-page-header__actions){width:100%;max-width:100%;min-width:0;flex-wrap:wrap;justify-content:flex-start;gap:8px;}.knowledge-doc-page :deep(.task-page-header__actions .el-button){margin-left:0;}
  .knowledge-doc-page__editor,.knowledge-doc-page__editor:has(.pane-preview[open]) { grid-template-columns: minmax(0, 1fr); }
}
</style>
