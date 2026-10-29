<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, ArrowRight, ClipboardCheck, RefreshCw, Inbox } from 'lucide-vue-next'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import RuleApplicabilitySummary from '../../../components/admin/RuleApplicabilitySummary.vue'
import TitleHelp from '../../../components/admin/TitleHelp.vue'
import { useAdminScrollLock } from '../../../composables/useAdminScrollLock'
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
import { useGovernanceScope } from '../../../composables/useGovernanceScope'
import { trackGovernanceWrite } from '../../../composables/governanceWriteTracker'
import GovernanceWriteNotice from '../../../components/admin/GovernanceWriteNotice.vue'
import { useAuthStore } from '../../../stores/auth'
import { useAdminContextStore } from '../../../stores/adminContext'
import { useIamS1Store } from '../../../stores/iamS1'
import { buildContextQuery } from '../../../utils/adminNavigation'

const context = useAdminContextStore()
const scope = useGovernanceScope(),
  auth = useAuthStore()
let initTicket = 0
const iamS1 = useIamS1Store()
const route = useRoute()
const router = useRouter()

const latestBatch = ref<QualityCheckBatch | null>(null)
const coverageOpen = ref(false)
useAdminScrollLock(() => coverageOpen.value)
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
const rulePreview = computed(() =>
  [...(ruleSummary.value?.rules || [])]
    .sort((a, b) => Number(a.status === 'READY') - Number(b.status === 'READY'))
    .slice(0, 3),
)
const recentIssues = computed(() => issueSummary.value?.recentIssues ?? [])
const snapshotScoreLabel = computed(() =>
  latestBatch.value?.complete &&
  latestBatch.value.requestedScope === 'FULL' &&
  latestBatch.value.totalScore != null
    ? '本次完整校验评分'
    : '历史评分',
)
const canViewOverview = computed(() => iamS1.canOnDatasource('governance:view', datasourceId.value))
const canRunCheck = computed(() => iamS1.canOnDatasource('governance:check', datasourceId.value))
const canViewIssues = computed(() => iamS1.canOnDatasource('governance:issue:view', datasourceId.value))
const canViewRules = computed(() => iamS1.canOnDatasource('governance:rule:view', datasourceId.value))
const canReturnToRelease = computed(
  () =>
    String(route.query.returnTo ?? '') === '/admin/releases' ||
    String(route.query.source ?? '') === 'release',
)
const overviewState = computed(() => {
  if (!scopeReady.value || scope.scopeLoading.value)
    return {
      kind: 'loading',
      title: '正在读取治理范围',
      description: '正在确认数据源、快照和当前可用能力。',
    }
  if (iamS1.errorMessage)
    return { kind: 'read-error', title: '治理权限读取失败', description: iamS1.errorMessage }
  if (!scope.ready.value)
    return {
      kind: 'no-scope',
      title: '等待选择校验范围',
      description: scope.scopeIssue.value || '先在上方选择数据源和快照，再查看对应的检查状态和问题。',
    }
  if (!canViewOverview.value)
    return {
      kind: 'no-access',
      title: '无权查看校验状态',
      description: '当前账号没有此数据源的治理总览查看权限；这不表示校验尚未执行。',
    }
  if (batchLoading.value)
    return {
      kind: 'loading',
      title: '正在读取校验状态',
      description: '正在读取最近批次及其覆盖情况。',
    }
  if (batchError.value)
    return {
      kind: 'read-error',
      title: '校验状态读取失败',
      description: '目前无法确认最近检查状态，也无法判断是否存在可核验批次。',
    }
  const batch = latestBatch.value
  if (!batch)
    return {
      kind: 'no-batch',
      title: '尚无可核验的校验批次',
      description:
        snapshot.value?.qualityScore != null
          ? '已有历史评分记录，尚无法确认对应的规则覆盖。'
          : '当前没有可核验的批次记录；这不代表历史上从未执行过校验。',
    }
  if (batch.legacyLimited || batch.status === 'LEGACY')
    return {
      kind: 'legacy',
      title: '历史结果覆盖有限',
      description: '该历史记录缺少可核验的规则版本或覆盖明细，不能作为完整通过依据。',
    }
  if (batch.status === 'RUNNING')
    return {
      kind: 'running',
      title: '正在执行质量校验',
      description: '当前批次尚未完成；完成前不作为完整校验结果。',
    }
  if (batch.status === 'SUCCESS' && batch.complete)
    return {
      kind: 'success',
      title: '校验已完整完成',
      description: '本次请求范围的检查已完整结束。',
    }
  if (batch.status === 'PARTIAL' || batch.status === 'SUCCESS')
    return {
      kind: 'partial',
      title: '校验部分完成',
      description: '本次检查没有覆盖全部适用目标，不能视作完整通过。',
    }
  if (batch.status === 'FAILED')
    return {
      kind: 'failed',
      title: '校验未完成',
      description: '本次检查没有形成完整结果；请查看失败和跳过原因。',
    }
  return {
    kind: 'unknown',
    title: '校验状态未知',
    description: '当前批次返回了无法识别的状态，请查看批次明细。',
  }
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

async function loadSnapshotState(
  expectedSnapshotId = snapshotId.value,
  expectedDatasourceId = datasourceId.value,
) {
  const current = ++requestId
  clearSnapshotState()
  if (!scopeReady.value || !scope.ready.value || !expectedSnapshotId || !expectedDatasourceId) return

  const requests: Promise<void>[] = []
  batchLoading.value = canViewOverview.value
  if (canViewOverview.value)
    requests.push(
      getLatestQualityCheckBatch(expectedSnapshotId)
        .then((response) => {
          if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
          latestBatch.value = response.data ?? null
        })
        .catch(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId))
            batchError.value = '最近一次校验结果读取失败。'
        })
        .finally(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) batchLoading.value = false
        }),
    )

  if (canViewIssues.value) {
    issueLoading.value = true
    requests.push(
      getSnapshotQualityIssueSummary(expectedSnapshotId)
        .then((response) => {
          if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
          issueSummary.value = response.data ?? null
        })
        .catch(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId))
            issueError.value = '待处理问题统计读取失败，无法确认问题数量。'
        })
        .finally(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) issueLoading.value = false
        }),
    )
  } else {
    issueLoading.value = false
  }

  if (canViewRules.value) {
    rulesLoading.value = true
    requests.push(
      getQualityRuleSummary(expectedSnapshotId)
        .then((response) => {
          if (!isCurrent(current, expectedDatasourceId, expectedSnapshotId)) return
          ruleSummary.value = response.data ?? null
        })
        .catch(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId))
            rulesError.value = '当前范围规则摘要读取失败。'
        })
        .finally(() => {
          if (isCurrent(current, expectedDatasourceId, expectedSnapshotId)) rulesLoading.value = false
        }),
    )
  } else {
    rulesLoading.value = false
  }
  await Promise.all(requests)
}

