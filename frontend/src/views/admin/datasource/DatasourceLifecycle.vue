<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  Activity,
  AlertTriangle,
  BookOpenCheck,
  CheckCircle2,
  CircleDot,
  ClipboardCheck,
  Database,
  FileCheck2,
  GitBranch,
  KeyRound,
  Link2,
  RefreshCw,
  ShieldCheck,
  Table2,
  Users,
} from 'lucide-vue-next'
import {
  getDatasource,
  getDatasourceReadiness,
  listDatasourceAccess,
  type DatasourceAccessItem,
  type DatasourceItem,
  type DatasourceReadiness,
} from '../../../api/admin/datasource'
import {
  getSnapshotDetail,
  listSnapshots,
  listSyncTasks,
  type SnapshotDetail,
  type SnapshotItem,
  type SyncTaskItem,
} from '../../../api/admin/metadata'
import { listQualityIssues, type QualityIssueItem } from '../../../api/admin/governance'
import { listKnowledgeDocs, type KnowledgeDocItem } from '../../../api/admin/knowledge'

interface LifecycleStep {
  key: string
  title: string
  owner: string
  description: string
  actionText: string
  actionPath: string
  ready: boolean
  active: boolean
  icon: Component
}

const route = useRoute()
const datasource = ref<DatasourceItem>()
const readiness = ref<DatasourceReadiness>()
const snapshots = ref<SnapshotItem[]>([])
const snapshotDetail = ref<SnapshotDetail>()
const syncTasks = ref<SyncTaskItem[]>([])
const qualityIssues = ref<QualityIssueItem[]>([])
const knowledgeDocs = ref<KnowledgeDocItem[]>([])
const accessList = ref<DatasourceAccessItem[]>([])
const activeTab = ref('overview')
const loading = ref(false)
const errorMessage = ref('')

const datasourceId = computed(() => Number(route.params.id))
const displayName = computed(() => readiness.value?.datasourceName || datasource.value?.name || `数据源 #${route.params.id}`)
const latestSnapshot = computed(() => snapshots.value[0])
const firstReason = computed(() => readiness.value?.blockReasons?.[0])

const hostText = computed(() => {
  if (!datasource.value) return '-'
  return `${datasource.value.host}:${datasource.value.port}`
})

const statusText = computed(() => datasource.value?.status === 1 ? '启用' : '停用')
const healthText = computed(() => {
  const health = datasource.value?.healthStatus
  if (health === 'UP') return '健康'
  if (health === 'DOWN') return '异常'
  return '未检测'
})

const stageTone = computed(() => {
  if (readiness.value?.askable) return 'success'
  if (readiness.value?.progress && readiness.value.progress >= 60) return 'warning'
  return 'info'
})

const summaryStats = computed(() => [
  { label: '表数量', value: latestSnapshot.value?.tableCount ?? snapshotDetail.value?.tables.length ?? '-' },
  { label: '字段数量', value: latestSnapshot.value?.columnCount ?? snapshotDetail.value?.columns.length ?? '-' },
  { label: '质量评分', value: latestSnapshot.value?.qualityScore ?? '-' },
  { label: '授权用户', value: accessList.value.length },
])

function currentLifecycleStepKey(stage?: string) {
  const stageMap: Record<string, string> = {
    CONNECTION_CHECK_REQUIRED: 'CONNECTION',
    SNAPSHOT_PENDING: 'METADATA',
    GOVERNANCE_BLOCKED: 'GOVERNANCE',
    KNOWLEDGE_PENDING: 'KNOWLEDGE',
    PERMISSION_PENDING: 'PERMISSION',
    READY: 'ASKABLE',
    UNKNOWN: '',
  }
  return stageMap[stage || ''] || ''
}

