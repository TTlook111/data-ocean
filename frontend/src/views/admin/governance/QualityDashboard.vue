<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardCheck,
  Database,
  FileWarning,
  GitBranch,
  Play,
  ShieldCheck,
} from 'lucide-vue-next'
import {
  listQualityIssues,
  listQualityRules,
  triggerQualityCheck,
  updateRuleEnabled,
  type QualityCheckResult,
  type QualityIssueItem,
  type QualityRule,
} from '../../../api/admin/governance'
import { listSnapshots } from '../../../api/admin/metadata'
import { qualityDimensionLabel, severityLabel } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import ErrorState from '../../../components/common/ErrorState.vue'

interface SnapshotOption {
  id: number
  datasourceName?: string
  snapshotVersion: number
  qualityScore?: number
  tableCount?: number
  columnCount?: number
  status?: string
  createdAt?: string
}

const loading = ref(false)
const checkLoading = ref(false)
const selectedSnapshotId = ref<number | undefined>()
const snapshots = ref<SnapshotOption[]>([])
const checkResult = ref<QualityCheckResult | null>(null)
const rules = ref<QualityRule[]>([])
const issues = ref<QualityIssueItem[]>([])
const unresolvedCounts = ref({ OPEN: 0, CONFIRMED: 0, REOPENED: 0 })
const pageError = ref('')
const issuesError = ref('')
const rulesError = ref('')
let snapshotRequestId = 0
let issueRequestId = 0
let disposed = false
const adminContext = useAdminContextStore()
const iamS1 = useIamS1Store()

/**
 * 执行质量检查要求 `governance:check` 与**当前数据源负责范围**在同一条启用绑定上同时成立。
 * 前端只控制按钮可见性，后端仍会独立拒绝；无能力时给出中文原因而不是静默失败。
 */
const canRunCheck = computed(() =>
  iamS1.canOnDatasource('governance:check', adminContext.datasourceId || undefined),
)
const router = useRouter()

const selectedSnapshot = computed(() => snapshots.value.find((item) => item.id === selectedSnapshotId.value))
const latestScore = computed(() => checkResult.value?.qualityScore ?? selectedSnapshot.value?.qualityScore)
const enabledRules = computed(() => rules.value.filter((item) => item.enabled === 1))
const highIssues = computed(() => issues.value.filter((item) => item.severity === 'HIGH'))
const unresolvedIssueTotal = computed(() => unresolvedCounts.value.OPEN + unresolvedCounts.value.CONFIRMED + unresolvedCounts.value.REOPENED)
const hasUnresolvedIssues = computed(() => unresolvedIssueTotal.value > 0)
const canReturnToRelease = computed(() => Boolean(
  !issuesError.value && !loading.value && selectedSnapshot.value
  && ['APPROVED', 'PUBLISHED', 'EXPIRED'].includes(String(selectedSnapshot.value.status))
  && !hasUnresolvedIssues.value
  && (checkResult.value || latestScore.value !== undefined),
))

function openIssueCenter() {
  if (!selectedSnapshotId.value) return
  router.push({
    path: '/admin/governance/issues',
    query: {
      datasourceId: String(adminContext.datasourceId || ''),
      snapshotId: String(selectedSnapshotId.value),
    },
  })
}

function openReleaseFlow() {
  if (!selectedSnapshotId.value) return
  router.push({
    path: '/admin/releases',
    query: {
      datasourceId: String(adminContext.datasourceId || ''),
      snapshotId: String(selectedSnapshotId.value),
      tab: 'candidates',
    },
  })
}

const flowSteps = computed(() => [
  {
    title: '选定数据源',
    icon: Database,
    done: Boolean(adminContext.datasourceId),
    text: adminContext.currentDatasource?.name || selectedSnapshot.value?.datasourceName || '未选择',
  },
  {
    title: '选定快照',
    icon: GitBranch,
    done: Boolean(selectedSnapshotId.value),
    text: selectedSnapshot.value ? `v${selectedSnapshot.value.snapshotVersion}` : '未选择',
  },
  {
    title: '执行校验',
    icon: ClipboardCheck,
    done: Boolean(checkResult.value || latestScore.value !== undefined),
    text: latestScore.value !== undefined ? `${latestScore.value} 分` : '等待执行',
  },
  {
    title: '处理问题',
    icon: ShieldCheck,
    done: !hasUnresolvedIssues.value && Boolean(selectedSnapshotId.value),
    text: hasUnresolvedIssues.value ? `${unresolvedIssueTotal.value} 个未解决` : '暂无阻塞',
  },
])

function scoreColor(score?: number): string {
  if (score === undefined) return '#64748b'
  if (score >= 80) return '#16a34a'
  if (score >= 60) return '#d97706'
  return '#dc2626'
}

