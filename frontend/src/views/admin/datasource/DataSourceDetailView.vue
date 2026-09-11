<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight, Database, KeyRound, PlugZap, RefreshCw, Sparkles, Table2, Workflow } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  getDatasource,
  getDatasourceReadiness,
  testSavedDatasourceConnection,
  updateDatasourceStatus,
  type DatasourceItem,
  type DatasourceReadiness,
} from '../../../api/admin/datasource'
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
const primaryAction = computed(() => {
  if (!readiness.value) return { key: 'reload', label: '刷新状态' }
  if (!readiness.value.connectionReady) return { key: 'test', label: '测试连接', icon: PlugZap }
  if (datasource.value?.status !== 1) return { key: 'enable', label: '启用数据源', icon: Database }
  if (!readiness.value.metadataReady) return { key: 'collect', label: '开始采集', icon: Workflow }
  if (!readiness.value.governanceReady) return { key: 'governance', label: '处理治理问题', icon: Workflow }
  if (!readiness.value.knowledgeReady) return { key: 'knowledge', label: '进入语义知识', icon: Sparkles }
  if (!readiness.value.permissionReady) return { key: 'access', label: '配置授权', icon: KeyRound }
  if (readiness.value.askable) return { key: 'query', label: '进入智能问数', icon: ArrowRight }
  return { key: 'reload', label: '刷新就绪度', icon: RefreshCw }
})

const lifecycleSteps = computed(() => {
  const current = readiness.value?.stage || ''
  const currentKey = current.includes('CONNECTION') ? 'connection'
    : current.includes('SNAPSHOT') || current.includes('METADATA') ? 'collection'
      : current.includes('GOVERNANCE') ? 'governance'
        : current.includes('KNOWLEDGE') ? 'knowledge'
          : current.includes('PERMISSION') ? 'permission'
            : readiness.value?.askable ? 'askable' : ''
  const values = [
    { key: 'connection', label: '连接', done: Boolean(readiness.value?.connectionReady) },
    { key: 'collection', label: '采集', done: Boolean(readiness.value?.metadataReady) },
    { key: 'governance', label: '治理', done: Boolean(readiness.value?.governanceReady) },
    { key: 'release', label: '快照发布', done: Boolean(readiness.value?.publishedSnapshotId) },
    { key: 'knowledge', label: '知识发布', done: Boolean(readiness.value?.knowledgeReady) },
    { key: 'permission', label: '授权', done: Boolean(readiness.value?.permissionReady) },
    { key: 'askable', label: '可问数', done: Boolean(readiness.value?.askable) },
  ]
  return values.map((item) => ({ ...item, current: item.key === currentKey }))
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
    if (snapshotResult.status === 'fulfilled') snapshots.value = snapshotResult.value.data.records || []
    if (taskResult.status === 'fulfilled') syncTasks.value = taskResult.value.data.records || []
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
  const key = primaryAction.value.key
  if (key === 'test') return testConnection()
  if (key === 'enable') return enableDatasource()
  if (key === 'collect') return startCollection()
  if (key === 'governance') return router.push({ path: '/admin/governance/issues', query: { datasourceId: String(datasourceId.value), snapshotId: latestSnapshot.value?.id ? String(latestSnapshot.value.id) : undefined } })
  if (key === 'knowledge') return router.push({ path: '/admin/semantics/knowledge', query: { datasourceId: String(datasourceId.value) } })
  if (key === 'access') return router.push({ path: '/admin/access', query: { datasourceId: String(datasourceId.value), tab: 'grants' } })
  if (key === 'query') return router.push('/query')
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
            <div v-if="readiness.stage.includes('SNAPSHOT') || readiness.stage.includes('METADATA')" class="stage-callout">
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
                <BusinessStatusBadge :status="snapshot.status" />
              </button>
            </div>
            <EmptyState v-else message="暂无快照，连接正常后可以开始采集。" action-text="开始采集" @action="startCollection" />
          </section>
          <section class="datasource-cockpit__card">
            <div class="section-heading"><div><h2>最近活动</h2><p>采集和快照记录。</p></div></div>
            <ActivityTimeline :items="activityItems" />
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
