<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, ArrowRight, ClipboardCheck, RefreshCw } from 'lucide-vue-next'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import BusinessStatusBadge from '../../../components/admin/BusinessStatusBadge.vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import {
  getLatestQualityCheckBatch,
  getQualityRuleSummary,
  getSnapshotQualityIssueSummary,
  triggerQualityCheck,
  type QualityCheckBatch,
  type QualityIssueSummary,
  type QualityRuleSummary,
} from '../../../api/admin/governance'
import { qualityDimensionLabel, severityLabel, snapshotStatusLabel } from '../../../utils/enumLabels'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import { buildContextQuery } from '../../../utils/adminNavigation'

const context = useAdminContextStore()
const iamS1 = useIamS1Store()
const route = useRoute()
const router = useRouter()

const latestBatch = ref<QualityCheckBatch | null>(null)
const issueSummary = ref<QualityIssueSummary | null>(null)
const ruleSummary = ref<QualityRuleSummary | null>(null)
const scopeReady = ref(false)
const batchLoading = ref(false)
const issueLoading = ref(false)
const rulesLoading = ref(false)
const loading = ref(true)
const checkLoading = ref(false)
const pageError = ref('')
const batchError = ref('')
const issueError = ref('')
const rulesError = ref('')
let requestId = 0
let disposed = false

const datasourceId = computed(() => context.datasourceId)
const snapshotId = computed(() => context.snapshotId)
const snapshot = computed(() => context.currentSnapshot)
const recentIssues = computed(() => issueSummary.value?.recentIssues ?? [])
const snapshotScoreLabel = computed(() => latestBatch.value?.complete && latestBatch.value.requestedScope === 'FULL'
  ? '本次完整校验评分' : '历史评分')
const canViewOverview = computed(() => iamS1.canOnDatasource('governance:view', datasourceId.value))
const canRunCheck = computed(() => iamS1.canOnDatasource('governance:check', datasourceId.value))
const canViewIssues = computed(() => iamS1.canOnDatasource('governance:issue:view', datasourceId.value))
const canViewRules = computed(() => iamS1.canOnDatasource('governance:rule:view', datasourceId.value))
const canReturnToRelease = computed(() => String(route.query.returnTo ?? '') === '/admin/releases'
  || String(route.query.source ?? '') === 'release')
const overviewState = computed(() => {
  if (!scopeReady.value) return {
    kind: 'loading', title: '正在读取治理范围', description: '正在确认数据源、快照和当前可用能力。',
  }
  if (!datasourceId.value || !snapshotId.value) return {
    kind: 'no-scope', title: '等待选择校验范围', description: '先在上方选择数据源和快照，再查看对应的检查状态和问题。',
  }
  if (!canViewOverview.value) return {
    kind: 'no-access', title: '无权查看校验状态', description: '当前账号没有此数据源的治理总览查看权限；这不表示校验尚未执行。',
  }
  if (batchLoading.value) return {
    kind: 'loading', title: '正在读取校验状态', description: '正在读取最近批次及其覆盖情况。',
  }
  if (batchError.value) return {
    kind: 'read-error', title: '校验状态读取失败', description: '目前无法确认最近检查状态，也无法判断是否存在可核验批次。',
  }
  const batch = latestBatch.value
  if (!batch) return {
    kind: 'no-batch',
    title: '尚无可核验的校验批次',
    description: snapshot.value?.qualityScore != null
      ? '已有历史评分记录，尚无法确认对应的规则覆盖。'
      : '当前没有可核验的批次记录；这不代表历史上从未执行过校验。',
  }
  if (batch.legacyLimited || batch.status === 'LEGACY') return {
    kind: 'legacy', title: '历史结果覆盖有限', description: '该历史记录缺少可核验的规则版本或覆盖明细，不能作为完整通过依据。',
  }
  if (batch.status === 'RUNNING') return {
    kind: 'running', title: '正在执行质量校验', description: '当前批次尚未完成；完成前不作为完整校验结果。',
  }
  if (batch.status === 'SUCCESS' && batch.complete) return {
    kind: 'success', title: '校验已完整完成', description: '本次请求范围的检查已完整结束。',
  }
  if (batch.status === 'PARTIAL' || batch.status === 'SUCCESS') return {
    kind: 'partial', title: '校验部分完成', description: '本次检查没有覆盖全部适用目标，不能视作完整通过。',
  }
  if (batch.status === 'FAILED') return {
    kind: 'failed', title: '校验未完成', description: '本次检查没有形成完整结果；请查看失败和跳过原因。',
  }
  return { kind: 'unknown', title: '校验状态未知', description: '当前批次返回了无法识别的状态，请查看批次明细。' }
})
function clearSnapshotState() {
  latestBatch.value = null
  issueSummary.value = null
  ruleSummary.value = null
  batchLoading.value = false
  issueLoading.value = false
  rulesLoading.value = false
  batchError.value = ''
  issueError.value = ''
  rulesError.value = ''
}

