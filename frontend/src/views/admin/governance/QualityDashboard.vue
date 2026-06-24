<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
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
const adminContext = useAdminContextStore()

const selectedSnapshot = computed(() => snapshots.value.find((item) => item.id === selectedSnapshotId.value))
const latestScore = computed(() => checkResult.value?.qualityScore ?? selectedSnapshot.value?.qualityScore)
const enabledRules = computed(() => rules.value.filter((item) => item.enabled === 1))
const highIssues = computed(() => issues.value.filter((item) => item.severity === 'HIGH'))

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
    done: issues.value.length === 0 && Boolean(selectedSnapshotId.value),
    text: issues.value.length ? `${issues.value.length} 个待处理` : '暂无阻塞',
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

function formatSnapshotLabel(item: SnapshotOption) {
  const score = item.qualityScore === undefined ? '' : ` / ${item.qualityScore} 分`
  return `${item.datasourceName || '数据源'} · v${item.snapshotVersion}${score}`
}

async function fetchSnapshots() {
  issues.value = []
  const res = await listSnapshots({ datasourceId: adminContext.datasourceId, page: 1, size: 50 })
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
  const res = await listQualityRules()
  rules.value = res.data ?? []
}

async function fetchIssues() {
  if (!selectedSnapshotId.value) {
    issues.value = []
    return
  }
  const res = await listQualityIssues(selectedSnapshotId.value, { page: 1, size: 6, status: 'OPEN' })
  issues.value = res.data?.records ?? []
}

async function runCheck() {
  if (!selectedSnapshotId.value) {
    ElMessage.warning('请选择快照')
    return
  }

  checkLoading.value = true
  try {
    const res = await triggerQualityCheck(selectedSnapshotId.value)
    checkResult.value = res.data ?? null
    await fetchIssues()
    ElMessage.success(`质量校验完成，综合得分 ${res.data?.qualityScore}`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '校验失败')
  } finally {
    checkLoading.value = false
  }
}

async function handleSnapshotChange(id?: number) {
  adminContext.selectSnapshot(id)
  checkResult.value = null
  await fetchIssues()
}

async function toggleRule(rule: QualityRule) {
  const nextEnabled = rule.enabled !== 1
  await updateRuleEnabled(rule.id, nextEnabled)
  rule.enabled = nextEnabled ? 1 : 0
}

onMounted(async () => {
  loading.value = true
  try {
    await adminContext.initialize()
    await Promise.all([fetchSnapshots(), fetchRules()])
  } finally {
    loading.value = false
  }
})

watch(
  () => adminContext.snapshotId,
  async (snapshotId) => {
    if (!snapshotId || selectedSnapshotId.value === snapshotId) return
    selectedSnapshotId.value = snapshotId
    checkResult.value = null
    await fetchIssues()
  },
)

watch(
  () => adminContext.datasourceId,
  async () => {
    checkResult.value = null
    await fetchSnapshots()
  },
)
</script>

<template>
  <main v-loading="loading" class="quality-page post-login-page">
    <section class="quality-hero">
      <div class="quality-title">
        <span>治理质量</span>
        <h2>{{ selectedSnapshot?.datasourceName || adminContext.currentDatasource?.name || '选择数据源后开始治理' }}</h2>
        <p>围绕同一个数据源快照完成校验、规则启停和问题处理。</p>
      </div>
      <div class="score-summary" :style="{ color: scoreColor(latestScore) }">
        <strong>{{ latestScore ?? '--' }}</strong>
        <span>{{ scoreLabel(latestScore) }}</span>
      </div>
    </section>

    <section class="context-panel">
      <div class="snapshot-picker">
        <span>
          <GitBranch :size="16" />
          快照
        </span>
        <el-select
          v-model="selectedSnapshotId"
          placeholder="选择快照"
          filterable
          @change="handleSnapshotChange"
        >
          <el-option
            v-for="item in snapshots"
            :key="item.id"
            :value="item.id"
            :label="formatSnapshotLabel(item)"
          />
        </el-select>
      </div>
      <el-button type="primary" :icon="Play" :loading="checkLoading" @click="runCheck">
        执行质量校验
      </el-button>
    </section>

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

    <section class="governance-layout">
      <div class="result-area">
        <div class="area-header">
          <div>
            <span>校验结果</span>
            <h3>{{ checkResult ? '本次校验' : '当前快照' }}</h3>
          </div>
          <el-tag :type="highIssues.length ? 'danger' : 'success'">
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
          <span>执行质量校验后展示维度得分</span>
        </div>

        <div class="issue-strip">
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
            <h3>{{ issues.length ? '优先处理这些项' : '暂无待处理项' }}</h3>
          </div>
          <FileWarning :size="20" />
        </div>

        <div v-if="issues.length" class="issue-list">
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
          <span>当前快照没有打开状态的问题</span>
        </div>
      </div>
    </section>

    <section class="rules-area">
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
            <el-switch :model-value="rule.enabled === 1" size="small" @change="toggleRule(rule)" />
          </div>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
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
  display: grid;
  grid-template-columns: minmax(240px, 420px) auto;
  align-items: end;
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

@media (max-width: 980px) {
  .quality-hero,
  .context-panel,
  .governance-layout {
    grid-template-columns: 1fr;
  }

  .flow-panel {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 22px;
  }

  .flow-line {
    display: none;
  }
}

@media (max-width: 640px) {
  .quality-title h2 {
    white-space: normal;
  }

  .flow-panel,
  .dimension-grid {
    grid-template-columns: 1fr;
  }

  .issue-item,
  .rule-item {
    grid-template-columns: 1fr;
  }

  .rule-meta {
    justify-content: flex-start;
  }
}
</style>