const lifecycleSteps = computed<LifecycleStep[]>(() => {
  const currentKey = currentLifecycleStepKey(readiness.value?.stage)
  return [
    {
      key: 'CONNECTION',
      title: '接入验证',
      owner: '数据源管理员',
      description: healthText.value,
      actionText: '测试连接',
      actionPath: '/admin/datasources',
      ready: Boolean(readiness.value?.connectionReady),
      active: currentKey === 'CONNECTION',
      icon: Link2,
    },
    {
      key: 'METADATA',
      title: '采集快照',
      owner: '元数据管理员',
      description: latestSnapshot.value ? `v${latestSnapshot.value.snapshotVersion}` : '等待采集',
      actionText: '同步任务',
      actionPath: '/admin/metadata/sync',
      ready: Boolean(readiness.value?.metadataReady),
      active: currentKey === 'METADATA',
      icon: Database,
    },
    {
      key: 'GOVERNANCE',
      title: '治理处理',
      owner: '数据治理人员',
      description: qualityIssues.value.length ? `${qualityIssues.value.length} 个待处理` : '状态可用',
      actionText: '问题清单',
      actionPath: '/admin/governance/issues',
      ready: Boolean(readiness.value?.governanceReady),
      active: currentKey === 'GOVERNANCE',
      icon: ClipboardCheck,
    },
    {
      key: 'KNOWLEDGE',
      title: '语义发布',
      owner: '知识维护人员',
      description: readiness.value?.knowledgeVersion ? `v${readiness.value.knowledgeVersion}` : '未发布',
      actionText: '知识库',
      actionPath: '/admin/knowledge',
      ready: Boolean(readiness.value?.knowledgeReady),
      active: currentKey === 'KNOWLEDGE',
      icon: BookOpenCheck,
    },
    {
      key: 'PERMISSION',
      title: '权限配置',
      owner: '权限管理员',
      description: accessList.value.length ? `${accessList.value.length} 人可用` : '等待授权',
      actionText: '权限中心',
      actionPath: '/admin/permission/access',
      ready: Boolean(readiness.value?.permissionReady),
      active: currentKey === 'PERMISSION',
      icon: KeyRound,
    },
    {
      key: 'ASKABLE',
      title: '开放询问',
      owner: '业务用户',
      description: readiness.value?.askable ? '可询问' : '未开放',
      actionText: '智能问答',
      actionPath: '/query',
      ready: Boolean(readiness.value?.askable),
      active: currentKey === 'ASKABLE',
      icon: ShieldCheck,
    },
    {
      key: 'ARCHIVE',
      title: '持续运营',
      owner: '平台管理员',
      description: '审计与反馈',
      actionText: '审计日志',
      actionPath: '/admin/audit/logs',
      ready: Boolean(readiness.value?.askable),
      active: false,
      icon: FileCheck2,
    },
  ]
})

function stepClass(step: LifecycleStep) {
  if (step.ready) return 'done'
  if (step.active) return 'active'
  return 'pending'
}