async function loadSnapshotState(expectedSnapshotId = snapshotId.value, expectedDatasourceId = datasourceId.value) {
  const current = ++requestId
  clearSnapshotState()
  if (!scopeReady.value || !expectedSnapshotId || !expectedDatasourceId) return

  const requests: Promise<void>[] = []
  batchLoading.value = canViewOverview.value
  if (canViewOverview.value) requests.push(getLatestQualityCheckBatch(expectedSnapshotId)
    .then((response) => {
      if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
      latestBatch.value = response.data ?? null
    })
    .catch(() => {
      if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) batchError.value = '最近一次校验结果读取失败。'
    })
    .finally(() => { if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) batchLoading.value = false }))

  if (canViewIssues.value) {
    issueLoading.value = true
    requests.push(getSnapshotQualityIssueSummary(expectedSnapshotId)
      .then((response) => {
        if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
        issueSummary.value = response.data ?? null
      })
      .catch(() => {
        if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) issueError.value = '待处理问题统计读取失败，无法确认问题数量。'
      })
      .finally(() => { if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) issueLoading.value = false }))
  } else {
    issueLoading.value = false
  }

  if (canViewRules.value) {
    rulesLoading.value = true
    requests.push(getQualityRuleSummary(expectedSnapshotId)
      .then((response) => {
        if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
        ruleSummary.value = response.data ?? null
      })
      .catch(() => {
        if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) rulesError.value = '当前范围规则摘要读取失败。'
      })
      .finally(() => { if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) rulesLoading.value = false }))
  } else {
    rulesLoading.value = false
  }
  await Promise.all(requests)
}

function isCurrent(id: number, expectedDatasourceId: number, expectedSnapshotId: number) {
  return !disposed && id === requestId && expectedDatasourceId === datasourceId.value
    && expectedSnapshotId === snapshotId.value
}

async function refresh() {
  loading.value = true
  pageError.value = ''
  try {
    await Promise.all([context.initialize(true), iamS1.load()])
    scopeReady.value = true
    await loadSnapshotState()
  } catch {
    scopeReady.value = true
    pageError.value = '治理范围或权限能力读取失败，请重试。'
  } finally {
    loading.value = false
  }
}

async function runCheck() {
  const currentDatasourceId = datasourceId.value
  const currentSnapshotId = snapshotId.value
  if (!currentDatasourceId || !currentSnapshotId) {
    ElMessage.warning('请选择数据源和快照后再执行质量校验。')
    return
  }
  if (!canRunCheck.value) {
    ElMessage.warning('当前账号没有执行质量校验的负责源权限。')
    return
  }
  checkLoading.value = true
  try {
    const response = await triggerQualityCheck(currentSnapshotId)
    if (currentSnapshotId !== snapshotId.value || currentDatasourceId !== datasourceId.value) return
    const result = response.data
    const verifiedSnapshotScore = result?.complete && result.requestedScope === 'FULL'
      ? result.qualityScore : undefined
    if (verifiedSnapshotScore != null) {
      context.snapshots = context.snapshots.map((item) => item.id === currentSnapshotId
        ? { ...item, qualityScore: verifiedSnapshotScore, status: result?.snapshotStatus || item.status }
        : item)
    }
    await loadSnapshotState(currentSnapshotId, currentDatasourceId)
    if (result?.status === 'SUCCESS' && result.complete) ElMessage.success('质量校验已完整完成。')
    else if (result?.status === 'PARTIAL') ElMessage.warning('质量校验部分完成；本次评分不作为完整结果。')
    else ElMessage.error('质量校验未完成，请查看失败覆盖说明。')
  } catch (error) {
    if (currentSnapshotId !== snapshotId.value || currentDatasourceId !== datasourceId.value) return
    await loadSnapshotState(currentSnapshotId, currentDatasourceId)
    const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(message || '质量校验请求失败，已刷新最近批次状态。')
  } finally {
    checkLoading.value = false
  }
}

function openIssueCenter() {
  if (!snapshotId.value || !datasourceId.value) return
  router.push({
    path: '/admin/governance/issues',
    query: buildContextQuery('datasource-snapshot', { datasourceId: datasourceId.value, snapshotId: snapshotId.value }),
  })
}

