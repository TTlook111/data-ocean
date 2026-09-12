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
 * 后端事实：知识文档状态为 DRAFT -> PENDING_REVIEW -> APPROVED -> INDEXING -> PUBLISHED，
 * 审核通过与索引入库是两个独立阶段，页面必须分开表达。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, FileText, MessageSquareText, Plus, RefreshCw, Sparkles, X } from 'lucide-vue-next'
import {
  approveDoc,
  generateFromSnapshot,
  listKnowledgeDocs,
  rejectDoc,
  type KnowledgeDocItem,
} from '../../../api/admin/knowledge'
import {
  getBatchDatasourceReadiness,
  listSimpleDatasources,
  type DatasourceReadiness,
  type DatasourceSimpleItem,
} from '../../../api/admin/datasource'
import { listSnapshots, type SnapshotItem } from '../../../api/admin/metadata'
import { getPublishedSnapshot, type VersionHistoryItem } from '../../../api/admin/versioning'
import { knowledgeStatusLabel } from '../../../utils/enumLabels'
import { findDomainHome, resolveReadinessActionPath } from '../../../utils/adminNavigation'
import { useAdminContextStore } from '../../../stores/adminContext'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
// actionPath 未映射时的安全落点：当前业务域的首个工作区
const domainHome = computed(() => findDomainHome(String(route.meta.domainKey || '')))
const context = useAdminContextStore()

const activeTab = ref(String(route.query.tab || 'documents'))
const docs = ref<KnowledgeDocItem[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const actionLoading = ref(false)
const datasources = ref<DatasourceSimpleItem[]>([])
const publishedSnapshot = ref<VersionHistoryItem | null>(null)
const page = ref(Number(route.query.page) || 1)
const pageSize = 20

const filters = reactive({ status: '', keyword: '' })
const stats = reactive({ total: 0, published: 0, indexing: 0, pending: 0, draft: 0 })
/** 统计口径来自服务端 total；失败时记录错误而不静默保留旧值（§11.3） */
const statsError = ref('')
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
const snapshotsError = ref('')
let requestId = 0

const datasourceId = computed(() => context.datasourceId)
const datasourceName = (id: number) => datasources.value.find((item) => item.id === id)?.name || `数据源 #${id}`
const readinessAction = (row: DatasourceReadiness) => {
  const reason = row.blockReasons?.[0]
  if (!reason) return null
  return {
    label: reason.actionText || '去处理',
    target: resolveReadinessActionPath(reason.actionPath, {
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
  const current = ++requestId
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
    if (current !== requestId) return
    docs.value = result.data?.records || []
    total.value = result.data?.total || 0
  } catch (cause) {
    if (current !== requestId) return
    docs.value = []
    error.value = apiError(cause, '知识文档加载失败')
  } finally {
    if (current === requestId) loading.value = false
  }
}

/** 各状态全量计数：分别按状态取 total，避免用当前分页数据推断全局 */
async function loadStats() {
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
    statsError.value = apiError(cause, '状态统计加载失败')
  }
}

async function loadDatasources() {
  datasourcesError.value = ''
  try {
    datasources.value = (await listSimpleDatasources()).data || []
  } catch (cause) {
    datasources.value = []
    datasourcesError.value = apiError(cause, '数据源列表加载失败')
  }
  await loadReadiness()
}

/** 批量取各数据源的知识就绪度；知识准备情况总览的数据来源 */
async function loadReadiness() {
  if (!datasources.value.length) {
    readinessList.value = []
    return
  }
  readinessLoading.value = true
  readinessError.value = ''
  readinessWarning.value = ''
  try {
    const result = await getBatchDatasourceReadiness(datasources.value.map((item) => item.id))
    readinessList.value = result.data
    if (result.failedDatasourceIds.length) {
      readinessWarning.value = `部分数据源就绪度加载失败：${result.failedDatasourceIds.join('、')}`
    }
  } catch (cause) {
    readinessList.value = []
    readinessError.value = cause instanceof Error ? cause.message : '数据源知识准备情况加载失败'
  } finally {
    readinessLoading.value = false
  }
}

/** 判断当前数据源是否已有正式发布快照，用作「快照发布 → 生成知识」的下一步引导 */
async function loadPublishedSnapshot() {
  publishedSnapshotError.value = ''
  if (!datasourceId.value) {
    publishedSnapshot.value = null
    return
  }
  try {
    publishedSnapshot.value = (await getPublishedSnapshot(datasourceId.value)).data || null
  } catch (cause) {
    publishedSnapshot.value = null
    publishedSnapshotError.value = apiError(cause, '已发布快照加载失败')
  }
}

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
  if (publishedSnapshotError.value) return '已发布快照状态加载失败，暂时无法判断是否可以生成知识。'
  if (!publishedSnapshot.value) return '当前数据源还没有正式发布的快照。语义知识必须基于已发布快照生成。'
  return '当前筛选条件下没有知识文档。'
})