function formatTime(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function syncProgress(task: SyncTaskItem) {
  if (!task.progressTotal) return 0
  return Math.min(100, Math.round(((task.progressCurrent || 0) / task.progressTotal) * 100))
}

function statusType(status?: string) {
  if (!status) return 'info'
  if (['SUCCESS', 'PUBLISHED', 'APPROVED', 'RESOLVED'].includes(status)) return 'success'
  if (['RUNNING', 'PENDING', 'PENDING_REVIEW', 'INDEXING', 'OPEN'].includes(status)) return 'warning'
  if (['FAILED', 'REJECTED', 'BLOCKED'].includes(status)) return 'danger'
  return 'info'
}

async function fetchDetail() {
  if (!Number.isFinite(datasourceId.value)) {
    errorMessage.value = '数据源编号无效'
    return
  }

  loading.value = true
  errorMessage.value = ''
  snapshotDetail.value = undefined
  qualityIssues.value = []

  try {
    const [
      datasourceResult,
      readinessResult,
      snapshotResult,
      taskResult,
      knowledgeResult,
      accessResult,
    ] = await Promise.all([
      getDatasource(datasourceId.value),
      getDatasourceReadiness(datasourceId.value),
      listSnapshots({ datasourceId: datasourceId.value, page: 1, size: 5 }),
      listSyncTasks({ datasourceId: datasourceId.value, page: 1, size: 5 }),
      listKnowledgeDocs({ datasourceId: datasourceId.value, page: 1, pageSize: 5 }),
      listDatasourceAccess(datasourceId.value),
    ])

    datasource.value = datasourceResult.data
    readiness.value = readinessResult.data
    snapshots.value = snapshotResult.data?.records || []
    syncTasks.value = taskResult.data?.records || []
    knowledgeDocs.value = knowledgeResult.data?.records || []
    accessList.value = accessResult.data || []

    const snapshotId = latestSnapshot.value?.id
    if (snapshotId) {
      const [detailResult, issueResult] = await Promise.allSettled([
        getSnapshotDetail(snapshotId),
        listQualityIssues(snapshotId, { page: 1, size: 5, status: 'OPEN' }),
      ])

      if (detailResult.status === 'fulfilled') {
        snapshotDetail.value = detailResult.value.data
      }
      if (issueResult.status === 'fulfilled') {
        qualityIssues.value = issueResult.value.data?.records || []
      }
    }
  } catch (error) {
    console.error(error)
    errorMessage.value = '数据源详情加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(fetchDetail)
</script>

<template>
  <main class="datasource-detail-page post-login-page">
    <section class="detail-hero">
      <div class="hero-symbol">
        <Database :size="42" />
      </div>
      <div class="hero-main">
        <div class="hero-kicker">
          <span>数据源详情</span>
          <el-tag :type="stageTone">{{ readiness?.stageLabel || '状态确认中' }}</el-tag>
        </div>
        <h1>{{ displayName }}</h1>
        <div class="hero-meta">
          <span>数据库：{{ datasource?.databaseName || '-' }}</span>
          <span>类型：{{ datasource?.dbType || 'MySQL' }}</span>
          <span>主机：{{ hostText }}</span>
          <span>状态：{{ statusText }} / {{ healthText }}</span>
        </div>
      </div>
      <div class="hero-progress">
        <el-progress
          type="dashboard"
          :percentage="readiness?.progress || 0"
          :width="104"
          :stroke-width="9"
          :color="readiness?.askable ? '#16a34a' : '#2563eb'"
        />
        <span>{{ readiness?.askable ? '可询问' : '推进中' }}</span>
      </div>
    </section>

    <el-result v-if="errorMessage" icon="error" title="加载失败" :sub-title="errorMessage">
      <template #extra>
        <el-button type="primary" @click="fetchDetail">重新加载</el-button>
      </template>
    </el-result>

    <template v-else>
      <section v-loading="loading" class="process-section">
        <div class="process-header">
          <div>
            <p>当前阶段</p>
            <h2>{{ readiness?.stageLabel || '状态确认中' }}</h2>
          </div>
          <el-button :icon="RefreshCw" @click="fetchDetail">刷新</el-button>
        </div>

        <div class="process-track">
          <div
            v-for="(step, index) in lifecycleSteps"
            :key="step.key"
            class="process-step"
            :class="stepClass(step)"
          >
            <div class="step-line" :class="{ filled: index === 0 || lifecycleSteps[index - 1]?.ready }" />
            <div class="step-node">
              <CheckCircle2 v-if="step.ready" :size="20" />
              <CircleDot v-else-if="step.active" :size="20" />
              <component :is="step.icon" v-else :size="20" />
            </div>
            <h3>{{ step.title }}</h3>
            <p>{{ step.owner }}</p>
            <span>{{ step.description }}</span>
            <RouterLink :to="step.actionPath">{{ step.actionText }}</RouterLink>
          </div>
        </div>
      </section>

      <section class="detail-tabs-section">
        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="概览" name="overview">
            <div class="overview-grid">
              <div class="status-panel">
                <div class="panel-title">
                  <AlertTriangle :size="18" />
                  <span>{{ firstReason ? '当前阻塞项' : '当前状态' }}</span>
                </div>
                <h3>{{ firstReason?.message || '数据源已满足询问条件' }}</h3>
                <p>{{ firstReason?.ownerRole || '平台持续记录查询、权限与治理反馈' }}</p>
                <RouterLink v-if="firstReason?.actionPath" :to="firstReason.actionPath">
                  {{ firstReason.actionText }}
                </RouterLink>
              </div>

              <div class="stats-grid">
                <div v-for="item in summaryStats" :key="item.label" class="stat-item">
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="元数据" name="metadata">
            <div class="tab-layout">
              <div class="info-panel">
                <div class="panel-title">
                  <Table2 :size="18" />
                  <span>最新快照</span>
                </div>
                <dl>
                  <div>
                    <dt>版本</dt>
                    <dd>{{ latestSnapshot ? `v${latestSnapshot.snapshotVersion}` : '-' }}</dd>
                  </div>
                  <div>
                    <dt>状态</dt>
                    <dd>
                      <el-tag :type="statusType(latestSnapshot?.status)">{{ latestSnapshot?.status || '-' }}</el-tag>
                    </dd>
                  </div>
                  <div>
                    <dt>生成时间</dt>
                    <dd>{{ formatTime(latestSnapshot?.createdAt) }}</dd>
                  </div>
                </dl>
              </div>

              <div class="list-panel">
                <div class="panel-title">
                  <Activity :size="18" />
                  <span>同步任务</span>
                </div>
                <div v-if="syncTasks.length" class="task-list">
                  <div v-for="task in syncTasks" :key="task.id" class="task-row">
                    <div>
                      <strong>{{ task.triggerType }}</strong>
                      <span>{{ formatTime(task.startedAt) }}</span>
                    </div>
                    <el-progress :percentage="syncProgress(task)" :show-text="false" :stroke-width="6" />
                    <el-tag :type="statusType(task.status)">{{ task.status }}</el-tag>
                  </div>
                </div>
                <el-empty v-else description="暂无同步任务" />
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="治理" name="governance">
            <div class="list-panel full">
              <div class="panel-title">
                <ClipboardCheck :size="18" />
                <span>待处理问题</span>
              </div>
              <div v-if="qualityIssues.length" class="issue-list">
                <div v-for="issue in qualityIssues" :key="issue.id" class="issue-row">
                  <div>
                    <strong>{{ issue.tableName }}{{ issue.columnName ? `.${issue.columnName}` : '' }}</strong>
                    <span>{{ issue.issueDescription }}</span>
                  </div>
                  <el-tag :type="statusType(issue.severity)">{{ issue.severity }}</el-tag>
                </div>
              </div>
              <el-empty v-else description="暂无待处理治理问题" />
            </div>
          </el-tab-pane>

          <el-tab-pane label="语义知识" name="knowledge">
            <div class="list-panel full">
              <div class="panel-title">
                <BookOpenCheck :size="18" />
                <span>知识文档</span>
              </div>
              <div v-if="knowledgeDocs.length" class="doc-list">
                <div v-for="doc in knowledgeDocs" :key="doc.id" class="doc-row">
                  <div>
                    <strong>{{ doc.title }}</strong>
                    <span>v{{ doc.currentVersion }} · {{ formatTime(doc.updatedAt) }}</span>
                  </div>
                  <el-tag :type="statusType(doc.status)">{{ doc.status }}</el-tag>
                </div>
              </div>
              <el-empty v-else description="暂无知识文档" />
            </div>
          </el-tab-pane>

          <el-tab-pane label="权限" name="permission">
            <div class="tab-layout">
              <div class="info-panel">
                <div class="panel-title">
                  <KeyRound :size="18" />
                  <span>可访问用户</span>
                </div>
                <strong class="big-number">{{ accessList.length }}</strong>
                <p>最终是否可问，还会叠加角色策略、临时授权、行列权限和数据状态。</p>
              </div>
              <div class="list-panel">
                <div class="panel-title">
                  <Users :size="18" />
                  <span>直接授权</span>
                </div>
                <div v-if="accessList.length" class="access-list">
                  <div v-for="item in accessList.slice(0, 8)" :key="item.id" class="access-row">
                    <span>{{ item.realName || item.username || `用户 ${item.userId}` }}</span>
                    <small>{{ item.expiresAt ? `至 ${formatTime(item.expiresAt)}` : '长期' }}</small>
                  </div>
                </div>
                <el-empty v-else description="暂无直接授权" />
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="运营" name="operation">
            <div class="tab-layout">
              <div class="info-panel">
                <div class="panel-title">
                  <GitBranch :size="18" />
                  <span>版本线索</span>
                </div>
                <dl>
                  <div>
                    <dt>快照版本</dt>
                    <dd>{{ readiness?.snapshotVersion ? `v${readiness.snapshotVersion}` : '-' }}</dd>
                  </div>
                  <div>
                    <dt>知识版本</dt>
                    <dd>{{ readiness?.knowledgeVersion ? `v${readiness.knowledgeVersion}` : '-' }}</dd>
                  </div>
                  <div>
                    <dt>可询问</dt>
                    <dd>{{ readiness?.askable ? '是' : '否' }}</dd>
                  </div>
                </dl>
              </div>
              <div class="status-panel subtle">
                <div class="panel-title">
                  <Activity :size="18" />
                  <span>运营判断</span>
                </div>
                <h3>{{ readiness?.askable ? '进入持续运营' : '仍在上线流程中' }}</h3>
                <p>{{ readiness?.askable ? '可以通过审计、慢查询、反馈和治理事件继续迭代。' : '优先处理流程线上的当前阻塞项。' }}</p>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>
  </main>
</template>

<style scoped>
.datasource-detail-page {
  display: grid;
  gap: 18px;
}

.detail-hero {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 24px;
  min-height: 168px;
  padding: 28px 34px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background:
    linear-gradient(165deg, rgba(255, 255, 255, 0.96) 0%, rgba(255, 255, 255, 0.96) 58%, rgba(232, 240, 255, 0.92) 58%),
    #f8fbff;
  box-shadow: 0 16px 40px rgba(37, 99, 235, 0.08);
}

.hero-symbol {
  display: grid;
  place-items: center;
  width: 96px;
  height: 96px;
  color: #2563eb;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  box-shadow: inset 0 -10px 0 rgba(37, 99, 235, 0.08);
}

.hero-main {
  min-width: 0;
}

.hero-kicker {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  color: #475569;
  font-size: 14px;
}

.hero-main h1 {
  margin: 0;
  color: #0f172a;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.25;
}

.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 24px;
  margin-top: 18px;
  color: #334155;
  font-size: 14px;
}

.hero-progress {
  display: grid;
  justify-items: center;
  gap: 6px;
  color: #1e40af;
  font-size: 13px;
  font-weight: 600;
}

.process-section,
.detail-tabs-section {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06);
}

.process-section {
  padding: 24px 18px 26px;
}

.process-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 6px 22px;
}

