<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight, Database, PlugZap, RefreshCw, Sparkles, Table2, Workflow } from 'lucide-vue-next'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import {
  getDatasource,
  getDatasourceReadiness,
  testSavedDatasourceConnection,
  updateDatasourceStatus,
  type DatasourceItem,
  type DatasourceReadiness,
} from '../../../api/admin/datasource'
import { resolveReadinessActionPath } from '../../../utils/adminNavigation'
import { knowledgeStatusLabel, snapshotStatusLabel } from '../../../utils/enumLabels'
import { listSnapshots, listSyncTasks, triggerSync, type SnapshotItem, type SyncTaskItem } from '../../../api/admin/metadata'
import { listQualityIssues, type QualityIssueItem } from '../../../api/admin/governance'
import { listKnowledgeDocs, type KnowledgeDocItem } from '../../../api/admin/knowledge'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ObjectContextSummary from '../../../components/admin/ObjectContextSummary.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import LifecycleStepper from '../../../components/admin/LifecycleStepper.vue'
import ReadinessPanel from '../../../components/admin/ReadinessPanel.vue'
import NextActionCard from '../../../components/admin/NextActionCard.vue'
import ActivityTimeline from '../../../components/admin/ActivityTimeline.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import EmptyState from '../../../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const datasourceId = computed(() => Number(route.params.id))
const datasource = ref<DatasourceItem | null>(null)
const readiness = ref<DatasourceReadiness | null>(null)
const snapshots = ref<SnapshotItem[]>([])
const syncTasks = ref<SyncTaskItem[]>([])
const qualityIssues = ref<QualityIssueItem[]>([])
const knowledgeDocs = ref<KnowledgeDocItem[]>([])
const loading = ref(true)
const actionLoading = ref(false)
const error = ref('')

const latestSnapshot = computed(() => snapshots.value[0])

// 快照列表来自 Promise.allSettled，空数组也可能只是「请求失败」。
// 区分这两种情况，避免在明明有草稿快照时误判为「还没采集」。
const snapshotRequestOk = ref(false)
/** 语义知识文档请求是否成功；失败要显示错误态，不能伪装成「没有知识文档」 */
const knowledgeRequestOk = ref(false)

interface PrimaryAction {
  key: string
  label: string
  icon?: Component
  to?: RouteLocationRaw
}

/** 能在本页内联完成、且语义与后端 actionText 完全一致的动作。 */
const INLINE_ACTIONS: Record<string, { key: string; icon: Component }> = {
  DATASOURCE_DISABLED: { key: 'enable', icon: Database },
  CONNECTION_NOT_HEALTHY: { key: 'test', icon: PlugZap },
}

/**
 * 头部主操作。
 *
 * 优先级完全由后端 `blockReasons` 的顺序决定，前端不做二次排序，也不自建
 * `code → 中文` 文案表（文案一律取后端 `actionText`）——见《开发指导》§3.1
 * 「不能在前端重新拼装另一套就绪状态」。前端只决定「怎么执行」。
 */
const primaryAction = computed<PrimaryAction>(() => {
  if (!readiness.value) return { key: 'reload', label: '刷新状态' }
  // 可证明 blockReasons 为空等价于 askable：后端 appendBlockReasons 的 6 个条件
  // 正好是 askable 五个分量的否定。
  const reason = readiness.value.blockReasons?.[0]
  if (!reason) return { key: 'query', label: '进入智能问数', icon: ArrowRight }

  // 后端用同一个 code 表达了两种情况（metadataReady = publishedSnapshot != null）：
  // 一条快照都没有 vs 有草稿快照但未发布。两者该做的事相反，必须按页面已有数据分流。
  // 门禁要求：连接通过但无快照时突出「启动采集」，不得显示为可执行「发布快照」
  // （《实施任务清单》§9）。
  if (reason.code === 'SNAPSHOT_NOT_PUBLISHED' && snapshotRequestOk.value && !snapshots.value.length) {
    return { key: 'collect', label: '开始采集', icon: Workflow }
  }

  const inline = INLINE_ACTIONS[reason.code]
  if (inline) return { key: inline.key, label: reason.actionText || '去处理', icon: inline.icon }

  // 治理阻塞问题按「已发布快照」统计，必须带 publishedSnapshotId；
  // 用 latestSnapshot（可能是草稿快照）会让目标页过滤到 0 条问题。
  const target = resolveReadinessActionPath(reason.actionPath, {
    datasourceId: datasourceId.value,
    snapshotId: readiness.value.publishedSnapshotId,
  })
  // 未映射路径不得猜测：回退为刷新状态，而不是跳到占位目标（《实施任务清单》§4）。
  if (!target.known) return { key: 'reload', label: '刷新状态', icon: RefreshCw }
  return { key: 'navigate', label: reason.actionText || '去处理', icon: ArrowRight, to: target.to }
})

