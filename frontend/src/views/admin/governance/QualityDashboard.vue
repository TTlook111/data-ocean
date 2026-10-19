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
const batchLoading = ref(false)
const issueLoading = ref(false)
const rulesLoading = ref(false)
const loading = ref(false)
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
const canViewOverview = computed(() => iamS1.canOnDatasource('governance:view', datasourceId.value))
const canRunCheck = computed(() => iamS1.canOnDatasource('governance:check', datasourceId.value))
const canViewIssues = computed(() => iamS1.canOnDatasource('governance:issue:view', datasourceId.value))
const canViewRules = computed(() => iamS1.canOnDatasource('governance:rule:view', datasourceId.value))
const canReturnToRelease = computed(() => String(route.query.returnTo ?? '') === '/admin/releases'
  || String(route.query.source ?? '') === 'release')

function clearSnapshotState() {
  latestBatch.value = null
  issueSummary.value = null
  ruleSummary.value = null
  batchError.value = ''
  issueError.value = ''
  rulesError.value = ''
}

async function loadSnapshotState(expectedSnapshotId = snapshotId.value, expectedDatasourceId = datasourceId.value) {
  const current = ++requestId
  clearSnapshotState()
  if (!expectedSnapshotId || !expectedDatasourceId) return

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
    await loadSnapshotState()
  } catch {
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

function stateLabel(status?: string) {
  if (!status) return '暂无可核验结果'
  return ({ RUNNING: '正在执行', SUCCESS: '完整完成', PARTIAL: '部分失败', FAILED: '执行失败', LEGACY: '历史结果覆盖未知' } as Record<string, string>)[status] || status
}

function coverageLabel(status: string) {
  return ({ SUCCESS: '已检查', FAILURE: '检查失败', NOT_APPLICABLE: '不适用', NOT_EXECUTED: '未执行' } as Record<string, string>)[status] || status
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
      description="查看所选快照最近一次可核验的校验批次、待处理问题和适用规则。快照记录评分与本次检查状态分别展示。"
    >
      <template #actions>
        <el-button :icon="RefreshCw" :loading="loading || batchLoading || issueLoading || rulesLoading" @click="refresh">刷新总览</el-button>
        <el-button v-if="canReturnToRelease" @click="openReleasePage">返回元数据版本</el-button>
      </template>
    </TaskPageHeader>

    <ErrorState v-if="pageError" :message="pageError" @retry="refresh" />
    <section v-else class="quality-page__status" aria-label="当前快照校验状态">
      <div class="quality-page__status-heading">
        <div>
          <span class="quality-page__eyebrow">当前范围</span>
          <h2 v-if="snapshot">v{{ snapshot.snapshotVersion }} · {{ snapshotStatusLabel(snapshot.status) }}</h2>
          <h2 v-else-if="snapshotId">快照 #{{ snapshotId }}</h2>
          <h2 v-else>尚未选择快照</h2>
          <p>{{ context.currentDatasource?.name || '未选择数据源' }}</p>
        </div>
        <div class="quality-page__primary-action">
          <BusinessStatusBadge v-if="latestBatch" :status="latestBatch.status" :label="stateLabel(latestBatch.status)" />
          <span v-else-if="!canViewOverview" class="quality-page__muted">没有治理总览查看权限</span>
          <span v-else-if="batchLoading" class="quality-page__muted" role="status">正在读取校验状态…</span>
          <span v-else class="quality-page__muted">暂无可核验结果</span>
          <el-button type="primary" :icon="ClipboardCheck" :loading="checkLoading" :disabled="!snapshotId || !canRunCheck || checkLoading" @click="runCheck">
            执行质量校验
          </el-button>
        </div>
      </div>

      <div class="quality-page__metrics">
        <div>
          <span>快照记录评分</span>
          <strong v-if="snapshot?.qualityScore != null">{{ snapshot.qualityScore }} 分</strong>
          <strong v-else>未记录</strong>
        </div>
        <div>
          <span>最近校验</span>
          <strong v-if="latestBatch">{{ formatTime(latestBatch.startedAt) }}</strong>
          <strong v-else-if="batchLoading">读取中…</strong>
          <strong v-else>暂无可核验结果</strong>
        </div>
        <div>
          <span>最近批次覆盖</span>
          <strong v-if="latestBatch">{{ requestedScopeLabel(latestBatch) }}</strong>
          <strong v-else>未记录</strong>
        </div>
        <div>
          <span>本次检查评分</span>
          <strong v-if="latestBatch?.complete && latestBatch.totalScore != null">{{ latestBatch.totalScore }} 分</strong>
          <strong v-else-if="latestBatch?.status === 'PARTIAL'">不完整</strong>
          <strong v-else-if="latestBatch?.status === 'FAILED'">未生成</strong>
          <strong v-else>—</strong>
        </div>
      </div>
      <ErrorState v-if="batchError" :message="batchError" @retry="loadSnapshotState()" />
      <p v-if="!latestBatch && snapshot?.qualityScore != null" class="quality-page__history-note">
        当前快照有历史评分记录，但没有可核验的校验批次；不能据此判断最近检查覆盖或问题数量。
      </p>
      <p v-if="latestBatch?.legacyLimited" class="quality-page__history-note" role="status">
        该历史结果缺少规则版本与覆盖记录，不能还原当时的完整检查依据。
      </p>
      <div v-if="latestBatch?.errorSummary" class="quality-page__failure" role="alert">
        <AlertTriangle :size="17" aria-hidden="true" />
        <span>{{ latestBatch.errorSummary }}</span>
      </div>
      <p v-if="!canRunCheck && datasourceId" class="quality-page__hint">当前账号没有此数据源的「执行质量校验」权限。</p>
    </section>

    <section class="quality-page__issues" aria-label="待处理问题">
      <header class="quality-page__section-heading">
        <div><span>风险处理</span><h2>待处理问题</h2></div>
        <el-button v-if="canViewIssues && snapshotId" link type="primary" @click="openIssueCenter">进入问题中心<ArrowRight :size="14" aria-hidden="true" /></el-button>
      </header>
      <ErrorState v-if="issueError" :message="issueError" @retry="loadSnapshotState()" />
      <p v-else-if="!canViewIssues && datasourceId" class="quality-page__hint">没有此数据源的「查看质量问题」权限，问题统计不可见。</p>
      <div v-else-if="issueLoading" class="quality-page__inline-state" role="status">正在读取问题统计…</div>
      <template v-else-if="issueSummary">
        <div class="quality-page__issue-counts">
          <span>待处理 <strong>{{ issueSummary.totalUnresolved }}</strong></span>
          <span>待确认 {{ issueSummary.statusCounts.OPEN ?? 0 }}</span>
          <span>已确认 {{ issueSummary.statusCounts.CONFIRMED ?? 0 }}</span>
          <span>重新打开 {{ issueSummary.statusCounts.REOPENED ?? 0 }}</span>
        </div>
        <div v-if="issueSummary.recentIssues.length" class="quality-page__issue-list">
          <article v-for="issue in issueSummary.recentIssues" :key="issue.id" class="quality-page__issue-row">
            <div class="quality-page__issue-copy">
              <div class="quality-page__issue-meta">
                <el-tag size="small" :type="severityType(issue.severity)">{{ severityLabel(issue.severity) }}</el-tag>
                <span>{{ issue.tableName }}<template v-if="issue.columnName">.{{ issue.columnName }}</template></span>
                <span class="quality-page__muted">{{ formatTime(issue.createdAt) }}</span>
              </div>
              <strong>{{ issue.issueDescription }}</strong>
              <p v-if="issue.suggestion">{{ issue.suggestion }}</p>
            </div>
            <el-button v-if="canViewIssues" link type="primary" @click="openIssueCenter">处理</el-button>
          </article>
          <button v-if="issueSummary.totalUnresolved > issueSummary.recentIssues.length" class="quality-page__more-issues" type="button" @click="openIssueCenter">
            查看其余 {{ issueSummary.totalUnresolved - issueSummary.recentIssues.length }} 个问题
          </button>
        </div>
        <p v-else class="quality-page__no-issues">当前没有待处理问题。</p>
      </template>
      <p v-else-if="snapshotId" class="quality-page__muted">暂无统计结果。</p>
      <p v-else class="quality-page__muted">选择数据源和快照后查看问题。</p>
    </section>

    <section class="quality-page__details" aria-label="校验明细">
      <header class="quality-page__section-heading">
        <div><span>执行覆盖</span><h2>校验明细</h2></div>
        <button v-if="snapshotId" class="quality-page__rules-link" type="button" @click="openRules">
          质量规则
          <template v-if="ruleSummary && !rulesError">
            · {{ ruleSummary.applicableCount }} 项适用 / {{ ruleSummary.enabledCount }} 项启用 / {{ ruleSummary.executedCount ?? '—' }} 项实际执行
          </template>
          <ArrowRight :size="14" aria-hidden="true" />
        </button>
      </header>
      <ErrorState v-if="rulesError" :message="rulesError" @retry="loadSnapshotState()" />
      <p v-else-if="!canViewRules && datasourceId" class="quality-page__hint">没有此数据源的「查看治理规则」权限，规则摘要不可见。</p>
      <p v-else-if="rulesLoading" class="quality-page__inline-state" role="status">正在读取规则摘要…</p>
      <p v-else-if="ruleSummary?.unsupportedCount" class="quality-page__failure" role="alert">
        有 {{ ruleSummary.unsupportedCount }} 条规则尚未映射到受支持的执行模板；本次检查会将其记为失败覆盖。
      </p>
      <details v-if="latestBatch" class="quality-page__batch-details">
        <summary>
          {{ stateLabel(latestBatch.status) }}<template v-if="!latestBatch.legacyLimited"> · {{ latestBatch.executedRuleCount ?? '—' }} 项已执行 · {{ latestBatch.failedRuleCount ?? '—' }} 项失败 · {{ latestBatch.notApplicableRuleCount ?? '—' }} 项不适用</template>
        </summary>
        <p v-if="latestBatch.legacyLimited" class="quality-page__history-note">此历史结果没有可证明的规则配置版本和执行覆盖；当前配置不会被用来补造历史依据。</p>
        <div v-if="Object.keys(latestBatch.dimensionScores || {}).length" class="quality-page__dimension-list">
          <div v-for="[dimension, score] in Object.entries(latestBatch.dimensionScores || {})" :key="dimension">
            <span>{{ qualityDimensionLabel(dimension) }}</span><strong>{{ score }} 分</strong>
          </div>
        </div>
        <div v-if="latestBatch.rules.length" class="quality-page__coverage-list">
          <article v-for="rule in latestBatch.rules" :key="`${rule.ruleId}-${rule.ruleVersion}`" class="quality-page__coverage-row">
            <div><strong>{{ rule.ruleName }}</strong><span>{{ qualityDimensionLabel(rule.dimension) }} · {{ rule.scopeType }}<template v-if="rule.targetTableName"> · {{ rule.targetTableName }}</template><template v-if="rule.targetColumnName">.{{ rule.targetColumnName }}</template></span></div>
            <div class="quality-page__coverage-status">
              <el-tag size="small" :type="rule.status === 'SUCCESS' ? 'success' : rule.status === 'FAILURE' ? 'danger' : 'info'">{{ coverageLabel(rule.status) }}</el-tag>
              <small>v{{ rule.ruleVersion }} · {{ rule.issueCount }} 个问题</small>
              <p v-if="rule.reason">{{ rule.reason }}</p>
            </div>
          </article>
        </div>
        <p v-else class="quality-page__muted">本批次没有规则覆盖记录。</p>
      </details>
      <p v-else-if="!batchLoading && !batchError" class="quality-page__muted">尚无可核验的校验明细。执行一次质量校验后将在此显示规则版本和覆盖状态。</p>
    </section>
  </main>
</template>

<style scoped>
.quality-page { display: grid; gap: 16px; min-width: 0; }
.quality-page__status,
.quality-page__issues,
.quality-page__details { min-width: 0; padding: 18px 20px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-surface); }
.quality-page__status-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px 24px; }
.quality-page__eyebrow,
.quality-page__section-heading > div > span { color: var(--do-muted); font-size: 12px; }
.quality-page__status-heading h2 { margin: 4px 0; color: var(--do-ink); font-size: 20px; }
.quality-page__status-heading p { margin: 0; color: var(--do-muted); font-size: 13px; }
.quality-page__primary-action { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.quality-page__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--do-line); }
.quality-page__metrics > div { display: grid; align-content: start; gap: 5px; min-width: 0; }
.quality-page__metrics span { color: var(--do-muted); font-size: 12px; }
.quality-page__metrics strong { overflow-wrap: anywhere; color: var(--do-ink); font-size: 14px; font-weight: 600; }
.quality-page__history-note,.quality-page__hint { margin: 14px 0 0; color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.quality-page__failure { display: flex; align-items: flex-start; gap: 8px; margin: 14px 0 0; color: var(--do-danger); font-size: 13px; line-height: 1.5; }
.quality-page__section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.quality-page__section-heading h2 { margin: 3px 0 0; color: var(--do-ink); font-size: 17px; }
.quality-page__section-heading :deep(.el-button),
.quality-page__rules-link { display: inline-flex; align-items: center; gap: 5px; }
.quality-page__issue-counts { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 18px; margin-bottom: 12px; color: var(--do-muted); font-size: 12px; }
.quality-page__issue-counts strong { color: var(--do-ink); font-size: 16px; }
.quality-page__issue-list { display: grid; }
.quality-page__issue-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0; border-top: 1px solid var(--do-line); }
.quality-page__issue-copy { min-width: 0; }
.quality-page__issue-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 10px; margin-bottom: 5px; color: var(--do-muted); font-size: 12px; }
.quality-page__issue-copy > strong { display: block; overflow-wrap: anywhere; color: var(--do-ink); font-size: 14px; font-weight: 600; }
.quality-page__issue-copy p { margin: 4px 0 0; color: var(--do-muted); font-size: 12px; }
.quality-page__more-issues,.quality-page__rules-link { border: 0; padding: 0; color: var(--do-primary-strong); background: transparent; cursor: pointer; font-size: 13px; font-weight: 600; }
.quality-page__more-issues { margin-top: 10px; }
.quality-page__inline-state { margin: 6px 0; color: var(--do-muted); font-size: 13px; }
.quality-page__muted { color: var(--do-muted); font-size: 12px; }
.quality-page__no-issues { margin: 8px 0 0; color: var(--do-muted); font-size: 13px; }
.quality-page__batch-details { border-top: 1px solid var(--do-line); }
.quality-page__batch-details summary { padding: 12px 0 2px; color: var(--do-ink); cursor: pointer; font-size: 13px; font-weight: 600; }
.quality-page__dimension-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px 16px; padding: 12px 0; }
.quality-page__dimension-list > div { display: flex; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--do-line); font-size: 13px; }
.quality-page__dimension-list span { color: var(--do-muted); }
.quality-page__coverage-list { display: grid; }
.quality-page__coverage-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 12px 0; border-top: 1px solid var(--do-line); }
.quality-page__coverage-row > div:first-child { display: grid; gap: 4px; }
.quality-page__coverage-row strong { color: var(--do-ink); font-size: 13px; }
.quality-page__coverage-row span,.quality-page__coverage-row small,.quality-page__coverage-row p { color: var(--do-muted); font-size: 12px; }
.quality-page__coverage-status { display: grid; justify-items: end; gap: 4px; text-align: right; }
.quality-page__coverage-status p { margin: 0; }
@media (max-width: 760px) {
  .quality-page__status,.quality-page__issues,.quality-page__details { padding: 14px 12px; }
  .quality-page__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quality-page__coverage-row { align-items: flex-start; flex-direction: column; gap: 8px; }
  .quality-page__coverage-status { justify-items: start; text-align: left; }
}
</style>