const emptyAction = computed(() => {
  if (activeTab.value === 'review') return ''
  if (publishedSnapshotError.value) return ''
  if (!publishedSnapshot.value && datasourceId.value) return '去版本发布'
  return 'AI 一键生成'
})

function handleEmptyAction() {
  if (!publishedSnapshot.value && datasourceId.value) {
    router.push({ path: '/admin/releases', query: { datasourceId: String(datasourceId.value) } })
    return
  }
  openGenerateDialog()
}

function goDetail(id: number) {
  router.push({ name: 'admin-semantic-knowledge-detail', params: { id } })
}

function goCreate() {
  router.push({ name: 'admin-semantic-knowledge-new' })
}

function goAsk() {
  router.push('/query')
}

async function approve(doc: KnowledgeDocItem) {
  try {
    await ElMessageBox.confirm(`通过「${doc.title}」的审核？通过后仍需执行发布会进入索引阶段。`, '审核通过')
    actionLoading.value = true
    await approveDoc(doc.id)
    ElMessage.success('审核已通过，下一步是发布并构建索引')
    await Promise.all([loadDocs(), loadStats()])
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '审核通过失败'))
  } finally {
    actionLoading.value = false
  }
}

async function reject(doc: KnowledgeDocItem) {
  try {
    const result = await ElMessageBox.prompt(`请说明驳回「${doc.title}」的原因`, '审核驳回', {
      inputValidator: (value) => Boolean(value?.trim()) || '驳回原因不能为空',
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
    })
    actionLoading.value = true
    await rejectDoc(doc.id, result.value)
    ElMessage.success('已驳回，文档回到草稿状态')
    await Promise.all([loadDocs(), loadStats()])
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(apiError(cause, '驳回失败'))
  } finally {
    actionLoading.value = false
  }
}

/* ---------------- AI 一键生成 ---------------- */

const generateDialogVisible = ref(false)
const generateLoading = ref(false)
const generateForm = reactive<{ datasourceId?: number; snapshotId?: number }>({})
const snapshots = ref<SnapshotItem[]>([])
const generatedDocs = ref<Array<{ id: number; title: string; tableNames: string[] }>>([])

function openGenerateDialog() {
  generateForm.datasourceId = datasourceId.value
  generateForm.snapshotId = context.snapshotId
  snapshots.value = []
  generatedDocs.value = []
  generateDialogVisible.value = true
  if (generateForm.datasourceId) loadSnapshots(generateForm.datasourceId)
}

async function loadSnapshots(id: number) {
  snapshotsError.value = ''
  try {
    const result = await listSnapshots({ datasourceId: id, page: 1, size: 50 })
    snapshots.value = result.data?.records || []
  } catch (cause) {
    snapshots.value = []
    snapshotsError.value = apiError(cause, '快照列表加载失败')
  }
}

function onGenerateDatasourceChange(id?: number) {
  generateForm.snapshotId = undefined
  if (id) loadSnapshots(id)
  else snapshots.value = []
}