.process-header p {
  margin: 0 0 4px;
  color: #64748b;
  font-size: 13px;
}

.process-header h2 {
  margin: 0;
  color: #0f172a;
  font-size: 22px;
  line-height: 1.25;
}

.process-track {
  display: grid;
  grid-template-columns: repeat(7, minmax(116px, 1fr));
  align-items: start;
  overflow-x: auto;
  padding: 8px 0 2px;
}

.process-step {
  position: relative;
  display: grid;
  justify-items: center;
  min-width: 116px;
  padding: 0 8px;
  text-align: center;
}

.step-line {
  position: absolute;
  top: 16px;
  left: 0;
  width: 100%;
  height: 2px;
  background: #cbd5e1;
}

.step-line.filled {
  background: #22c55e;
}

.step-node {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  color: #64748b;
  border: 2px solid #cbd5e1;
  border-radius: 999px;
  background: #ffffff;
}

.process-step.done .step-node {
  color: #ffffff;
  border-color: #22c55e;
  background: #22c55e;
}

.process-step.active .step-node {
  color: #ffffff;
  border-color: #f59e0b;
  background: #f59e0b;
}

.process-step h3 {
  margin: 16px 0 6px;
  color: #0f172a;
  font-size: 15px;
  line-height: 1.25;
}