function openRules() {
  router.push({
    path: '/admin/governance/rules',
    query: buildContextQuery('datasource-snapshot', {
      datasourceId: datasourceId.value,
      snapshotId: snapshotId.value,
    }),
  })
}

function openReleasePage() {
  if (!snapshotId.value || !datasourceId.value) return
  router.push({
    path: '/admin/releases',
    query: buildContextQuery('datasource-snapshot', {
      datasourceId: datasourceId.value,
      snapshotId: snapshotId.value,
    }),
  })
}

function batchStateLabel(batch: QualityCheckBatch) {
  if (batch.legacyLimited || batch.status === 'LEGACY') return '历史覆盖有限'
  return ({ RUNNING: '正在执行', SUCCESS: batch.complete ? '完整完成' : '未完整完成',
    PARTIAL: '部分完成', FAILED: '校验失败' } as Record<string, string>)[batch.status] || batch.status
}

function coverageLabel(status: string) {
  return ({ SUCCESS: '已检查', FAILURE: '检查失败', NOT_APPLICABLE: '不适用', NOT_EXECUTED: '未执行' } as Record<string, string>)[status] || status
}

function coverageType(status: string) {
  return ({ SUCCESS: 'success', FAILURE: 'danger', NOT_APPLICABLE: 'warning', NOT_EXECUTED: 'info' } as Record<string, string>)[status] || 'info'
}

function scopeTypeLabel(scope?: string) {
  return ({ GLOBAL: '全局', DATASOURCE: '数据源', TABLE: '表', COLUMN: '字段' } as Record<string, string>)[scope || ''] || '范围未知'
}

function severityType(severity: string) {
  return severity === 'HIGH' ? 'danger' : severity === 'MEDIUM' ? 'warning' : 'info'
}

function requestedScopeLabel(batch: QualityCheckBatch) {
  if (batch.legacyLimited || !batch.requestedScope) return '历史批次范围未知'
  if (batch.requestedScope === 'FULL') return '全量快照'
  if (batch.requestedScope === 'TABLE') return `局部表范围 · ${(batch.requestedTableNames || []).join('、')}`
  return `局部维度 · ${(batch.requestedDimensions || []).map(qualityDimensionLabel).join('、')}`
}

function formatTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ')
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

function issueStatusCount(status: 'OPEN' | 'CONFIRMED' | 'REOPENED') {
  return issueSummary.value?.statusCounts?.[status] ?? '—'
}

onMounted(refresh)
watch(() => [context.datasourceId, context.snapshotId], () => {
  void loadSnapshotState()
})
onBeforeUnmount(() => {
  disposed = true
  requestId++
})
</script>

