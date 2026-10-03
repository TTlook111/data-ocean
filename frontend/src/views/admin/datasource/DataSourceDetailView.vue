<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight, CheckCircle2, CircleAlert, Database, PlugZap, RefreshCw, Workflow } from 'lucide-vue-next'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import {
  getDatasource,
  getDatasourceReadiness,
  testSavedDatasourceConnection,
  updateDatasourceStatus,
  type DatasourceItem,
  type DatasourceReadiness,
} from '../../../api/admin/datasource'
import { resolveReadinessAction } from '../../../utils/adminNavigation'
import { knowledgeStatusLabel, snapshotStatusLabel } from '../../../utils/enumLabels'
import { listSnapshots, listSyncTasks, triggerSync, type SnapshotItem, type SyncTaskItem } from '../../../api/admin/metadata'
import { listQualityIssues, type QualityIssueItem } from '../../../api/admin/governance'
import { listKnowledgeDocs, type KnowledgeDocItem } from '../../../api/admin/knowledge'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import ReadinessPanel from '../../../components/admin/ReadinessPanel.vue'
import NextActionCard from '../../../components/admin/NextActionCard.vue'
import ActivityTimeline from '../../../components/admin/ActivityTimeline.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import ErrorState from '../../../components/common/ErrorState.vue'

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