.process-step.done h3 {
  color: #15803d;
}

.process-step.active h3 {
  color: #b45309;
}

.process-step p,
.process-step span {
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.process-step a {
  margin-top: 8px;
  color: #2563eb;
  font-size: 12px;
  text-decoration: none;
}

.detail-tabs-section {
  padding: 0 24px 24px;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.overview-grid,
.tab-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
  gap: 18px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.status-panel,
.info-panel,
.list-panel,
.stat-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}

.status-panel,
.info-panel,
.list-panel {
  padding: 18px;
}

.status-panel.subtle {
  background: #f8fafc;
}

.status-panel h3 {
  margin: 14px 0 8px;
  color: #0f172a;
  font-size: 18px;
  line-height: 1.45;
}

.status-panel p,
.info-panel p {
  margin: 0;
  color: #64748b;
  line-height: 1.7;
}

.status-panel a {
  display: inline-flex;
  margin-top: 14px;
  color: #2563eb;
  text-decoration: none;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1e40af;
  font-weight: 700;
}

.stat-item {
  display: grid;
  gap: 8px;
  padding: 18px;
}

.stat-item span {
  color: #64748b;
  font-size: 13px;
}

.stat-item strong,
.big-number {
  color: #0f172a;
  font-size: 30px;
  line-height: 1;
}

dl {
  display: grid;
  gap: 14px;
  margin: 18px 0 0;
}

dl div {
  display: grid;
  grid-template-columns: 80px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
}

dt {
  color: #64748b;
}

dd {
  min-width: 0;
  margin: 0;
  color: #0f172a;
  font-weight: 600;
}

.list-panel.full {
  min-height: 260px;
}

.task-list,
.issue-list,
.doc-list,
.access-list {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

.task-row,
.issue-row,
.doc-row,
.access-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid #e2e8f0;
}

.task-row {
  grid-template-columns: minmax(0, 1fr) 120px auto;
}

.task-row:last-child,
.issue-row:last-child,
.doc-row:last-child,
.access-row:last-child {
  border-bottom: 0;
}

.task-row div,
.issue-row div,
.doc-row div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.task-row strong,
.issue-row strong,
.doc-row strong,
.access-row span {
  overflow: hidden;
  color: #0f172a;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-row span,
.issue-row span,
.doc-row span,
.access-row small {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1120px) {
  .detail-hero {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .hero-progress {
    grid-column: 1 / -1;
    justify-self: start;
  }

  .overview-grid,
  .tab-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .detail-hero {
    grid-template-columns: 1fr;
    padding: 22px;
  }

  .hero-symbol {
    width: 72px;
    height: 72px;
  }

  .hero-main h1 {
    font-size: 22px;
  }

  .detail-tabs-section {
    padding: 0 14px 18px;
  }

  .stats-grid {
    grid-template-columns: 1fr;
  }

  .task-row,
  .issue-row,
  .doc-row,
  .access-row {
    grid-template-columns: 1fr;
  }
}
</style>