<template>
  <main class="quality-page post-login-page">
    <TaskPageHeader
      title="治理总览"
      description="查看当前快照的校验状态、待处理问题和规则配置。历史评分与批次覆盖分开展示。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading || batchLoading || issueLoading || rulesLoading" @click="refresh">刷新总览</el-button>
        <el-button v-if="canReturnToRelease" @click="openReleasePage">返回元数据版本</el-button>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="pageError" :message="pageError" @retry="refresh" />
    <template v-else>
      <section class="quality-page__status" :class="`quality-page__status--${overviewState.kind}`" aria-labelledby="quality-current-state">
        <div class="quality-page__status-main">
          <div class="quality-page__status-copy">
            <span class="quality-page__eyebrow">当前校验状态</span>
            <h2 id="quality-current-state">{{ overviewState.title }}</h2>
            <p class="quality-page__status-description" role="status" aria-live="polite">{{ overviewState.description }}</p>
            <div v-if="snapshot || snapshotId" class="quality-page__scope-context">
              <span>{{ snapshot ? `快照 v${snapshot.snapshotVersion}` : `快照 #${snapshotId}` }}</span>
              <BusinessStatusBadge v-if="snapshot" :status="snapshot.status" :label="snapshotStatusLabel(snapshot.status)" />
              <span v-if="overviewState.kind === 'no-batch' && snapshot?.qualityScore != null" class="quality-page__history-score">历史评分 {{ snapshot.qualityScore }} 分</span>
            </div>
          </div>
          <div class="quality-page__primary-action">
            <el-button
              type="primary"
              :icon="ClipboardCheck"
              :loading="checkLoading"
              :disabled="!snapshotId || !canRunCheck || checkLoading || latestBatch?.status === 'RUNNING'"
              @click="runCheck"
            >执行质量校验</el-button>
            <span v-if="datasourceId && !canRunCheck" class="quality-page__action-note">没有此数据源的执行权限</span>
            <span v-else-if="latestBatch?.status === 'RUNNING'" class="quality-page__action-note">当前批次运行中</span>
          </div>
        </div>

        <div v-if="latestBatch || (snapshot?.qualityScore != null && overviewState.kind !== 'no-batch')" class="quality-page__secondary-metrics" aria-label="评分与批次信息">
          <div v-if="snapshot?.qualityScore != null" class="quality-page__metric quality-page__metric--score">
            <span>{{ snapshotScoreLabel }}</span><strong>{{ snapshot.qualityScore }}<small>分</small></strong>
          </div>
          <div v-if="latestBatch" class="quality-page__metric">
            <span>最近校验</span><strong>{{ formatTime(latestBatch.startedAt) }}</strong>
          </div>
          <div v-if="latestBatch" class="quality-page__metric">
            <span>校验范围</span><strong>{{ requestedScopeLabel(latestBatch) }}</strong>
          </div>
          <div v-if="latestBatch" class="quality-page__metric">
            <span>本次检查评分</span>
            <strong v-if="latestBatch.complete && latestBatch.totalScore != null">{{ latestBatch.totalScore }}<small>分</small></strong>
            <strong v-else-if="latestBatch.status === 'PARTIAL'">不完整</strong>
            <strong v-else-if="latestBatch.status === 'FAILED'">未生成</strong>
            <strong v-else>—</strong>
          </div>
        </div>

        <ErrorState v-if="batchError" class="quality-page__batch-error" :message="batchError" @retry="loadSnapshotState()" />
        <div v-if="latestBatch?.errorSummary" class="quality-page__failure" role="alert">
          <AlertTriangle :size="17" aria-hidden="true" />
          <span>{{ latestBatch.errorSummary }}</span>
        </div>
      </section>

      <div class="quality-page__content-grid">
        <section class="quality-page__panel quality-page__issues" aria-labelledby="quality-issues-title">
          <header class="quality-page__panel-heading">
            <div><h2 id="quality-issues-title">待处理问题</h2></div>
            <el-button v-if="canViewIssues && snapshotId" link type="primary" @click="openIssueCenter">问题中心<ArrowRight :size="14" aria-hidden="true" /></el-button>
          </header>
          <ErrorState v-if="issueError" :message="issueError" @retry="loadSnapshotState()" />
          <p v-else-if="!datasourceId || !snapshotId" class="quality-page__inline-state">选择数据源和快照后查看问题。</p>
          <p v-else-if="!canViewIssues" class="quality-page__inline-state">没有此数据源的「查看质量问题」权限，问题统计不可见。</p>
          <p v-else-if="issueLoading" class="quality-page__inline-state" role="status">正在读取问题统计…</p>
          <template v-else-if="issueSummary">
            <div class="quality-page__issue-summary">
              <div class="quality-page__issue-total"><span>待处理总数</span><strong>{{ issueSummary.totalUnresolved }}</strong></div>
              <div class="quality-page__issue-counts" aria-label="待处理问题分类">
                <div><span>待确认</span><strong>{{ issueStatusCount('OPEN') }}</strong></div>
                <div><span>已确认</span><strong>{{ issueStatusCount('CONFIRMED') }}</strong></div>
                <div><span>重新打开</span><strong>{{ issueStatusCount('REOPENED') }}</strong></div>
              </div>
            </div>
            <div v-if="recentIssues.length" class="quality-page__issue-list">
              <p class="quality-page__preview-heading">最近问题</p>
              <article v-for="issue in recentIssues" :key="issue.id" class="quality-page__issue-row">
                <div class="quality-page__issue-copy">
                  <div class="quality-page__issue-meta">
                    <el-tag size="small" :type="severityType(issue.severity)">{{ severityLabel(issue.severity) }}</el-tag>
                    <span class="quality-page__issue-target">{{ issue.tableName }}<template v-if="issue.columnName">.{{ issue.columnName }}</template></span>
                    <span class="quality-page__issue-time">{{ formatTime(issue.createdAt) }}</span>
                  </div>
                  <strong>{{ issue.issueDescription }}</strong>
                  <p v-if="issue.suggestion">{{ issue.suggestion }}</p>
                </div>
              </article>
              <el-button v-if="issueSummary.totalUnresolved > 0" class="quality-page__all-issues" link type="primary" @click="openIssueCenter">查看问题中心<ArrowRight :size="14" aria-hidden="true" /></el-button>
            </div>
            <p v-else-if="issueSummary.totalUnresolved === 0" class="quality-page__empty-issues">当前无待处理问题记录。</p>
            <p v-else class="quality-page__empty-issues">当前没有返回近期预览；待处理总数仍以服务端统计为准。</p>
          </template>
          <p v-else class="quality-page__inline-state">暂无问题统计结果。</p>
        </section>

        <section class="quality-page__panel quality-page__rules" aria-labelledby="quality-rules-title">
          <header class="quality-page__panel-heading">
            <div><h2 id="quality-rules-title">规则摘要</h2><p>当前规则配置</p></div>
            <el-button v-if="canViewRules && snapshotId" link type="primary" @click="openRules">管理规则<ArrowRight :size="14" aria-hidden="true" /></el-button>
          </header>
          <ErrorState v-if="rulesError" :message="rulesError" @retry="loadSnapshotState()" />
          <p v-else-if="!datasourceId || !snapshotId" class="quality-page__inline-state">选择数据源和快照后查看规则配置。</p>
          <p v-else-if="!canViewRules" class="quality-page__inline-state">没有此数据源的「查看治理规则」权限，规则摘要不可见。</p>
          <p v-else-if="rulesLoading" class="quality-page__inline-state" role="status">正在读取规则摘要…</p>
          <template v-else-if="ruleSummary">
            <div class="quality-page__rule-stats">
              <div><span>当前适用</span><strong>{{ ruleSummary.applicableCount }}</strong><small>项</small></div>
              <div><span>已启用</span><strong>{{ ruleSummary.enabledCount }}</strong><small>项</small></div>
            </div>
            <p v-if="ruleSummary.unsupportedCount" class="quality-page__rule-warning" role="alert">
              有 {{ ruleSummary.unsupportedCount }} 条已启用规则尚未映射到受支持模板；执行时会记录失败覆盖。
            </p>
            <p v-if="ruleSummary.overriddenCount" class="quality-page__rule-note">有 {{ ruleSummary.overriddenCount }} 条规则的部分目标由更具体范围覆盖。</p>
          </template>
          <p v-else class="quality-page__inline-state">暂无规则摘要。</p>

          <section v-if="latestBatch || batchError || batchLoading || !canViewOverview || !datasourceId || !snapshotId" class="quality-page__execution" aria-label="最近批次执行情况">
            <h3>最近批次执行</h3>
            <p v-if="batchError" class="quality-page__execution-state quality-page__execution-state--error" role="alert">读取失败，无法核对实际执行数。</p>
            <p v-else-if="!canViewOverview && datasourceId" class="quality-page__execution-state">没有查看校验批次的权限。</p>
            <p v-else-if="!datasourceId || !snapshotId" class="quality-page__execution-state">选择数据源和快照后查看执行情况。</p>
            <p v-else-if="batchLoading" class="quality-page__execution-state" role="status">正在读取批次…</p>
            <p v-else-if="!latestBatch" class="quality-page__execution-state">暂无可核验批次。</p>
            <p v-else-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'" class="quality-page__execution-state">历史覆盖有限，实际执行数不可核验。</p>
            <div v-else class="quality-page__batch-counts">
              <div><strong>{{ latestBatch.executedRuleCount ?? '—' }}</strong><span>项实际执行</span></div>
              <div><strong>{{ latestBatch.failedRuleCount ?? '—' }}</strong><span>项失败</span></div>
              <div><strong>{{ latestBatch.notApplicableRuleCount ?? '—' }}</strong><span>项不适用</span></div>
              <p>{{ batchStateLabel(latestBatch) }}<template v-if="latestBatch.startedAt"> · {{ formatTime(latestBatch.startedAt) }}</template></p>
            </div>
          </section>
        </section>
      </div>

      <details v-if="latestBatch" class="quality-page__details">
        <summary>
          <div class="quality-page__details-title"><h2>校验明细</h2><span>{{ batchStateLabel(latestBatch) }} · {{ formatTime(latestBatch.startedAt) }} · {{ requestedScopeLabel(latestBatch) }}</span></div>
          <div v-if="!latestBatch.legacyLimited && latestBatch.status !== 'LEGACY'" class="quality-page__details-counts">
            <span>{{ latestBatch.executedRuleCount ?? '—' }} 项已执行</span>
            <span>{{ latestBatch.failedRuleCount ?? '—' }} 项失败</span>
            <span>{{ latestBatch.notApplicableRuleCount ?? '—' }} 项不适用</span>
          </div>
        </summary>
        <p v-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'" class="quality-page__history-note" role="status">
          此历史结果没有可证明的规则配置版本和执行覆盖；当前配置不会被用来补造历史依据。
        </p>
        <div v-if="Object.keys(latestBatch.dimensionScores || {}).length" class="quality-page__dimension-list">
          <div v-for="[dimension, score] in Object.entries(latestBatch.dimensionScores || {})" :key="dimension">
            <span>{{ qualityDimensionLabel(dimension) }}</span><strong>{{ score }} 分</strong>
          </div>
        </div>
        <div v-if="latestBatch.rules?.length" class="quality-page__coverage-list">
          <article v-for="rule in latestBatch.rules" :key="`${rule.ruleId}-${rule.ruleVersion}`" class="quality-page__coverage-row">
            <div class="quality-page__coverage-rule">
              <strong>{{ rule.ruleName }}</strong>
              <span>{{ qualityDimensionLabel(rule.dimension) }} · {{ scopeTypeLabel(rule.scopeType) }}<template v-if="rule.targetTableName"> · {{ rule.targetTableName }}</template><template v-if="rule.targetColumnName">.{{ rule.targetColumnName }}</template></span>
            </div>
            <div class="quality-page__coverage-status">
              <el-tag size="small" :type="coverageType(rule.status)">{{ coverageLabel(rule.status) }}</el-tag>
              <small>v{{ rule.ruleVersion ?? '—' }} · 检查 {{ rule.checkedTargetCount ?? '—' }}/{{ rule.targetCount ?? '—' }} 个目标 · {{ rule.issueCount }} 个问题</small>
              <p v-if="rule.reason" :class="{ 'quality-page__coverage-reason--error': rule.status === 'FAILURE', 'quality-page__coverage-reason--skipped': rule.status === 'NOT_EXECUTED' || rule.status === 'NOT_APPLICABLE' }" :role="rule.status === 'FAILURE' ? 'alert' : 'status'">{{ rule.reason }}</p>
            </div>
          </article>
        </div>
        <p v-else-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'" class="quality-page__inline-state">该历史结果没有规则覆盖记录。</p>
        <p v-else class="quality-page__inline-state">本批次没有规则覆盖记录。</p>
      </details>
      <div v-else-if="batchLoading || batchError || !canViewOverview || !datasourceId || !snapshotId" class="quality-page__details-empty" role="status">
        <strong>校验明细</strong>
        <span v-if="!datasourceId || !snapshotId">选择数据源和快照后查看批次明细。</span>
        <span v-else-if="!canViewOverview">没有查看校验批次的权限。</span>
        <span v-else-if="batchLoading">正在读取批次明细…</span>
        <span v-else-if="batchError">校验状态读取失败，暂时无法确认是否有明细。</span>
      </div>
    </template>
  </main>
