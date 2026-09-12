<script setup lang="ts">
/**
 * 知识文档详情容器
 *
 * 合并旧 SkillsEditor.vue（编辑、AI 生成草稿、提交、发布）与 VersionList.vue（版本、回滚），
 * 并补齐切分预览与索引状态，使「草稿 → 待审核 → 已批准 → 索引中 → 已发布」各阶段不再混淆。
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
 * - rollback 自 2026-09-12 起要求文档为 PUBLISHED 且目标版本审核已通过，前端仍只对已发布文档开放该入口。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GitCompareArrows, MessageSquareText, RefreshCw, RotateCcw, Save, Scissors, Send, Sparkles, Upload } from 'lucide-vue-next'
import {
  approveDoc,
  diffVersions,
  getKnowledgeDoc,
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
  type KnowledgeChunkPreview,
  type KnowledgeDiffLine,
  type KnowledgeDocItem,
  type KnowledgeReviewRecord,
  type KnowledgeSourceSnapshot,
  type KnowledgeVersionItem,
  type VectorIndexTaskItem,
} from '../../../api/admin/knowledge'
import { listSimpleDatasources, type DatasourceSimpleItem } from '../../../api/admin/datasource'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import {
  generationSourceLabel,
  generationSourceType,
  knowledgeReviewStatusLabel,
  knowledgeReviewStatusType,
  knowledgeStatusLabel,
  vectorTaskStatusLabel,
  vectorTaskStatusType,
} from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import LifecycleStepper from '../../../components/admin/LifecycleStepper.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const context = useAdminContextStore()

const docId = computed(() => Number(route.params.id))
const activeTab = ref(String(route.query.tab || 'content'))

const doc = ref<KnowledgeDocItem | null>(null)
const versions = ref<KnowledgeVersionItem[]>([])
const datasources = ref<DatasourceSimpleItem[]>([])
const snapshots = ref<SnapshotItem[]>([])
const chunks = ref<KnowledgeChunkPreview[]>([])

/** 审核记录与向量化任务：2026-09-12 后端补齐查询接口后才有数据来源 */
const reviewRecords = ref<KnowledgeReviewRecord[]>([])
const reviewLoading = ref(false)
const reviewError = ref('')
const vectorTasks = ref<VectorIndexTaskItem[]>([])
const tasksLoading = ref(false)
const taskError = ref('')

const loading = ref(true)
const error = ref('')
const saving = ref(false)
const actionLoading = ref(false)
const versionsLoading = ref(false)
const chunksLoading = ref(false)
const chunkError = ref('')

const title = ref('')
const content = ref('')

/** 生命周期步骤：索引中与已发布是两件事，必须分开显示 */
const lifecycleSteps = computed(() => {
  const status = doc.value?.status || 'DRAFT'
  const order = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'INDEXING', 'PUBLISHED']
  const index = order.indexOf(status)
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    PENDING_REVIEW: '待审核',
    APPROVED: '已批准',
    INDEXING: '索引中',
    PUBLISHED: '已发布',
  }
  return order.map((key, position) => ({
    key,
    label: labels[key],
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
    return '文档正在构建索引。此阶段重复发布不会生效，请等待任务结束后刷新状态。'
  }
  if (status.value === 'APPROVED') {
    return '文档已批准但尚未进入检索。发布并构建索引成功后，内容才会被 RAG 检索使用。'
  }
  if (status.value === 'PENDING_REVIEW') {
    return '等待审核。审核通过后进入已批准，仍需发布才会构建索引。'
  }
  if (status.value === 'PUBLISHED') {
    return '文档已发布并可被检索。直接修改内容会创建新版本并回到草稿，需要重新审核与发布。'
  }
  if (status.value === 'DEPRECATED') {
    return '文档已废弃，不再参与检索，当前页面保持只读。'
  }
  return '草稿状态。完成编辑后提交审核，审核通过再发布并构建索引。'
})

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

function selectTab(tab: string) {
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } })
  if (tab === 'versions' && !versions.value.length) loadVersions()
  if (tab === 'chunks' && !chunks.value.length) loadChunks()
}