// 快照列表来自 Promise.allSettled，空数组也可能只是「请求失败」。
// 区分这两种情况，避免在明明有草稿快照时误判为「还没采集」。
const snapshotRequestOk = ref(false)
/** 语义知识文档请求是否成功；失败要显示错误态，不能伪装成「没有知识文档」 */
const knowledgeRequestOk = ref(false)
const syncRequestOk = ref(false)
const issuesError = ref('')
const publishedSnapshot = computed(() => snapshots.value.find(item => item.id === readiness.value?.publishedSnapshotId))

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
  const reason = readiness.value.blockReasons?.[0]
  if (!reason) return readiness.value.askable
    ? { key: 'navigate', label: '查看数据资产', icon: Database, to: { path: '/admin/assets', query: { datasourceId: String(datasourceId.value) } } }
    : { key: 'reload', label: '刷新状态', icon: RefreshCw }

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
  const target = resolveReadinessAction(reason.code, {
    datasourceId: datasourceId.value,
    snapshotId: readiness.value.publishedSnapshotId,
  })
  // 未知状态不得猜测：回退为刷新状态，而不是跳到占位目标。
  if (!target) return { key: 'reload', label: '刷新状态', icon: RefreshCw }
  return { key: 'navigate', label: reason.actionText || '去处理', icon: ArrowRight, to: target }
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
  syncRequestOk.value = false
  issuesError.value = ''
  snapshots.value = []
  syncTasks.value = []
  knowledgeDocs.value = []
  qualityIssues.value = []
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
    syncRequestOk.value = taskResult.status === 'fulfilled'
    knowledgeRequestOk.value = knowledgeResult.status === 'fulfilled'
    if (knowledgeResult.status === 'fulfilled') knowledgeDocs.value = knowledgeResult.value.data.records || []

    if (readiness.value?.publishedSnapshotId) {
      try {
        const issueResult = await listQualityIssues(readiness.value.publishedSnapshotId, { page: 1, size: 5, status: 'OPEN' })
        qualityIssues.value = issueResult.data.records || []
      } catch (cause) {
        issuesError.value = apiError(cause, '治理问题读取失败，请重试')
      }
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
    <RouterLink class="datasource-back" to="/admin/data-sources"><ArrowLeft :size="15" aria-hidden="true" />返回数据源</RouterLink>
    <TaskPageHeader :title="datasource?.name || '数据源详情'" :description="datasource ? datasource.dbType + ' · ' + datasource.databaseName : '查看准备条件和待处理事项。'">
      <template #actions><el-button :icon="RefreshCw" :loading="loading" @click="load">刷新状态</el-button><el-button v-if="primaryAction.icon && primaryAction.key !== 'reload'" type="primary" :icon="primaryAction.icon" :loading="actionLoading" :disabled="loading || !!error" @click="runPrimaryAction">{{ primaryAction.label }}</el-button></template>
    </TaskPageHeader>
    <LoadingState v-if="loading" variant="skeleton" :rows="7" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="readiness">
      <section class="source-outcome" :class="{ ready: readiness.askable }">
        <component :is="readiness.askable ? CheckCircle2 : CircleAlert" :size="28" aria-hidden="true" />
        <div><strong>{{ readiness.askable ? '已具备问数条件 · 当前没有阻断项' : readiness.stageLabel }}</strong><p>{{ readiness.askable ? '准备条件已满足，可继续查看数据资产或管理知识与授权。' : '先处理下方具体事项，再刷新状态确认准备情况。' }}</p></div>
      </section>
      <div class="datasource-cockpit__grid">
        <div class="datasource-cockpit__main">
          <ReadinessPanel :readiness="readiness" />
          <NextActionCard v-if="readiness.blockReasons.length" :reasons="readiness.blockReasons" :datasource-id="datasourceId" :snapshot-id="readiness.publishedSnapshotId" />
          <section class="datasource-cockpit__card">
            <div class="section-heading"><h2>治理问题</h2><RouterLink class="inline-link" :to="{ path: '/admin/governance/issues', query: { datasourceId: String(datasourceId), snapshotId: readiness.publishedSnapshotId ? String(readiness.publishedSnapshotId) : undefined } }">查看问题中心<ArrowRight :size="14" aria-hidden="true" /></RouterLink></div>
            <ErrorState v-if="issuesError" :message="issuesError" @retry="load" />
            <p v-else-if="!readiness.publishedSnapshotId" class="muted">发布快照后，可在这里查看对应的治理问题。</p>
            <p v-else-if="qualityIssues.length" class="muted">最近 {{ qualityIssues.length }} 个待处理问题可在问题中心继续处理。</p>
            <p v-else class="muted">当前发布快照没有读取到待处理问题。</p>
          </section>
        </div>
        <aside class="datasource-cockpit__side">
          <section class="datasource-cockpit__card">
            <h2>数据源信息</h2>
            <dl class="source-facts"><div><dt>数据源类型</dt><dd>{{ datasource?.dbType }}</dd></div><div><dt>数据库</dt><dd>{{ datasource?.databaseName }}</dd></div><div><dt>连接地址</dt><dd>{{ datasource?.host }}:{{ datasource?.port }}</dd></div><div><dt>使用状态</dt><dd><BusinessStatusBadge :status="datasource?.status === 1 ? 'ENABLED' : 'DISABLED'" /></dd></div></dl>
            <div class="source-detail-section">
              <h2>当前发布快照</h2>
              <ErrorState v-if="!snapshotRequestOk" message="快照读取失败，无法展示快照详情。" @retry="load" />
              <button v-else-if="readiness.publishedSnapshotId" type="button" class="compact-list__item" @click="openSnapshot(readiness.publishedSnapshotId)"><span><strong>{{ readiness.snapshotVersion ? 'v' + readiness.snapshotVersion : '已发布快照' }}</strong><small v-if="publishedSnapshot">{{ publishedSnapshot.tableCount }} 张表 · {{ publishedSnapshot.columnCount }} 个字段</small></span><BusinessStatusBadge status="PUBLISHED" /></button>
              <p v-else class="muted">还没有已发布快照。采集后需审核并发布。</p>
              <details v-if="snapshots.length" class="source-details"><summary>查看最近快照</summary><div class="compact-list"><button v-for="snapshot in snapshots" :key="snapshot.id" class="compact-list__item" type="button" @click="openSnapshot(snapshot.id)"><span>v{{ snapshot.snapshotVersion }}</span><BusinessStatusBadge :status="snapshot.status" :label="snapshotStatusLabel(snapshot.status)" /></button></div></details>
            </div>
            <div class="source-detail-section">
              <h2>语义知识</h2>
              <ErrorState v-if="!knowledgeRequestOk" message="知识文档读取失败，请重试。" @retry="load" />
              <div v-else-if="knowledgeDocs.length" class="compact-list"><RouterLink v-for="doc in knowledgeDocs" :key="doc.id" class="compact-list__item" :to="'/admin/semantics/knowledge/' + doc.id"><span><strong>{{ doc.title }}</strong><small>v{{ doc.currentVersion }} · {{ knowledgeStatusLabel(doc.status) }}</small></span><ArrowRight :size="15" aria-hidden="true" /></RouterLink></div>
              <p v-else class="muted">还没有知识文档。通过准备清单进入语义知识工作区。</p>
            </div>
          </section>
          <details class="datasource-cockpit__card source-details"><summary>最近采集与快照活动</summary><ErrorState v-if="!syncRequestOk" message="采集记录读取失败，请重试。" @retry="load" /><ActivityTimeline v-else :items="activityItems" /></details>
        </aside>
      </div>
    </template>
  </div>
</template>
<style scoped>
.datasource-back { display: inline-flex; align-items: center; gap: 7px; color: var(--do-primary-strong); margin-bottom: 18px; font-size: 13px; }
.source-outcome { display: flex; align-items: center; gap: 16px; padding: 20px 24px; margin-bottom: 24px; color: var(--do-warning); background: var(--do-warning-soft); border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); }
.source-outcome.ready { color: var(--do-success); background: var(--do-success-soft); }
.source-outcome strong { font-size: 18px; color: var(--do-ink); }
.source-outcome p { margin: 7px 0 0; color: var(--do-muted); font-size: 14px; line-height: 1.6; }
.datasource-cockpit__grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(280px, .8fr); gap: 24px; }
.datasource-cockpit__main, .datasource-cockpit__side { display: grid; align-content: start; gap: 24px; min-width: 0; }
.datasource-cockpit__card { padding: 24px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); min-width: 0; }
h2 { margin: 0 0 18px; color: var(--do-ink); font-size: 18px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.section-heading h2 { margin: 0; }
.source-facts { display: grid; gap: 18px; margin: 0; }
.source-facts div { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 12px; font-size: 13px; }
dt, .muted { color: var(--do-muted); } dd { margin: 0; color: var(--do-ink); overflow-wrap: anywhere; }
.muted { font-size: 13px; line-height: 1.7; }
.source-detail-section { margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--do-line); }
.compact-list { display: grid; gap: 8px; }
.compact-list__item { display: flex; align-items: center; justify-content: space-between; gap: 10px; width: 100%; padding: 14px 0; border: 0; border-bottom: 1px solid var(--do-line); color: var(--do-ink); background: transparent; cursor: pointer; text-align: left; min-width: 0; }
.compact-list__item:hover { color: var(--do-primary-strong); }
.compact-list__item > span { display: grid; gap: 5px; min-width: 0; overflow-wrap: anywhere; }
.compact-list__item strong { font-size: 14px; } .compact-list__item small { color: var(--do-muted); font-size: 12px; }
.source-details summary { cursor: pointer; color: var(--do-muted); font-size: 13px; padding: 8px 0; }
.source-details[open] summary { margin-bottom: 16px; color: var(--do-ink); }
.inline-link { display: inline-flex; align-items: center; gap: 5px; color: var(--do-primary-strong); font-size: 13px; }
@media (max-width: 1150px) { .datasource-cockpit__grid { grid-template-columns: 1fr; } }
</style>