/**
 * 后端 `applyStage` 只会产出这 7 个 stage 取值
 * （DatasourceReadinessServiceImpl.applyStage）。这里做显式翻译，
 * 不用字符串 `includes` 猜测——未知取值落到空串、不高亮任何步骤，
 * 后端新增 stage 时会显式失配而不是静默错配。
 */
const STAGE_TO_STEP: Record<string, string> = {
  CONNECTION_CHECK_REQUIRED: 'connection',
  SNAPSHOT_PENDING: 'collection',
  GOVERNANCE_BLOCKED: 'governance',
  KNOWLEDGE_PENDING: 'knowledge',
  PERMISSION_PENDING: 'permission',
  ASKABLE: 'askable',
  // UNKNOWN 在后端不可达：applyStage 先判 askable，而五个 !isX 全不成立等价于 askable=true。
  UNKNOWN: '',
}

const currentStepKey = computed(() => STAGE_TO_STEP[readiness.value?.stage || ''] || '')

const lifecycleSteps = computed(() => {
  // 「快照发布」不再是独立步骤：后端 metadataReady 同时表示「已采集」和「已发布」
  // （metadataReady = publishedSnapshot != null），与 publishedSnapshotId 布尔值恒等，
  // 该步骤永远不可能成为当前步。6 步对应后端 6 个就绪维度。
  const values = [
    { key: 'connection', label: '连接', done: Boolean(readiness.value?.connectionReady) },
    { key: 'collection', label: '采集', done: Boolean(readiness.value?.metadataReady) },
    { key: 'governance', label: '治理', done: Boolean(readiness.value?.governanceReady) },
    { key: 'knowledge', label: '知识发布', done: Boolean(readiness.value?.knowledgeReady) },
    { key: 'permission', label: '授权', done: Boolean(readiness.value?.permissionReady) },
    { key: 'askable', label: '可问数', done: Boolean(readiness.value?.askable) },
  ]
  return values.map((item) => ({ ...item, current: item.key === currentStepKey.value }))
})

const activityItems = computed(() => [
  ...syncTasks.value.slice(0, 3).map((task) => ({
    time: task.finishedAt || task.startedAt,
    action: '元数据采集 · ' + task.status,
    result: task.errorMessage || task.datasourceName,
  })),
  ...snapshots.value.slice(0, 2).map((snapshot) => ({
    time: snapshot.createdAt,
    action: '快照 v' + snapshot.snapshotVersion,
    result: snapshot.status,
  })),
])