function scoreLabel(score?: number) {
  if (score === undefined) return '待校验'
  if (score >= 80) return '质量稳定'
  if (score >= 60) return '需要治理'
  return '优先处理'
}

function statusType(value?: string) {
  if (!value) return 'info'
  if (['HIGH', 'FAILED', 'BLOCKED'].includes(value)) return 'danger'
  if (['MEDIUM', 'OPEN', 'PENDING'].includes(value)) return 'warning'
  if (['LOW', 'SUCCESS', 'RESOLVED'].includes(value)) return 'success'
  return 'info'
}

async function fetchSnapshots() {
  pageError.value = ''
  const currentRequest = ++snapshotRequestId
  const datasourceId = adminContext.datasourceId
  issues.value = []
  unresolvedCounts.value = { OPEN: 0, CONFIRMED: 0, REOPENED: 0 }
  if (!datasourceId) {
    snapshots.value = []
    selectedSnapshotId.value = undefined
    adminContext.selectSnapshot(undefined)
    return
  }
  const res = await listSnapshots({ datasourceId, page: 1, size: 50 })
  if (disposed || currentRequest !== snapshotRequestId || datasourceId !== adminContext.datasourceId) return
  snapshots.value = res.data?.records ?? []

  if (adminContext.snapshotId && snapshots.value.some((item) => item.id === adminContext.snapshotId)) {
    selectedSnapshotId.value = adminContext.snapshotId
  } else {
    selectedSnapshotId.value = snapshots.value[0]?.id
  }

  adminContext.selectSnapshot(selectedSnapshotId.value)
  await fetchIssues()
}

async function fetchRules() {
  rulesError.value = ''
  if (!iamS1.hasGlobal('governance:rule:view')) {
    rules.value = []
    rulesError.value = '当前账号没有查看质量规则的功能权限。'
    return
  }
  try {
    const res = await listQualityRules()
    rules.value = res.data ?? []
  } catch {
    rulesError.value = '质量规则读取失败，请重试。'
  }
}

async function fetchIssues() {
  const currentRequest = ++issueRequestId
  issuesError.value = ''
  const snapshotId = selectedSnapshotId.value
  if (!snapshotId) {
    issues.value = []
    unresolvedCounts.value = { OPEN: 0, CONFIRMED: 0, REOPENED: 0 }
    return
  }
  try {
  const [openResult, confirmedResult, reopenedResult] = await Promise.all([
    listQualityIssues(snapshotId, { page: 1, size: 6, status: 'OPEN' }),
    listQualityIssues(snapshotId, { page: 1, size: 1, status: 'CONFIRMED' }),
    listQualityIssues(snapshotId, { page: 1, size: 1, status: 'REOPENED' }),
  ])
  if (disposed || currentRequest !== issueRequestId || snapshotId !== selectedSnapshotId.value) return
  issues.value = openResult.data?.records ?? []
  unresolvedCounts.value = {
    OPEN: openResult.data?.total ?? 0,
    CONFIRMED: confirmedResult.data?.total ?? 0,
    REOPENED: reopenedResult.data?.total ?? 0,
  }
  } catch {
    if (disposed || currentRequest !== issueRequestId || snapshotId !== selectedSnapshotId.value) return
    issuesError.value = '治理问题读取失败，无法确认是否有待处理事项。'
  }
}

async function runCheck() {
  if (!canRunCheck.value) {
    ElMessage.warning('没有“执行质量检查”能力：需要 IAM-SIMPLE-1 角色包含该功能并负责当前数据源。')
    return
  }
  if (!selectedSnapshotId.value) {
    ElMessage.warning('请选择快照')
    return
  }

  checkResult.value = null
  checkLoading.value = true
  try {
    const res = await triggerQualityCheck(selectedSnapshotId.value)
    checkResult.value = res.data ?? null
    await fetchSnapshots()
    ElMessage.success(`质量校验完成，综合得分 ${res.data?.qualityScore}`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '校验失败')
  } finally {
    checkLoading.value = false
  }
}

async function toggleRule(rule: QualityRule) {
  if (!iamS1.systemAdmin) return
  const nextEnabled = rule.enabled !== 1
  await updateRuleEnabled(rule.id, nextEnabled)
  rule.enabled = nextEnabled ? 1 : 0
}

async function load() {
  loading.value = true
  pageError.value = ''
  try {
    await adminContext.initialize()
    await Promise.all([fetchSnapshots(), fetchRules()])
  } catch {
    pageError.value = '治理范围或快照读取失败，请重试。'
  } finally {
    loading.value = false
  }
}
onMounted(load)

onBeforeUnmount(() => {
  disposed = true
  snapshotRequestId++
  issueRequestId++
})