</template>

<style scoped>
.quality-page { display: grid; gap: 16px; min-width: 0; }
.quality-page :deep(.task-page-header) { align-items: center; margin-bottom: 0; }
.quality-page :deep(.task-page-header h1) { font-size: clamp(24px, 2vw, 28px); }
.quality-page__status,
.quality-page__panel,
.quality-page__details {
  min-width: 0;
  padding: 20px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
}
.quality-page__status { display: grid; gap: 16px; }
.quality-page__status--read-error,
.quality-page__status--failed { border-color: color-mix(in srgb, var(--do-danger) 40%, var(--do-line)); }
.quality-page__status--partial,
.quality-page__status--legacy { border-color: color-mix(in srgb, var(--do-warning) 42%, var(--do-line)); }
.quality-page__status-main { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: 20px; }
.quality-page__status-copy { min-width: 0; }
.quality-page__eyebrow { display: block; margin-bottom: 5px; color: var(--do-muted); font-size: 13px; font-weight: 600; }
.quality-page__status-copy h2 { margin: 0; color: var(--do-ink); font-size: clamp(18px, 1.8vw, 22px); line-height: 1.3; }
.quality-page__status-description { max-width: 780px; margin: 6px 0 0; color: var(--do-muted); font-size: 14px; line-height: 1.5; }
.quality-page__scope-context { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 10px; color: var(--do-muted); font-size: 13px; }
.quality-page__history-score { font-weight: 600; font-variant-numeric: tabular-nums; }
.quality-page__scope-context :deep(.business-status-badge) { min-height: 22px; padding: 2px 7px; font-size: 12px; }
.quality-page__primary-action { display: grid; justify-items: end; gap: 7px; }
.quality-page__primary-action :deep(.el-button) { min-height: 40px; }
.quality-page__action-note { max-width: 250px; color: var(--do-muted); font-size: 13px; line-height: 1.4; text-align: right; }
.quality-page__secondary-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); gap: 12px 20px; padding-top: 14px; border-top: 1px solid var(--do-line); }
.quality-page__metric { display: grid; align-content: start; gap: 4px; min-width: 0; }
.quality-page__metric > span { color: var(--do-muted); font-size: 13px; }
.quality-page__metric > strong { overflow-wrap: anywhere; color: var(--do-ink); font-size: 14px; font-weight: 600; line-height: 1.4; }
.quality-page__metric > strong small { margin-left: 3px; color: var(--do-muted); font-size: 12px; font-weight: 500; }
.quality-page__metric--score > strong { font-size: 18px; font-variant-numeric: tabular-nums; }
.quality-page__batch-error { margin-top: 0; }
.quality-page__failure { display: flex; align-items: flex-start; gap: 9px; margin: 0; padding: 10px 12px; border-radius: var(--do-radius-md); color: var(--do-danger); background: var(--do-danger-soft); font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; }
.quality-page__failure svg { flex: 0 0 auto; margin-top: 1px; }
.quality-page__content-grid { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); align-items: start; gap: 16px; min-width: 0; }
.quality-page__panel { display: grid; align-content: start; gap: 14px; }
.quality-page__panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; min-width: 0; }
.quality-page__panel-heading > div { min-width: 0; }
.quality-page__panel-heading h2 { margin: 0; color: var(--do-ink); font-size: 17px; line-height: 1.35; }
.quality-page__panel-heading p { margin: 4px 0 0; color: var(--do-muted); font-size: 13px; line-height: 1.45; }
.quality-page__panel-heading :deep(.el-button) { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 5px; }
.quality-page__inline-state { margin: 0; color: var(--do-muted); font-size: 14px; line-height: 1.5; }
.quality-page__issue-summary { display: grid; grid-template-columns: minmax(105px, .55fr) minmax(0, 1.45fr); align-items: center; gap: 14px; }
.quality-page__issue-total { display: grid; align-content: center; gap: 3px; min-height: 64px; padding-right: 14px; border-right: 1px solid var(--do-line); }
.quality-page__issue-total span,.quality-page__issue-counts span { color: var(--do-muted); font-size: 13px; }
.quality-page__issue-total strong { color: var(--do-ink); font-size: 28px; font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1.1; }
.quality-page__issue-counts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 12px; }
.quality-page__issue-counts > div { display: grid; gap: 3px; min-width: 0; }
.quality-page__issue-counts strong { color: var(--do-ink); font-size: 16px; font-variant-numeric: tabular-nums; }
.quality-page__preview-heading { margin: 0; padding-top: 12px; border-top: 1px solid var(--do-line); color: var(--do-muted); font-size: 13px; font-weight: 600; }
.quality-page__issue-list { display: grid; gap: 0; min-width: 0; }
.quality-page__issue-row { display: grid; min-width: 0; padding: 12px 0; border-top: 1px solid var(--do-line); }
.quality-page__issue-copy { min-width: 0; }
.quality-page__issue-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 10px; margin-bottom: 6px; color: var(--do-muted); font-size: 13px; }
.quality-page__issue-target { min-width: 0; overflow-wrap: anywhere; }
.quality-page__issue-time { font-variant-numeric: tabular-nums; }
.quality-page__issue-copy > strong { display: block; overflow-wrap: anywhere; color: var(--do-ink); font-size: 14px; font-weight: 600; line-height: 1.45; }
.quality-page__issue-copy > p { margin: 5px 0 0; color: var(--do-muted); font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.quality-page__all-issues { justify-self: start; display: inline-flex; align-items: center; gap: 5px; margin-top: 2px; }
.quality-page__empty-issues { margin: 0; color: var(--do-muted); font-size: 14px; line-height: 1.5; }
.quality-page__rule-stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.quality-page__rule-stats > div { display: grid; grid-template-columns: auto 1fr; align-items: baseline; gap: 3px 7px; min-width: 0; padding: 10px 0; border-top: 1px solid var(--do-line); }
.quality-page__rule-stats span { grid-column: 1 / -1; color: var(--do-muted); font-size: 13px; }
.quality-page__rule-stats strong { color: var(--do-ink); font-size: 22px; font-variant-numeric: tabular-nums; }
.quality-page__rule-stats small { color: var(--do-muted); font-size: 13px; }
.quality-page__rule-warning { margin: 0; padding: 9px 10px; border-radius: var(--do-radius-md); color: var(--do-danger); background: var(--do-danger-soft); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.quality-page__rule-note { margin: 0; color: var(--do-muted); font-size: 13px; line-height: 1.5; }
.quality-page__execution { display: grid; gap: 8px; padding-top: 12px; border-top: 1px solid var(--do-line); }
.quality-page__execution h3 { margin: 0; color: var(--do-ink); font-size: 14px; font-weight: 600; }
.quality-page__execution-state { margin: 0; color: var(--do-muted); font-size: 13px; line-height: 1.5; }
.quality-page__execution-state--error { color: var(--do-danger); }
.quality-page__batch-counts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 5px 10px; }
.quality-page__batch-counts > div { display: grid; gap: 2px; min-width: 0; }
.quality-page__batch-counts strong { color: var(--do-ink); font-size: 16px; font-variant-numeric: tabular-nums; }
.quality-page__batch-counts span { color: var(--do-muted); font-size: 12px; line-height: 1.35; }
.quality-page__batch-counts p { grid-column: 1 / -1; margin: 5px 0 0; color: var(--do-muted); font-size: 13px; }
.quality-page__details { padding: 0 20px; }
.quality-page__details summary { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px 20px; padding: 15px 0; color: var(--do-ink); cursor: pointer; }
.quality-page__details-title { display: grid; gap: 3px; min-width: 0; }
.quality-page__details-title h2 { margin: 0; font-size: 17px; }
.quality-page__details-title span { color: var(--do-muted); font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.quality-page__details-counts { display: flex; flex-wrap: wrap; gap: 6px 14px; color: var(--do-muted); font-size: 13px; }
.quality-page__history-note { margin: 0 0 12px; padding: 10px 12px; border-radius: var(--do-radius-md); color: var(--do-warning); background: var(--do-warning-soft); font-size: 14px; line-height: 1.5; }
.quality-page__dimension-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px 16px; padding: 4px 0 14px; }
.quality-page__dimension-list > div { display: flex; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--do-line); font-size: 14px; }
.quality-page__dimension-list span { color: var(--do-muted); }
.quality-page__coverage-list { display: grid; }
.quality-page__coverage-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(240px, .8fr); align-items: start; gap: 12px 20px; padding: 14px 0; border-top: 1px solid var(--do-line); }
.quality-page__coverage-rule { display: grid; gap: 5px; min-width: 0; }
.quality-page__coverage-rule strong { color: var(--do-ink); font-size: 14px; line-height: 1.4; overflow-wrap: anywhere; }
.quality-page__coverage-rule span { color: var(--do-muted); font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.quality-page__coverage-status { display: grid; justify-items: start; gap: 6px; min-width: 0; }
.quality-page__coverage-status small { color: var(--do-muted); font-size: 13px; line-height: 1.4; }
.quality-page__coverage-status p { margin: 0; color: var(--do-muted); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.quality-page__coverage-status .quality-page__coverage-reason--error { color: var(--do-danger); }
.quality-page__coverage-status .quality-page__coverage-reason--skipped { color: var(--do-warning); }
.quality-page__inline-state { margin: 0; color: var(--do-muted); font-size: 14px; line-height: 1.5; }
.quality-page__details-empty { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px 14px; min-width: 0; padding: 2px 2px 0; color: var(--do-muted); font-size: 14px; line-height: 1.5; }
.quality-page__details-empty strong { color: var(--do-ink); font-size: 15px; }
@media (max-width: 1100px) {
  .quality-page__content-grid { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 760px) {
  .quality-page__status,.quality-page__panel { padding: 16px; }
  .quality-page__status-main { grid-template-columns: minmax(0, 1fr); gap: 14px; }
  .quality-page__primary-action { justify-items: start; }
  .quality-page__action-note { text-align: left; }
  .quality-page__secondary-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quality-page__issue-summary { grid-template-columns: minmax(0, 1fr); gap: 10px; }
  .quality-page__issue-total { min-height: auto; padding: 0 0 10px; border-right: 0; border-bottom: 1px solid var(--do-line); }
  .quality-page__coverage-row { grid-template-columns: minmax(0, 1fr); gap: 9px; }
  .quality-page__details { padding: 0 16px; }
}
@media (max-width: 480px) {
  .quality-page__secondary-metrics { grid-template-columns: minmax(0, 1fr); }
  .quality-page__panel-heading { align-items: flex-start; }
  .quality-page__issue-counts { gap: 8px; }
  .quality-page__details-counts { font-size: 12px; }
}
</style>