async function runGenerate() {
  if (!generateForm.datasourceId || !generateForm.snapshotId) {
    ElMessage.warning('请选择数据源和快照')
    return
  }
  generateLoading.value = true
  try {
    const result = await generateFromSnapshot(generateForm.datasourceId, generateForm.snapshotId)
    generatedDocs.value = result.data || []
    ElMessage.success(`已生成 ${generatedDocs.value.length} 份 skills.md 草稿`)
    await Promise.all([loadDocs(), loadStats()])
  } catch (cause) {
    ElMessage.error(apiError(cause, 'AI 生成失败'))
  } finally {
    generateLoading.value = false
  }
}

onMounted(async () => {
  await Promise.allSettled([context.initialize(), loadDatasources()])
  await Promise.allSettled([loadDocs(), loadStats(), loadPublishedSnapshot()])
})

watch(datasourceId, async () => {
  page.value = 1
  await Promise.all([loadDocs(), loadStats(), loadPublishedSnapshot()])
})

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
    <TaskPageHeader
      eyebrow="语义中心"
      title="语义知识"
      description="把已发布快照转成 skills.md，经过审核、发布和索引后进入 RAG。批准、索引和发布是三个不同阶段。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="loadDocs(); loadStats(); loadReadiness()">刷新</el-button>
        <el-button :icon="Plus" @click="goCreate">手动新建</el-button>
        <el-button type="primary" :icon="Sparkles" @click="openGenerateDialog">AI 一键生成</el-button>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="statsError" :message="statsError" @retry="loadStats" />
    <section v-else class="knowledge-page__stats">
      <div class="stat"><span>文档总数</span><strong>{{ stats.total }}</strong></div>
      <div class="stat stat--success"><span>已发布（可检索）</span><strong>{{ stats.published }}</strong></div>
      <div class="stat stat--warning"><span>索引中</span><strong>{{ stats.indexing }}</strong></div>
      <div class="stat stat--warning"><span>待审核</span><strong>{{ stats.pending }}</strong></div>
      <div class="stat"><span>草稿</span><strong>{{ stats.draft }}</strong></div>
    </section>

    <!-- 每个数据源的知识准备情况（开发指导 §7.11） -->
    <section class="knowledge-page__readiness">
      <div class="knowledge-page__readiness-heading">
        <div>
          <h3>数据源知识准备情况</h3>
          <p>
            逐个数据源确认是否已有可检索的语义知识。知识需由已发布快照生成，并经审核、
            索引、发布后才会进入 RAG 检索。
          </p>
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
        <el-table-column label="知识状态" width="150">
          <template #default="{ row }">
            <BusinessStatusBadge
              :status="row.knowledgeReady ? 'PUBLISHED' : 'DRAFT'"
              :label="row.knowledgeReady ? '已发布可检索' : '尚未发布'"
            />
          </template>
        </el-table-column>
        <el-table-column label="知识版本" width="100">
          <template #default="{ row }">{{ row.knowledgeVersion ? 'v' + row.knowledgeVersion : '—' }}</template>
        </el-table-column>
        <el-table-column label="当前阶段" width="130">
          <template #default="{ row }">
            <span class="knowledge-page__muted">{{ row.stageLabel || row.stage || '—' }}</span>
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
              v-else-if="readinessAction(row)?.target.known"
              class="knowledge-page__link"
              :to="readinessAction(row)!.target.to!"
            >{{ readinessAction(row)!.label }}</RouterLink>
            <!-- actionPath 未映射时不得猜测目标，也不能只留一句死文案：
                 按《实施任务清单》§4 给出当前业务域的安全落点 -->
            <span v-else-if="readinessAction(row)?.label" class="knowledge-page__muted">
              {{ readinessAction(row)!.label }}
              <RouterLink class="knowledge-page__link" :to="domainHome.path">返回{{ domainHome.label }}</RouterLink>
            </span>
            <span v-else class="knowledge-page__muted">等待后端状态</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <ErrorState v-if="publishedSnapshotError" :message="publishedSnapshotError" @retry="loadPublishedSnapshot" />
    <p v-else-if="!datasourceId" class="knowledge-page__scope-note">
      当前未限定数据源，列表展示全部知识文档。使用顶部的数据源范围可以聚焦到单个数据源。
    </p>
    <p v-else-if="publishedSnapshot" class="knowledge-page__scope-note is-ok">
      当前数据源已发布快照 v{{ publishedSnapshot.snapshotVersion }}，可以基于该快照生成或更新语义知识。
    </p>

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
      <el-tab-pane label="审核队列" name="review">
        <p class="knowledge-page__tab-note">
          审核队列只列出 PENDING_REVIEW 文档。通过后文档进入已批准状态，仍需发布并构建索引才会进入检索。
        </p>
      </el-tab-pane>
    </el-tabs>

    <ErrorState v-if="error" :message="error" @retry="loadDocs" />
    <LoadingState v-else-if="loading" variant="skeleton" :rows="6" />
    <EmptyState
      v-else-if="!visibleDocs.length"
      :message="emptyMessage"
      :action-text="emptyAction"
      @action="handleEmptyAction"
    />
    <section v-else class="knowledge-page__card">
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
              <el-button link type="success" @click="approve(row)"><Check :size="14" />通过</el-button>
              <el-button link type="danger" @click="reject(row)"><X :size="14" />驳回</el-button>
            </template>
            <el-button v-else-if="row.status === 'PUBLISHED'" link @click="goAsk()">
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

    <el-dialog v-model="generateDialogVisible" title="AI 一键生成 skills.md" width="580px" :close-on-click-modal="!generateLoading">
      <template v-if="!generatedDocs.length">
        <p class="generate-hint">
          AI 会分析所选快照的表结构，识别业务域，并为每个域生成一份独立的 skills.md 草稿。
          生成结果是草稿，仍需人工编辑、审核、发布和索引。
        </p>
        <el-form label-width="88px">
          <el-form-item label="数据源">
            <el-select v-model="generateForm.datasourceId" placeholder="选择数据源" style="width: 100%" @change="onGenerateDatasourceChange">
              <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="快照">
            <ErrorState v-if="snapshotsError" :message="snapshotsError" @retry="generateForm.datasourceId && loadSnapshots(generateForm.datasourceId)" />
            <el-select v-model="generateForm.snapshotId" placeholder="选择元数据快照" style="width: 100%" :disabled="!generateForm.datasourceId">
              <el-option v-for="item in snapshots" :key="item.id" :label="`v${item.snapshotVersion} · ${item.status}`" :value="item.id" />
            </el-select>
          </el-form-item>
        </el-form>
      </template>

      <template v-else>
        <el-alert type="success" :closable="false" show-icon :title="`已生成 ${generatedDocs.length} 份 skills.md 草稿，下一步是逐份编辑并提交审核`" />
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
        <el-button @click="generateDialogVisible = false">{{ generatedDocs.length ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!generatedDocs.length" type="primary" :loading="generateLoading" @click="runGenerate">
          <Sparkles :size="15" style="margin-right: 6px" />开始生成
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.knowledge-page { display: grid; gap: 16px; }

.knowledge-page__stats { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.stat {
  display: grid;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.stat span { color: var(--do-muted); font-size: 12px; }
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
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}
.knowledge-page__readiness-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.knowledge-page__readiness-heading h3 { margin: 0; color: var(--do-ink); font-size: 15px; }
.knowledge-page__readiness-heading p { margin: 5px 0 0; color: var(--do-muted); font-size: 12px; max-width: 720px; }
.knowledge-page__link { color: var(--do-primary-strong); font-size: 12px; font-weight: 800; }
.knowledge-page__muted { color: var(--do-muted); }
.knowledge-page__scope-note.is-ok { background: var(--do-success-soft); color: var(--do-ink); }
.knowledge-page__tab-note { margin: 0; color: var(--do-muted); font-size: 12px; }

.knowledge-page__toolbar { display: flex; gap: 10px; flex-wrap: wrap; }

.knowledge-page__card {
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
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