watch(
  () => adminContext.snapshotId,
  async (snapshotId) => {
    if (selectedSnapshotId.value === snapshotId) return
    selectedSnapshotId.value = snapshotId
    checkResult.value = null
    await fetchIssues()
  },
)

watch(
  () => adminContext.datasourceId,
  async () => {
    checkResult.value = null
    try { await fetchSnapshots() } catch { pageError.value = '当前数据源的快照读取失败，请重试。' }
  },
)
</script>

<template>
  <main v-loading="loading" class="quality-page post-login-page">
    <TaskPageHeader title="治理总览" description="查看当前快照的质量结果，优先处理影响使用的问题。" />
    <ErrorState v-if="pageError" :message="pageError" @retry="load" />
    <template v-else>
    <section class="quality-hero">
      <div class="quality-title">
        <span>治理质量</span>
        <h2>{{ selectedSnapshot?.datasourceName || adminContext.currentDatasource?.name || '选择数据源后开始治理' }}</h2>
        <p>{{ selectedSnapshot ? '当前快照 v' + selectedSnapshot.snapshotVersion : '请在上方选择数据源和快照' }} · {{ checkResult ? '本次校验结果' : '显示快照记录的综合评分' }}</p>
      </div>
      <div class="score-summary" :style="{ color: scoreColor(latestScore) }">
        <strong>{{ latestScore ?? '--' }}</strong>
        <span>{{ scoreLabel(latestScore) }}</span>
      </div>
    </section>

    <section class="context-panel">
      <el-button
        type="primary"
        :icon="Play"
        :loading="checkLoading"
        :disabled="!canRunCheck || !selectedSnapshotId"
        @click="runCheck"
      >
        执行质量校验
      </el-button>
      <span v-if="!canRunCheck" class="muted-text">
        没有“执行质量检查”能力：需要 IAM-SIMPLE-1 角色包含该功能并负责当前数据源。
      </span>
      <el-button v-if="hasUnresolvedIssues" @click="openIssueCenter">进入问题中心</el-button>
      <el-button v-else-if="canReturnToRelease" @click="openReleaseFlow">返回版本发布</el-button>
    </section>

    <details class="quality-details">
      <summary>查看治理流程</summary>
      <section class="flow-panel">
      <div v-for="(step, index) in flowSteps" :key="step.title" class="flow-step" :class="{ done: step.done }">
        <div class="flow-line" :class="{ filled: index === 0 || flowSteps[index - 1]?.done }" />
        <div class="flow-node">
          <CheckCircle2 v-if="step.done" :size="19" />
          <component :is="step.icon" v-else :size="19" />
        </div>
        <strong>{{ step.title }}</strong>
        <span>{{ step.text }}</span>
      </div>
      </section>
    </details>

    <section class="governance-layout">
      <div class="result-area">
        <div class="area-header">
          <div>
            <span>校验结果</span>
            <h3>{{ checkResult ? '本次校验' : '当前快照' }}</h3>
          </div>
          <el-tag v-if="selectedSnapshotId && !issuesError" :type="highIssues.length ? 'danger' : 'success'">
            {{ highIssues.length ? `${highIssues.length} 个高危` : '无高危' }}
          </el-tag>
        </div>

        <div v-if="checkResult" class="dimension-grid">
          <div v-for="(score, dim) in checkResult.dimensionScores" :key="dim" class="dimension-item">
            <strong :style="{ color: scoreColor(score) }">{{ score }}</strong>
            <span>{{ qualityDimensionLabel(String(dim)) }}</span>
          </div>
        </div>
        <div v-else class="empty-state">
          <ClipboardCheck :size="28" />
          <span>上方评分来自快照记录。本页执行校验后展示本次维度得分。</span>
        </div>

        <div v-if="checkResult" class="issue-strip">
          <span>问题分布</span>
          <el-tag type="danger" size="small">高 {{ checkResult?.issueCount.HIGH || highIssues.length }}</el-tag>
          <el-tag type="warning" size="small">中 {{ checkResult?.issueCount.MEDIUM || 0 }}</el-tag>
          <el-tag size="small">低 {{ checkResult?.issueCount.LOW || 0 }}</el-tag>
        </div>
      </div>

      <div class="issue-area">
        <div class="area-header">
          <div>
            <span>待处理问题</span>
            <h3>{{ issuesError ? '问题状态暂不可用' : !selectedSnapshotId ? '请先选择快照' : issues.length ? '优先处理这些项' : '暂无待处理项' }}</h3>
          </div>
          <div class="issue-header-actions">
            <el-button v-if="hasUnresolvedIssues" link type="primary" @click="openIssueCenter">查看全部</el-button>
            <FileWarning :size="20" />
          </div>
        </div>

        <ErrorState v-if="issuesError" :message="issuesError" @retry="fetchIssues" />
        <div v-else-if="issues.length" class="issue-list">
          <div v-for="issue in issues" :key="issue.id" class="issue-item">
            <div>
              <strong>{{ issue.tableName }}{{ issue.columnName ? `.${issue.columnName}` : '' }}</strong>
              <span>{{ issue.issueDescription }}</span>
            </div>
            <el-tag :type="statusType(issue.severity)" size="small">{{ severityLabel(issue.severity) }}</el-tag>
          </div>
        </div>
        <div v-else class="empty-state">
          <ShieldCheck :size="28" />
          <span>{{ selectedSnapshotId ? '当前快照没有打开状态的问题' : '选择快照后查看待处理问题' }}</span>
        </div>
      </div>
    </section>

    <details class="rules-area quality-details">
      <summary>质量规则 · {{ rulesError ? '暂不可用' : enabledRules.length + ' / ' + rules.length + ' 已启用' }} · 展开查看</summary>
      <ErrorState v-if="rulesError" :message="rulesError" @retry="fetchRules" />
      <template v-else>
      <div class="area-header">
        <div>
          <span>质量规则</span>
          <h3>{{ enabledRules.length }} / {{ rules.length }} 已启用</h3>
        </div>
        <AlertTriangle :size="20" />
      </div>

      <div class="rules-grid">
        <div v-for="rule in rules" :key="rule.id" class="rule-item">
          <div class="rule-main">
            <strong>{{ rule.ruleName }}</strong>
            <span>{{ rule.description }}</span>
          </div>
          <div class="rule-meta">
            <el-tag size="small">{{ qualityDimensionLabel(rule.dimension) }}</el-tag>
            <el-tag :type="statusType(rule.severity)" size="small">{{ severityLabel(rule.severity) }}</el-tag>
            <span>-{{ rule.deductionPoints }}</span>
            <el-switch :model-value="rule.enabled === 1" size="small" :disabled="!iamS1.systemAdmin" :title="iamS1.systemAdmin ? '' : '只有系统管理员可以启停全局规则'" @change="toggleRule(rule)" />
          </div>
        </div>
      </div>
      </template>
    </details>
    </template>
  </main>