function apiError(cause: unknown, fallback: string) {
  const message = (cause as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (cause instanceof Error ? cause.message : fallback)
}

async function load() {
  if (!Number.isFinite(datasourceId.value)) {
    error.value = '数据源编号无效'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  snapshotRequestOk.value = false
  knowledgeRequestOk.value = false
  try {
    const [sourceResult, readinessResult] = await Promise.all([
      getDatasource(datasourceId.value),
      getDatasourceReadiness(datasourceId.value),
    ])
    datasource.value = sourceResult.data
    readiness.value = readinessResult.data

    const optionalResults = await Promise.allSettled([
      listSnapshots({ datasourceId: datasourceId.value, page: 1, size: 5 }),
      listSyncTasks({ datasourceId: datasourceId.value, page: 1, size: 5 }),
      listKnowledgeDocs({ datasourceId: datasourceId.value, page: 1, pageSize: 5 }),
    ])
    const snapshotResult = optionalResults[0]
    const taskResult = optionalResults[1]
    const knowledgeResult = optionalResults[2]
    snapshotRequestOk.value = snapshotResult.status === 'fulfilled'
    if (snapshotResult.status === 'fulfilled') snapshots.value = snapshotResult.value.data.records || []
    if (taskResult.status === 'fulfilled') syncTasks.value = taskResult.value.data.records || []
    knowledgeRequestOk.value = knowledgeResult.status === 'fulfilled'
    if (knowledgeResult.status === 'fulfilled') knowledgeDocs.value = knowledgeResult.value.data.records || []

    if (latestSnapshot.value) {
      const issueResult = await listQualityIssues(latestSnapshot.value.id, { page: 1, size: 5, status: 'OPEN' }).catch(() => null)
      qualityIssues.value = issueResult?.data.records || []
    } else {
      qualityIssues.value = []
    }
  } catch (cause) {
    error.value = apiError(cause, '数据源详情加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function testConnection() {
  actionLoading.value = true
  try {
    const result = await testSavedDatasourceConnection(datasourceId.value)
    ElMessage[result.data.success ? 'success' : 'error'](result.data.success ? '连接测试成功' : '连接失败：' + result.data.message)
    await load()
  } catch (cause) {
    ElMessage.error(apiError(cause, '连接测试失败'))
  } finally {
    actionLoading.value = false
  }
}

async function enableDatasource() {
  actionLoading.value = true
  try {
    await updateDatasourceStatus(datasourceId.value, 1)
    ElMessage.success('数据源已启用')
    await load()
  } catch (cause) {
    ElMessage.error(apiError(cause, '启用数据源失败'))
  } finally {
    actionLoading.value = false
  }
}

async function startCollection() {
  actionLoading.value = true
  try {
    await triggerSync({ datasourceId: datasourceId.value, includeStatistics: false })
    ElMessage.success('采集任务已发起')
    await router.push({ path: '/admin/collections', query: { datasourceId: String(datasourceId.value) } })
  } catch (cause) {
    ElMessage.error(apiError(cause, '发起采集失败'))
  } finally {
    actionLoading.value = false
  }
}

function runPrimaryAction() {
  const action = primaryAction.value
  if (action.key === 'test') return testConnection()
  if (action.key === 'enable') return enableDatasource()
  if (action.key === 'collect') return startCollection()
  if (action.key === 'query') return router.push('/query')
  if (action.key === 'navigate' && action.to) return router.push(action.to)
  return load()
}

function openSnapshot(snapshotId: number) {
  router.push('/admin/releases/snapshots/' + snapshotId)
}

onMounted(async () => {
  await load()
  if (!error.value && route.query.action === 'test') await testConnection()
})
</script>

<template>
  <div class="admin-page datasource-cockpit">
    <ObjectContextSummary
      v-if="datasource"
      :title="datasource.name"
      :description="datasource.host + ':' + datasource.port + ' / ' + datasource.databaseName"
      back-to="/admin/data-sources"
      source-label="数据源接入"
    />
    <TaskPageHeader
      title="数据源驾驶舱"
      :description="readiness?.blockReasons?.length ? '当前存在阻断原因，请按责任角色和行动入口推进。' : '沿着连接、采集、治理、发布、知识和授权完成上线。'"
    >
      <template #status>
        <BusinessStatusBadge v-if="datasource" :status="datasource.status === 1 ? 'ENABLED' : 'DISABLED'" />
        <BusinessStatusBadge v-if="readiness" :status="readiness.askable ? 'PUBLISHED' : readiness.stage" :label="readiness.askable ? '可以问数' : readiness.stageLabel" />
      </template>
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading" @click="load">刷新状态</el-button>
        <el-button v-if="primaryAction.icon" type="primary" :icon="primaryAction.icon" :loading="actionLoading" @click="runPrimaryAction">{{ primaryAction.label }}</el-button>
      </template>
    </TaskPageHeader>

    <LoadingState v-if="loading" variant="skeleton" :rows="8" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="readiness">
      <section class="datasource-cockpit__lifecycle">
        <div class="section-heading"><div><h2>上线流程</h2><p>当前动作由 readiness 和数据源服务端状态共同决定。</p></div></div>
        <LifecycleStepper :steps="lifecycleSteps" />
      </section>

      <div class="datasource-cockpit__grid">
        <div class="datasource-cockpit__main">
          <ReadinessPanel :readiness="readiness" />
          <NextActionCard :reasons="readiness.blockReasons" :datasource-id="datasourceId" />
          <section class="datasource-cockpit__card">
            <div class="section-heading">
              <div><h2>当前阶段</h2><p>{{ readiness.stageLabel }} · {{ readiness.progress }}%</p></div>
              <el-button text @click="runPrimaryAction">{{ primaryAction.label }}</el-button>
            </div>
            <div v-if="currentStepKey === 'collection'" class="stage-callout">
              <Table2 :size="20" />
              <div><strong>采集会形成新的元数据快照</strong><span>采集成功后进入版本发布和治理检查，草稿快照不会直接变成正式资产。</span></div>
            </div>
            <div v-else-if="readiness.askable" class="stage-callout stage-callout--success">
              <Sparkles :size="20" />
              <div><strong>数据源已达到可问数条件</strong><span>可以进入智能问数，也可以继续通过审计、反馈和治理工作区运营。</span></div>
            </div>
            <div v-else class="stage-callout">
              <Workflow :size="20" />
              <div><strong>{{ readiness.stageLabel }}</strong><span>优先处理上方阻断原因，再回到此驾驶舱刷新状态。</span></div>
            </div>
          </section>
        </div>

        <aside class="datasource-cockpit__side">
          <section class="datasource-cockpit__card">
            <div class="section-heading"><div><h2>最近快照</h2><p>正式发布资产由版本发布工作区管理。</p></div></div>
            <div v-if="snapshots.length" class="compact-list">
              <button v-for="snapshot in snapshots" :key="snapshot.id" type="button" class="compact-list__item" @click="openSnapshot(snapshot.id)">
                <span><strong>v{{ snapshot.snapshotVersion }}</strong><small>{{ snapshot.tableCount }} 表 · {{ snapshot.columnCount }} 字段</small></span>
                <BusinessStatusBadge :status="snapshot.status" :label="snapshotStatusLabel(snapshot.status)" />
              </button>
            </div>
            <EmptyState v-else message="暂无快照，连接正常后可以开始采集。" action-text="开始采集" @action="startCollection" />
          </section>
          <section class="datasource-cockpit__card">
            <div class="section-heading"><div><h2>最近活动</h2><p>采集和快照记录。</p></div></div>
            <ActivityTimeline :items="activityItems" />
          </section>
          <section class="datasource-cockpit__card">
            <div class="section-heading"><div><h2>语义知识</h2><p>最近的 skills.md 文档与发布状态。</p></div></div>
            <div v-if="knowledgeDocs.length" class="compact-list">
              <RouterLink v-for="doc in knowledgeDocs" :key="doc.id" class="compact-list__item" :to="'/admin/semantics/knowledge/' + doc.id">
                <span><strong>{{ doc.title }}</strong><small>v{{ doc.currentVersion }} · {{ knowledgeStatusLabel(doc.status) }}</small></span>
                <ArrowRight :size="15" />
              </RouterLink>
            </div>
            <!-- 请求失败必须说成失败：空态会把「接口挂了」读成「还没有知识文档」 -->
            <EmptyState
              v-else-if="!knowledgeRequestOk"
              message="语义知识文档加载失败，无法确认当前是否已有知识文档。"
              action-text="重试"
              @action="load"
            />
            <EmptyState
              v-else
              message="该数据源还没有语义知识文档。"
              action-text="去准备知识"
              @action="router.push({ path: '/admin/semantics/knowledge', query: { datasourceId: String(datasourceId) } })"
            />
          </section>
          <section class="datasource-cockpit__card">
            <div class="section-heading"><div><h2>治理问题</h2><p>当前快照待处理问题。</p></div></div>
            <RouterLink v-if="qualityIssues.length" class="inline-link" :to="{ path: '/admin/governance/issues', query: { datasourceId: String(datasourceId), snapshotId: String(latestSnapshot?.id || '') } }">
              {{ qualityIssues.length }} 个问题需要处理 <ArrowRight :size="15" />
            </RouterLink>
            <EmptyState v-else message="当前没有加载到待处理问题" />
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.datasource-cockpit {
  display: grid;
  gap: 18px;
}

.datasource-cockpit__lifecycle,
.datasource-cockpit__card {
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.section-heading h2 {
  margin: 0;
  color: var(--do-ink);
  font-size: 16px;
}

.section-heading p {
  margin: 5px 0 0;
  color: var(--do-muted);
  font-size: 12px;
}

.datasource-cockpit__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(300px, .7fr);
  gap: 18px;
}

.datasource-cockpit__main,
.datasource-cockpit__side {
  display: grid;
  align-content: start;
  gap: 18px;
  min-width: 0;
}

.stage-callout {
  display: flex;
  gap: 12px;
  padding: 16px;
  border-radius: var(--do-radius-md);
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}

.stage-callout--success {
  color: var(--do-success);
  background: var(--do-success-soft);
}

.stage-callout div {
  display: grid;
  gap: 5px;
}

.stage-callout strong {
  color: var(--do-ink);
  font-size: 14px;
}

.stage-callout span {
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
}

.compact-list {
  display: grid;
  gap: 8px;
}

.compact-list__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  background: var(--do-surface);
  cursor: pointer;
  text-align: left;
}

.compact-list__item:hover {
  border-color: var(--do-primary);
  background: var(--do-primary-soft);
}

.compact-list__item span {
  display: grid;
  gap: 3px;
}

.compact-list__item strong {
  color: var(--do-ink);
  font-size: 13px;
}

.compact-list__item small {
  color: var(--do-muted);
  font-size: 11px;
}

.inline-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--do-primary-strong);
  font-size: 13px;
  font-weight: 800;
}

@media (max-width: 1000px) {
  .datasource-cockpit__grid {
    grid-template-columns: 1fr;
  }
}
</style>