function isCurrent(id: number, expectedDatasourceId: number, expectedSnapshotId: number) {
  return (
    !disposed &&
    scope.ready.value &&
    iamS1.loaded &&
    !iamS1.errorMessage &&
    id === requestId &&
    expectedDatasourceId === datasourceId.value &&
    expectedSnapshotId === snapshotId.value
  )
}

async function refresh() {
  const init = ++initTicket,
    token = auth.token
  loading.value = true
  pageError.value = ''
  try {
    // Refresh evidence in the already verified public range; do not reselect a newer snapshot.
    await Promise.all([context.initialize(), iamS1.load(Boolean(iamS1.errorMessage))])
    if (disposed || init !== initTicket || token !== auth.token) return
    scopeReady.value = true
    await loadSnapshotState()
  } catch {
    if (!disposed && init === initTicket && token === auth.token)
      pageError.value = '治理范围或权限能力读取失败，请重试。'
  } finally {
    if (!disposed && init === initTicket && token === auth.token) loading.value = false
  }
}
async function runCheck() {
  const source = datasourceId.value,
    snapshot = snapshotId.value,
    ticket = scope.capture()
  if (checkLoading.value || !scope.ready.value || !source || !snapshot || !canRunCheck.value) return
  const current = () =>
    !disposed &&
    scope.isCurrent(ticket) &&
    canRunCheck.value &&
    source === datasourceId.value &&
    snapshot === snapshotId.value
  checkLoading.value = true
  try {
    const response = await trackGovernanceWrite(
      auth.token,
      [`quality-check:${source}:${snapshot}`],
      `来源检查 · 源 #${source} / 快照 #${snapshot}`,
      async () => {
        const result = await triggerQualityCheck(snapshot)
        if (result.data?.snapshotId !== snapshot)
          throw new Error('检查回执与请求快照不一致，请先核对最近批次')
        return result
      },
    )
    if (!current()) return
    const result = response.data
    if (result.complete && result.requestedScope === 'FULL' && result.qualityScore != null)
      context.snapshots = context.snapshots.map((item) =>
        item.id === snapshot
          ? { ...item, qualityScore: result.qualityScore!, status: result.snapshotStatus || item.status }
          : item,
      )
    await loadSnapshotState(snapshot, source)
    if (!current()) return
    if (result.status === 'SUCCESS' && result.complete)
      ElMessage.success('质量校验已完整完成；仍需核对问题与业务口径。')
    else if (result.status === 'PARTIAL') ElMessage.warning('质量校验部分完成；本次评分不作为完整结果。')
    else ElMessage.error('质量校验未完成，请查看失败覆盖说明。')
  } catch (cause) {
    if (current()) {
      await loadSnapshotState(snapshot, source)
      if (current())
        pageError.value =
          (cause as any)?.response?.data?.message ||
          (cause instanceof Error ? cause.message : '检查未确认完成，请先核对最近批次，勿重复发起')
    }
  } finally {
    if (current()) checkLoading.value = false
  }
}