</template>

<style scoped>
.quality-details summary { cursor: pointer; color: var(--do-ink); font-size: 14px; padding: 12px 0; }
.quality-details[open] summary { margin-bottom: 16px; }
.quality-page {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.quality-hero,
.context-panel,
.flow-panel,
.result-area,
.issue-area,
.rules-area {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.05);
}

.quality-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 20px;
  padding: 24px;
}

.quality-title {
  min-width: 0;
}

.quality-title span,
.area-header span {
  color: #64748b;
  font-size: 13px;
}

.quality-title h2,
.area-header h3 {
  margin: 6px 0 0;
  color: #0f172a;
  line-height: 1.3;
}

.quality-title h2 {
  overflow: hidden;
  font-size: 24px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quality-title p {
  margin: 10px 0 0;
  color: #64748b;
}

.score-summary {
  display: grid;
  justify-items: center;
  min-width: 110px;
}

.score-summary strong {
  font-size: 44px;
  line-height: 1;
}

.score-summary span {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 700;
}

.context-panel {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  padding: 16px;
}

.snapshot-picker {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.snapshot-picker span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #475569;
  font-weight: 700;
}

.flow-panel {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  padding: 22px 14px;
}

.flow-step {
  position: relative;
  display: grid;
  justify-items: center;
  gap: 7px;
  min-width: 0;
  text-align: center;
}

.flow-line {
  position: absolute;
  top: 16px;
  left: 0;
  width: 100%;
  height: 2px;
  background: #cbd5e1;
}

.flow-line.filled {
  background: #22c55e;
}

.flow-node {
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

.flow-step.done .flow-node {
  color: #ffffff;
  border-color: #22c55e;
  background: #22c55e;
}

.flow-step strong {
  color: #0f172a;
}

.flow-step span {
  max-width: 100%;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.governance-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.result-area,
.issue-area,
.rules-area {
  padding: 18px;
}

.area-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #2563eb;
}

.dimension-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 18px;
}

.dimension-item {
  display: grid;
  gap: 6px;
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.dimension-item strong {
  font-size: 28px;
  line-height: 1;
}

.dimension-item span {
  color: #64748b;
  font-size: 12px;
}

.issue-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 18px;
  color: #475569;
}

.issue-list,
.rules-grid {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

.issue-item,
.rule-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid #e2e8f0;
}

.issue-item:last-child,
.rule-item:last-child {
  border-bottom: 0;
}

.issue-item div,
.rule-main {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.issue-item strong,
.rule-main strong {
  overflow: hidden;
  color: #0f172a;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.issue-item span,
.rule-main span {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  color: #64748b;
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 10px;
  min-height: 150px;
  padding: 32px;
  color: #64748b;
}

</style>