async function loadDoc() {
  loading.value = true
  error.value = ''
  try {
    const result = await getKnowledgeDoc(docId.value)
    doc.value = result.data || null
    title.value = doc.value?.title || ''
    content.value = doc.value?.content || ''
    if (doc.value) {
      context.selectDatasource(doc.value.datasourceId)
      context.selectKnowledgeDoc(doc.value.id)
    }
  } catch (cause) {
    doc.value = null
    error.value = apiError(cause, '文档加载失败')
  } finally {
    loading.value = false
  }
}

async function loadVersions() {
  versionsLoading.value = true
  try {
    versions.value = (await listVersions(docId.value)).data || []
  } catch (cause) {
    versions.value = []
    ElMessage.error(apiError(cause, '版本列表加载失败'))
  } finally {
    versionsLoading.value = false
  }
}

async function loadChunks() {
  chunksLoading.value = true
  chunkError.value = ''
  try {
    chunks.value = (await previewChunks(docId.value)).data || []
  } catch (cause) {
    chunks.value = []
    chunkError.value = apiError(cause, '切分预览失败，请确认 Python 服务可用')
  } finally {
    chunksLoading.value = false
  }
}

async function loadSnapshots() {
  if (!doc.value?.datasourceId) return
  try {
    snapshots.value = (await listSnapshots({ datasourceId: doc.value.datasourceId, page: 1, size: 50 })).data?.records || []
  } catch {
    snapshots.value = []
  }
}

async function save() {
  if (!doc.value) return
  if (!title.value.trim()) {
    ElMessage.warning('文档标题不能为空')
    return
  }
  // 后端只在内容真正变化时才递增版本并把状态重置为草稿，提示语必须与之保持一致
  const changed = content.value !== (doc.value.content || '') || title.value !== doc.value.title
  saving.value = true
  try {
    await updateKnowledgeDoc(doc.value.id, {
      title: title.value,
      content: content.value,
      version: doc.value.version,
      changeSummary: '手动编辑保存',
    })
    ElMessage.success(changed
      ? '已保存。内容已变更，后端创建了新版本并把文档状态重置为草稿。'
      : '已保存。内容没有变化，版本与状态保持不变。')
    await loadDoc()
  } catch (cause) {
    ElMessage.error(apiError(cause, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function submit() {
  try {
    await ElMessageBox.confirm('提交审核后文档将不可编辑，直到审核完成。确认提交？', '提交审核')
    actionLoading.value = true
    await submitReview(docId.value)
    ElMessage.success('已提交审核')
    await loadDoc()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '提交审核失败'))
  } finally {
    actionLoading.value = false
  }
}

async function approve() {
  try {
    await ElMessageBox.confirm('确认通过审核？通过后文档进入已批准，需要再执行发布才会构建索引。', '审核通过')
    actionLoading.value = true
    await approveDoc(docId.value)
    ElMessage.success('审核已通过，下一步是发布并构建索引')
    await loadDoc()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '审核通过失败'))
  } finally {
    actionLoading.value = false
  }
}

async function reject() {
  try {
    const result = await ElMessageBox.prompt('请说明驳回原因，作者会据此修改后重新提交。', '审核驳回', {
      inputValidator: (value) => Boolean(value?.trim()) || '驳回原因不能为空',
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
    })
    actionLoading.value = true
    await rejectDoc(docId.value, result.value)
    ElMessage.success('已驳回，文档回到草稿')
    await loadDoc()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '驳回失败'))
  } finally {
    actionLoading.value = false
  }
}

async function publish() {
  try {
    await ElMessageBox.confirm(
      '发布后文档将进入索引阶段，内容会被切分并写入向量库。索引成功前旧版本向量会保留。确认发布？',
      '发布并构建索引',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' },
    )
    actionLoading.value = true
    await publishDoc(docId.value)
    ElMessage.success('已提交发布，文档进入索引中')
    await loadDoc()
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '发布失败'))
  } finally {
    actionLoading.value = false
  }
}