function openIssueCenter() {
  if (!snapshotId.value || !datasourceId.value) return
  router.push({
    path: '/admin/governance/issues',
    query: buildContextQuery('datasource-snapshot', {
      datasourceId: datasourceId.value,
      snapshotId: snapshotId.value,
    }),
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

function openKnowledgeGovernance() {
  if (
    !scopeReady.value ||
    !datasourceId.value ||
    !snapshotId.value ||
    !iamS1.canOnDatasource('governance:field:view', datasourceId.value)
  )
    return
  router.push({
    path: '/admin/governance/fields',
    query: { tab: 'tasks', datasourceId: datasourceId.value, snapshotId: snapshotId.value },
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
  return (
    (
      {
        RUNNING: '正在执行',
        SUCCESS: batch.complete ? '完整完成' : '未完整完成',
        PARTIAL: '部分完成',
        FAILED: '校验失败',
      } as Record<string, string>
    )[batch.status] || batch.status
  )
}

function coverageLabel(status: string) {
  return (
    (
      { SUCCESS: '已检查', FAILURE: '检查失败', NOT_APPLICABLE: '不适用', NOT_EXECUTED: '未执行' } as Record<
        string,
        string
      >
    )[status] || status
  )
}

function coverageType(status: string) {
  return (
    (
      { SUCCESS: 'success', FAILURE: 'danger', NOT_APPLICABLE: 'warning', NOT_EXECUTED: 'info' } as Record<
        string,
        string
      >
    )[status] || 'info'
  )
}

function scopeTypeLabel(scope?: string) {
  return (
    ({ GLOBAL: '全局', DATASOURCE: '数据源', TABLE: '表', COLUMN: '字段' } as Record<string, string>)[
      scope || ''
    ] || '范围未知'
  )
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
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function issueStatusCount(status: 'OPEN' | 'CONFIRMED' | 'REOPENED') {
  return issueSummary.value?.statusCounts?.[status] ?? '—'
}

onMounted(refresh)
watch(
  scope.generation,
  () => {
    requestId++
    clearSnapshotState()
    checkLoading.value = false
    coverageOpen.value = false
    loading.value = false
    pageError.value = ''
    if (scopeReady.value) void loadSnapshotState()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  disposed = true
  requestId++
})
</script>

<template>
  <main class="quality-page post-login-page governance-workspace governance-surface">
    <TaskPageHeader
      title="治理总览"
      eyebrow="数据治理 / 质量与知识"
      description-mode="inline"
      description="完善业务知识与字段解释，审核复查后用于文档和问数；下方保留来源质量诊断。"
    >
      <template #actions>
        <el-button
          type="primary"
          :disabled="
            !scopeReady || !snapshotId || !iamS1.canOnDatasource('governance:field:view', datasourceId)
          "
          @click="openKnowledgeGovernance"
        >
          开始知识治理
        </el-button>
        <el-button
          :icon="RefreshCw"
          :loading="loading || batchLoading || issueLoading || rulesLoading"
          @click="refresh"
        >
          刷新
        </el-button>
        <el-button v-if="canReturnToRelease" @click="openReleasePage">返回元数据版本</el-button>
      </template>
    </TaskPageHeader>
    <GovernanceWriteNotice />
    <div class="quality-contract">
      <span>来源质量 SOURCE</span>
      <p>诊断原始结构与只读数据；规则准备、实际执行覆盖和问题结果分别核对。</p>
      <span>业务语义 SEMANTIC</span>
      <p>AI 候选仍需人工依据、审核与同修订复查；来源评分不能代替业务正确性。</p>
    </div>
    <ErrorState v-if="pageError" :message="pageError" @retry="refresh" />
    <template v-else>
      <section
        class="quality-page__status"
        :class="`quality-page__status--${overviewState.kind}`"
        aria-labelledby="quality-current-state"
      >
        <div class="quality-page__status-main">
          <ClipboardCheck class="quality-page__state-icon" :size="26" aria-hidden="true" />
          <div class="quality-page__status-copy">
            <h2 id="quality-current-state">
              {{ overviewState.kind === 'no-batch' ? '暂无可追溯的检查结果' : overviewState.title }}
            </h2>
            <p class="quality-page__status-description" role="status" aria-live="polite">
              {{ overviewState.description }}
            </p>
          </div>
          <div class="quality-page__primary-action">
            <el-button
              :icon="ClipboardCheck"
              :loading="checkLoading"
              :disabled="
                !scope.ready.value || !canRunCheck || checkLoading || latestBatch?.status === 'RUNNING'
              "
              @click="runCheck"
            >
              执行质量校验
            </el-button>
            <span v-if="datasourceId && !canRunCheck" class="quality-page__action-note">
              没有此数据源的执行权限
            </span>
          </div>
        </div>
        <ErrorState v-if="batchError" :message="batchError" @retry="loadSnapshotState()" />
        <p v-if="latestBatch?.errorSummary" class="quality-page__failure" role="alert">
          <AlertTriangle :size="16" aria-hidden="true" />
          {{ latestBatch.errorSummary }}
        </p>
        <p
          v-if="latestBatch && !latestBatch.legacyLimited && latestBatch.status !== 'LEGACY'"
          class="quality-page__execution-summary"
        >
          <strong>{{ latestBatch.executedRuleCount ?? '未记录' }}</strong>
          项实际执行 · {{ latestBatch.failedRuleCount ?? '未记录' }} 项失败 ·
          {{ latestBatch.notApplicableRuleCount ?? '未记录' }} 项不适用
          <span>{{ requestedScopeLabel(latestBatch) }}</span>
        </p>
      </section>
      <div class="quality-page__content-grid">
        <section class="quality-page__panel quality-page__rules" aria-labelledby="quality-rules-title">
          <header class="quality-page__panel-heading">
            <div class="quality-page__section-title">
              <h2 id="quality-rules-title">规则准备</h2>
              <TitleHelp
                label="规则准备"
                text="按已保存配置和当前快照进行只读规划，尚未读取业务数据。业务是否适用、连接和执行权限还需核对。"
              />
            </div>
            <el-button v-if="canViewRules && snapshotId" link type="primary" @click="openRules">
              管理规则
              <ArrowRight :size="14" aria-hidden="true" />
            </el-button>
          </header>
          <ErrorState v-if="rulesError" :message="rulesError" @retry="loadSnapshotState()" />
          <p v-else-if="!datasourceId || !snapshotId" class="quality-page__inline-state">
            选择数据源和快照后查看规则。
          </p>
          <p v-else-if="!canViewRules" class="quality-page__inline-state">
            没有此数据源的「查看治理规则」权限。
          </p>
          <p v-else-if="rulesLoading" class="quality-page__inline-state" role="status">正在读取规则…</p>
          <template v-else-if="ruleSummary">
            <p class="quality-page__rule-line">
              已启用
              <strong>{{ ruleSummary.enabledCount }}</strong>
              项 · 当前快照可参与
              <strong>{{ ruleSummary.applicableCount }}</strong>
              项
            </p>
            <div v-if="rulePreview.length" class="quality-page__rule-preview">
              <article v-for="item in rulePreview" :key="item.ruleId">
                <div>
                  <strong>{{ item.ruleName }}</strong>
                  <small v-if="item.status !== 'READY' || item.reason">
                    {{ item.reason || '当前没有可参与检查的对象' }}
                  </small>
                </div>
                <span>{{ item.scopeType === 'GLOBAL' ? '全局默认' : '本源专用' }}</span>
                <span :class="{ 'quality-page__rule-warning': item.status !== 'READY' }">
                  {{ item.status === 'READY' ? `预计 ${item.plannedTargetCount} 个目标` : '需核对' }}
                </span>
              </article>
            </div>
            <p v-else-if="!ruleSummary.enabledCount" class="quality-page__inline-state">
              暂无启用规则，请先配置检查规则。
            </p>
            <el-button link type="primary" class="quality-page__coverage-link" @click="coverageOpen = true">
              查看检查覆盖
              <ArrowRight :size="14" aria-hidden="true" />
            </el-button>
          </template>
          <p v-else class="quality-page__inline-state">暂无规则信息。</p>
        </section>
        <section class="quality-page__panel quality-page__issues" aria-labelledby="quality-issues-title">
          <header class="quality-page__panel-heading">
            <div class="quality-page__section-title">
              <h2 id="quality-issues-title">待处理问题</h2>
              <TitleHelp
                label="问题数量"
                text="这里统计待处理问题记录。尚未完成可核验检查时，零记录不代表数据质量通过。"
              />
            </div>
            <el-button v-if="canViewIssues && snapshotId" link type="primary" @click="openIssueCenter">
              问题中心
              <ArrowRight :size="14" aria-hidden="true" />
            </el-button>
          </header>
          <ErrorState v-if="issueError" :message="issueError" @retry="loadSnapshotState()" />
          <p v-else-if="!datasourceId || !snapshotId" class="quality-page__inline-state">
            选择数据源和快照后查看问题。
          </p>
          <p v-else-if="!canViewIssues" class="quality-page__inline-state">
            没有此数据源的「查看质量问题」权限。
          </p>
          <p v-else-if="issueLoading" class="quality-page__inline-state" role="status">正在读取问题…</p>
          <template v-else-if="issueSummary">
            <div class="quality-page__issue-total">
              <strong>{{ issueSummary.totalUnresolved }}</strong>
              <span>项待处理</span>
            </div>
            <div v-if="issueSummary.totalUnresolved === 0" class="quality-page__empty-issues">
              <Inbox :size="34" aria-hidden="true" />
              <p>暂无待处理问题</p>
            </div>
            <template v-else>
              <div class="quality-page__issue-counts">
                <span>待确认 {{ issueStatusCount('OPEN') }}</span>
                <span>已确认 {{ issueStatusCount('CONFIRMED') }}</span>
                <span>重新打开 {{ issueStatusCount('REOPENED') }}</span>
              </div>
              <article
                v-for="issue in recentIssues.slice(0, 3)"
                :key="issue.id"
                class="quality-page__issue-row"
              >
                <strong>{{ issue.issueDescription }}</strong>
                <small>
                  {{ issue.tableName }}
                  <template v-if="issue.columnName">.{{ issue.columnName }}</template>
                  · {{ severityLabel(issue.severity) }}
                </small>
              </article>
            </template>
          </template>
          <p v-else class="quality-page__inline-state">暂无问题统计结果。</p>
        </section>
      </div>
      <details class="quality-page__details">
        <summary>
          <h2>检查记录</h2>
          <span v-if="latestBatch">
            {{ batchStateLabel(latestBatch) }} · {{ formatTime(latestBatch.startedAt) }}
          </span>
          <span v-else-if="batchLoading">正在读取</span>
          <span v-else-if="batchError">读取失败</span>
          <span v-else-if="!canViewOverview">无查看权限</span>
          <span v-else>暂无可追溯批次</span>
        </summary>
        <div v-if="canViewOverview && snapshot" class="quality-page__scope-context">
          <span>快照 v{{ snapshot.snapshotVersion }}</span>
          <BusinessStatusBadge :status="snapshot.status" :label="snapshotStatusLabel(snapshot.status)" />
          <span v-if="snapshot.qualityScore != null">
            {{ snapshotScoreLabel }}
            {{
              latestBatch?.complete && latestBatch.requestedScope === 'FULL' && latestBatch.totalScore != null
                ? latestBatch.totalScore
                : snapshot.qualityScore
            }}
            分
          </span>
        </div>
        <p
          v-if="!latestBatch && canViewOverview && snapshot?.qualityScore != null"
          class="quality-page__inline-state"
        >
          已有历史评分记录，尚无法确认对应的规则覆盖。
        </p>
        <ErrorState v-if="batchError" :message="batchError" @retry="loadSnapshotState()" />
        <p v-else-if="!canViewOverview" class="quality-page__inline-state">没有查看校验批次的权限。</p>
        <p v-else-if="batchLoading" class="quality-page__inline-state">正在读取批次…</p>
        <p v-else-if="!latestBatch" class="quality-page__inline-state" aria-label="最近批次执行情况">
          暂无可核验批次。
        </p>
        <div v-else class="quality-page__record">
          <h3>校验明细 · {{ requestedScopeLabel(latestBatch) }}</h3>
          <p v-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'">
            历史覆盖有限，实际执行数不可核验。
          </p>
          <p v-else>
            {{ latestBatch.executedRuleCount ?? '—' }} 项实际执行 ·
            {{ latestBatch.failedRuleCount ?? '—' }} 项失败 ·
            {{ latestBatch.notApplicableRuleCount ?? '—' }} 项不适用
          </p>
          <p
            v-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'"
            class="quality-page__history-note"
            role="status"
          >
            此历史结果没有可证明的规则配置版本和执行覆盖；当前配置不会被用来补造历史依据。
          </p>
          <div
            v-if="Object.keys(latestBatch.dimensionScores || {}).length"
            class="quality-page__dimension-list"
          >
            <div
              v-for="[dimension, score] in Object.entries(latestBatch.dimensionScores || {})"
              :key="dimension"
            >
              <span>{{ qualityDimensionLabel(dimension) }}</span>
              <strong>{{ score }} 分</strong>
            </div>
          </div>
          <div v-if="latestBatch.rules?.length" class="quality-page__coverage-list">
            <article
              v-for="rule in latestBatch.rules"
              :key="`${rule.ruleId}-${rule.ruleVersion}`"
              class="quality-page__coverage-row"
            >
              <div class="quality-page__coverage-rule">
                <strong>{{ rule.ruleName }}</strong>
                <span>
                  {{ qualityDimensionLabel(rule.dimension) }} · {{ scopeTypeLabel(rule.scopeType) }}
                  <template v-if="rule.targetTableName">· {{ rule.targetTableName }}</template>
                  <template v-if="rule.targetColumnName">.{{ rule.targetColumnName }}</template>
                </span>
              </div>
              <div class="quality-page__coverage-status">
                <el-tag size="small" :type="coverageType(rule.status)">
                  {{ coverageLabel(rule.status) }}
                </el-tag>
                <small>
                  v{{ rule.ruleVersion ?? '—' }} · 检查 {{ rule.checkedTargetCount ?? '—' }}/{{
                    rule.targetCount ?? '—'
                  }}
                  个目标 · {{ rule.issueCount }} 个问题
                </small>
                <p
                  v-if="rule.reason"
                  :class="{
                    'quality-page__coverage-reason--error': rule.status === 'FAILURE',
                    'quality-page__coverage-reason--skipped':
                      rule.status === 'NOT_EXECUTED' || rule.status === 'NOT_APPLICABLE',
                  }"
                  :role="rule.status === 'FAILURE' ? 'alert' : 'status'"
                >
                  {{ rule.reason }}
                </p>
              </div>
            </article>
          </div>
          <p
            v-else-if="latestBatch.legacyLimited || latestBatch.status === 'LEGACY'"
            class="quality-page__inline-state"
          >
            该历史结果没有规则覆盖记录。
          </p>
          <p v-else class="quality-page__inline-state">本批次没有规则覆盖记录。</p>
        </div>
      </details>
    </template>
    <el-drawer
      v-model="coverageOpen"
      title="当前快照检查覆盖"
      size="min(680px, 96vw)"
      append-to-body
      destroy-on-close
      class="governance-drawer"
    >
      <RuleApplicabilitySummary
        :summary="ruleSummary"
        :loading="rulesLoading"
        :error="rulesError"
        @retry="loadSnapshotState()"
      />
    </el-drawer>
  </main>
</template>

<style scoped>
.quality-page {
  display: grid;
  gap: 22px;
  min-width: 0;
}
.quality-contract {
  display: grid;
  grid-template-columns: auto 1fr auto 1fr;
  gap: 12px 18px;
  align-items: start;
  padding: 18px 20px;
  border: 1px solid var(--do-line);
  border-radius: 12px;
  background: var(--do-surface);
}
.quality-contract > span {
  font-size: 11px;
  font-weight: 800;
  color: var(--do-primary-strong);
  padding-top: 3px;
}
.quality-contract p {
  margin: 0;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.8;
}
.quality-page__execution-summary {
  font-size: 12px;
  line-height: 1.8;
  color: var(--do-muted);
  margin: 16px 0 0;
}
.quality-page__execution-summary strong {
  color: var(--do-ink);
  font-size: 18px;
}
.quality-page__execution-summary span {
  margin-left: 16px;
}
.quality-page__status {
  padding: 24px !important;
  border: 1px solid var(--do-line) !important;
  border-radius: 12px;
  background: var(--do-surface);
}
.quality-page__content-grid {
  padding: 0 !important;
  gap: 20px !important;
}
.quality-page__panel {
  padding: 22px !important;
  border: 1px solid var(--do-line) !important;
  border-radius: 12px;
  background: var(--do-surface);
}
.quality-page__details {
  padding: 20px !important;
  background: var(--do-surface);
  border: 1px solid var(--do-line) !important;
  border-radius: 12px;
}
@media (max-width: 1000px) {
  .quality-contract {
    grid-template-columns: auto 1fr;
  }
}
@media (max-width: 760px) {
  .quality-contract {
    grid-template-columns: 1fr;
  }
  .quality-page__status {
    padding: 18px !important;
  }
  .quality-page__panel {
    padding: 18px !important;
  }
}

.quality-page__status {
  padding: 16px 0 26px;
  border-bottom: 1px solid var(--do-line);
}
.quality-page__status-main {
  display: flex;
  align-items: center;
  gap: 18px;
}
.quality-page__state-icon {
  box-sizing: content-box;
  flex: 0 0 auto;
  padding: 14px;
  border-radius: 10px;
  color: var(--do-primary);
  background: var(--do-primary-soft);
}
.quality-page__status-copy {
  min-width: 0;
  flex: 1;
}
.quality-page__status-copy h2 {
  margin: 0;
  color: var(--do-ink);
  font-size: 23px;
  line-height: 1.4;
}
.quality-page__status-description {
  margin: 6px 0 0;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.6;
}
.quality-page__primary-action {
  display: grid;
  justify-items: end;
  gap: 7px;
}
.quality-page__action-note {
  font-size: 12px;
  color: var(--do-muted);
}
.quality-page__content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  gap: 32px;
  padding: 10px 0;
}
.quality-page__panel {
  min-width: 0;
}
.quality-page__issues {
  padding-left: 30px;
  border-left: 1px solid var(--do-line);
}
.quality-page__panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 16px;
}
.quality-page__section-title {
  display: flex;
  align-items: center;
  gap: 6px;
}
.quality-page__panel-heading h2 {
  margin: 0;
  font-size: 17px;
  color: var(--do-ink);
}
.quality-page__rule-line {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--do-muted);
}
.quality-page__rule-line strong {
  color: var(--do-ink);
}
.quality-page__rule-preview article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 76px 95px;
  gap: 10px;
  align-items: center;
  min-height: 56px;
  border-bottom: 1px solid var(--do-line);
  font-size: 12px;
  color: var(--do-muted);
}
.quality-page__rule-preview strong {
  color: var(--do-ink);
  font-size: 13px;
  font-weight: 600;
}
.quality-page__rule-preview small {
  display: block;
  margin-top: 4px;
  color: var(--do-warning);
  line-height: 1.5;
}
.quality-page__rule-warning {
  color: var(--do-warning);
}
.quality-page__coverage-link {
  margin-top: 16px;
}
.quality-page__issue-total {
  display: flex;
  align-items: baseline;
  gap: 9px;
}
.quality-page__issue-total strong {
  font-size: 30px;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
  color: var(--do-ink);
}
.quality-page__issue-total span {
  color: var(--do-muted);
  font-size: 12px;
}
.quality-page__empty-issues {
  display: grid;
  justify-items: center;
  gap: 10px;
  padding: 22px 12px;
  color: var(--do-muted);
}
.quality-page__empty-issues p {
  margin: 0;
  font-size: 13px;
}
.quality-page__issue-counts {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--do-muted);
  margin: 10px 0;
}
.quality-page__issue-row {
  padding: 12px 0;
  border-bottom: 1px solid var(--do-line);
}
.quality-page__issue-row strong {
  display: block;
  font-size: 13px;
  color: var(--do-ink);
}
.quality-page__issue-row small {
  display: block;
  color: var(--do-muted);
  margin-top: 5px;
}
.quality-page__details {
  border-top: 1px solid var(--do-line);
  padding-top: 18px;
}
.quality-page__details > summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  color: var(--do-muted);
  font-size: 12px;
}
.quality-page__details h2 {
  margin: 0;
  font-size: 15px;
  color: var(--do-ink);
}
.quality-page__inline-state,
.quality-page__history-note {
  font-size: 13px;
  color: var(--do-muted);
  line-height: 1.7;
}
.quality-page__scope-context {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin: 16px 0 8px;
  font-size: 12px;
  color: var(--do-muted);
}
.quality-page__record {
  padding-top: 15px;
}
.quality-page__record h3 {
  font-size: 14px;
  color: var(--do-ink);
}
.quality-page__record > p {
  font-size: 12px;
  color: var(--do-muted);
}
.quality-page__failure {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--do-danger);
  font-size: 13px;
}
.quality-page__dimension-list {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 13px;
  margin: 16px 0;
}
.quality-page__dimension-list strong {
  margin-left: 10px;
}
.quality-page__coverage-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
  padding: 14px 0;
  border-top: 1px solid var(--do-line);
}
.quality-page__coverage-rule strong {
  display: block;
  font-size: 13px;
}
.quality-page__coverage-rule span,
.quality-page__coverage-status small,
.quality-page__coverage-status p {
  display: block;
  color: var(--do-muted);
  font-size: 12px;
  line-height: 1.6;
  margin-top: 5px;
}
.quality-page__coverage-reason--error {
  color: var(--do-danger) !important;
}
@media (max-width: 760px) {
  .quality-page__status-main {
    flex-wrap: wrap;
  }
  .quality-page__primary-action {
    width: 100%;
    justify-items: start;
  }
  .quality-page__content-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 25px;
  }
  .quality-page__issues {
    padding: 22px 0 0;
    border-left: 0;
    border-top: 1px solid var(--do-line);
  }
  .quality-page__coverage-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 6px;
  }
}
</style>