/* ---------------- AI 生成草稿 ---------------- */

const generateVisible = ref(false)
const generateLoading = ref(false)
const generateSnapshotId = ref<number>()

function openGenerate() {
  generateSnapshotId.value = context.snapshotId || snapshots.value[0]?.id
  generateVisible.value = true
}

async function runGenerate() {
  if (!generateSnapshotId.value) {
    ElMessage.warning('请选择用于生成草稿的快照')
    return
  }
  generateLoading.value = true
  try {
    const result = await generateDraft(docId.value, generateSnapshotId.value)
    generateVisible.value = false
    // 后端生成草稿时会写入新版本并递增乐观锁 version，必须重新拉取后再覆盖展示内容
    await loadDoc()
    content.value = result.data?.content || content.value
    ElMessage.success('AI 草稿已生成，请核对后保存')
  } catch (cause) {
    ElMessage.error(apiError(cause, 'AI 生成失败'))
  } finally {
    generateLoading.value = false
  }
}

/* ---------------- 版本与差异 ---------------- */

const versionPreviewVisible = ref(false)
const previewVersion = ref<KnowledgeVersionItem | null>(null)

function showVersion(item: KnowledgeVersionItem) {
  previewVersion.value = item
  versionPreviewVisible.value = true
}

async function rollback(item: KnowledgeVersionItem) {
  try {
    await ElMessageBox.confirm(
      `回滚到版本 ${item.versionNo}？影响范围：后端会以该版本内容创建一个新的 ROLLBACK 版本，`
      + '把文档状态置为「索引中」，并立即触发向量化任务；此操作不经过审核。',
      '版本回滚',
      { type: 'warning', confirmButtonText: '确认回滚并重新索引', cancelButtonText: '取消' },
    )
    actionLoading.value = true
    await rollbackVersion(docId.value, item.versionNo)
    ElMessage.success('回滚已提交，文档进入索引中')
    await Promise.all([loadDoc(), loadVersions()])
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '回滚失败'))
  } finally {
    actionLoading.value = false
  }
}

const diffVisible = ref(false)
const diffLoading = ref(false)
const diffLines = ref<KnowledgeDiffLine[]>([])
const diffV1 = ref<number>()
const diffV2 = ref<number>()
const diffError = ref('')

async function openDiff() {
  diffVisible.value = true
  diffError.value = ''
  diffLines.value = []
  if (!versions.value.length) await loadVersions()
  diffV1.value = versions.value.length > 1 ? versions.value[1].versionNo : undefined
  diffV2.value = versions.value[0]?.versionNo
}

async function runDiff() {
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
    diffLines.value = (await diffVersions(docId.value, diffV1.value, diffV2.value)).data || []
  } catch (cause) {
    diffLines.value = []
    diffError.value = apiError(cause, '版本差异加载失败')
  } finally {
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
  sourceSnapshotsLoading.value = true
  sourceSnapshotsError.value = ''
  try {
    sourceSnapshots.value = (await listSourceSnapshots(docId.value)).data || []
  } catch (cause) {
    sourceSnapshots.value = []
    sourceSnapshotsError.value = cause instanceof Error ? cause.message : '来源快照加载失败'
  } finally {
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
  reviewLoading.value = true
  reviewError.value = ''
  try {
    reviewRecords.value = (await listReviewTasks(docId.value)).data || []
  } catch (cause) {
    reviewRecords.value = []
    reviewError.value = cause instanceof Error ? cause.message : '审核记录加载失败'
  } finally {
    reviewLoading.value = false
  }
}

/** 加载向量化任务（后端 2026-09-12 补齐 `GET /{id}/vector-tasks` 后才有数据来源） */
async function loadVectorTasks() {
  tasksLoading.value = true
  taskError.value = ''
  try {
    vectorTasks.value = (await listVectorTasks(docId.value)).data || []
  } catch (cause) {
    vectorTasks.value = []
    taskError.value = cause instanceof Error ? cause.message : '索引任务加载失败'
  } finally {
    tasksLoading.value = false
  }
}

onMounted(async () => {
  try {
    datasources.value = (await listSimpleDatasources()).data || []
  } catch {
    datasources.value = []
  }
  await loadDoc()
  await loadSnapshots()
  // 版本记录支撑「来源与覆盖」「审核记录」「版本」三个 Tab，因此在加载时就取回
  loadVersions()
  if (activeTab.value === 'source') loadSourceSnapshots()
  if (activeTab.value === 'review') loadReviewRecords()
  if (activeTab.value === 'chunks') {
    loadChunks()
    loadVectorTasks()
  }
})

watch(docId, async () => {
  versions.value = []
  chunks.value = []
  reviewRecords.value = []
  vectorTasks.value = []
  sourceSnapshots.value = []
  await loadDoc()
  await loadSnapshots()
  loadVersions()
  if (activeTab.value === 'source') loadSourceSnapshots()
  if (activeTab.value === 'review') loadReviewRecords()
  if (activeTab.value === 'chunks') {
    loadChunks()
    loadVectorTasks()
  }
})

watch(() => route.query.tab, (value) => {
  const next = String(value || 'content')
  if (next === activeTab.value) return
  activeTab.value = next
  if (next === 'versions' && !versions.value.length) loadVersions()
  if (next === 'source' && !sourceSnapshots.value.length) loadSourceSnapshots()
  if (next === 'review' && !reviewRecords.value.length) loadReviewRecords()
  if (next === 'chunks') {
    if (!chunks.value.length) loadChunks()
    // 索引任务独立于切分预览：INDEXING 中的文档可能还没有预览结果，但已有任务可看
    loadVectorTasks()
  }
})
</script>

<template>
  <div class="admin-page knowledge-doc-page">
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
        description="编辑内容、提交审核、发布并构建索引。批准、索引和发布是三个不同阶段，页面按状态只突出一个主操作。"
      >
        <template #status>
          <BusinessStatusBadge :status="doc.status" :label="knowledgeStatusLabel(doc.status)" />
          <span class="doc-version">v{{ doc.currentVersion }}</span>
        </template>
        <template #actions>
          <el-button :icon="RefreshCw" :loading="loading" @click="loadDoc">刷新</el-button>
          <el-button v-if="canEdit" :icon="Save" :loading="saving" @click="save">
            {{ status === 'DRAFT' ? '保存' : '保存并新建版本' }}
          </el-button>
          <el-button v-if="canEdit" :icon="Sparkles" @click="openGenerate">AI 生成草稿</el-button>

          <el-button v-if="canSubmitReview" type="primary" :icon="Send" :loading="actionLoading" @click="submit">
            提交审核
          </el-button>
          <template v-else-if="canReview">
            <el-button type="primary" :loading="actionLoading" @click="approve">审核通过</el-button>
            <el-button type="danger" plain :loading="actionLoading" @click="reject">驳回</el-button>
          </template>
          <el-button v-else-if="canPublish" type="primary" :icon="Upload" :loading="actionLoading" @click="publish">
            发布并构建索引
          </el-button>
          <el-button v-else-if="isPublished" type="primary" :icon="MessageSquareText" @click="router.push('/query')">
            进入智能问数
          </el-button>
        </template>
      </TaskPageHeader>

      <section class="knowledge-doc-page__lifecycle">
        <LifecycleStepper :steps="lifecycleSteps" />
        <p class="knowledge-doc-page__hint">{{ nextStepHint }}</p>
      </section>

      <el-tabs :model-value="activeTab" @update:model-value="selectTab">
        <!-- 内容 -->
        <el-tab-pane label="内容" name="content">
          <section class="knowledge-doc-page__editor">
            <div class="pane">
              <div class="pane__title">编辑</div>
              <el-input
                v-model="title"
                placeholder="文档标题"
                :disabled="!canEdit"
                class="pane__title-input"
              />
              <el-input
                v-model="content"
                type="textarea"
                :rows="22"
                :disabled="!canEdit"
                placeholder="在此编写 skills.md 内容（Markdown 格式）"
                resize="vertical"
                class="pane__textarea"
              />
            </div>
            <div class="pane">
              <div class="pane__title">预览</div>
              <pre class="pane__preview">{{ content || '（暂无内容）' }}</pre>
            </div>
          </section>
          <p v-if="!canEdit" class="knowledge-doc-page__locked">
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
                  <RouterLink class="snapshot-link" :to="'/admin/releases/snapshots/' + row.snapshotId">
                    {{ row.snapshotVersion ? 'v' + row.snapshotVersion : '#' + row.snapshotId }}
                  </RouterLink>
                </template>
              </el-table-column>
              <el-table-column label="快照状态" width="120">
                <template #default="{ row }">
                  <BusinessStatusBadge v-if="row.status" :status="row.status" />
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
                <p>版本行的审核状态自 2026-09-12 起由后端真实写入，可按版本查看。</p>
              </div>
            </div>
            <LoadingState v-if="versionsLoading" text="正在读取版本流转…" />
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
                  回滚会基于目标版本创建新的 ROLLBACK 版本并直接把文档置为索引中，不经过审核，
                  因此只对已发布文档开放该入口。历史版本不会被删除。
                </p>
              </div>
              <div class="card-heading__actions">
                <el-button :icon="GitCompareArrows" @click="openDiff">版本差异</el-button>
                <el-button :icon="RefreshCw" :loading="versionsLoading" @click="loadVersions">刷新</el-button>
              </div>
            </div>
            <LoadingState v-if="versionsLoading" variant="skeleton" :rows="4" />
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
                  <RouterLink v-if="row.metadataSnapshotId" class="snapshot-link" :to="'/admin/releases/snapshots/' + row.metadataSnapshotId">
                    #{{ row.metadataSnapshotId }}
                  </RouterLink>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column prop="changeSummary" label="变更摘要" min-width="200" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="创建时间" width="175" />
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="showVersion(row)">查看</el-button>
                  <el-button
                    v-if="canRollback && row.versionNo !== doc.currentVersion"
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
        <el-tab-pane label="切分与索引" name="chunks">
          <section class="knowledge-doc-page__card">
            <div class="card-heading">
              <div>
                <h3>索引状态</h3>
                <p>索引任务按文档记录，失败时可在下方看到原因；索引中的文档重复发布不会生效。</p>
              </div>
              <BusinessStatusBadge :status="doc.status" :label="knowledgeStatusLabel(doc.status)" />
            </div>
            <dl class="facts">
              <div><dt>可检索</dt><dd>{{ isPublished ? '是，已发布并完成索引' : '否' }}</dd></div>
              <div><dt>当前版本</dt><dd>v{{ doc.currentVersion }}</dd></div>
            </dl>
            <ErrorState v-if="taskError" :message="taskError" @retry="loadVectorTasks" />
            <LoadingState v-else-if="tasksLoading" text="正在读取索引任务…" />
            <EmptyState
              v-else-if="!vectorTasks.length"
              message="还没有索引任务。文档发布后会创建向量化任务，在这里可以看到执行结果与失败原因。"
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
      </el-tabs>
    </template>

    <!-- AI 生成草稿 -->
    <el-dialog v-model="generateVisible" title="AI 生成草稿" width="480px">
      <p class="muted">
        选择一个元数据快照，AI 会基于快照内容生成 skills.md 草稿。生成结果会直接写入文档内容并创建新版本。
      </p>
      <el-select v-model="generateSnapshotId" placeholder="选择快照" style="width: 100%">
        <el-option
          v-for="item in snapshots"
          :key="item.id"
          :value="item.id"
          :label="`v${item.snapshotVersion} · ${item.status}`"
        />
      </el-select>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generateLoading" @click="runGenerate">生成</el-button>
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
.knowledge-doc-page { display: grid; gap: 16px; }

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

.knowledge-doc-page__card {
  display: grid;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.knowledge-doc-page__editor { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }

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
  .knowledge-doc-page__editor { grid-template-columns: minmax(0, 1fr); }
}
</style>
