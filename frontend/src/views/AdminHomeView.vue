<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CheckCircle2, CircleAlert, Clock3, Database, LoaderCircle } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useIamS1Store } from '../stores/iamS1'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'
import { getBatchDatasourceReadiness, listSimpleDatasources, type DatasourceReadiness, type DatasourceSimpleItem } from '../api/admin/datasource'
import { listIamS1AccessRequestQueue } from '../api/iamS1'
import TaskPageHeader from '../components/admin/TaskPageHeader.vue'
import TitleHelp from '../components/admin/TitleHelp.vue'
import ReadinessPanel from '../components/admin/ReadinessPanel.vue'
import BusinessStatusBadge from '../components/admin/BusinessStatusBadge.vue'
import ActivityTimeline from '../components/admin/ActivityTimeline.vue'
import LoadingState from '../components/common/LoadingState.vue'
import ErrorState from '../components/common/ErrorState.vue'

const router = useRouter()
const iamS1 = useIamS1Store()
const stats = ref<DashboardStats | null>(null)
const readiness = ref<DatasourceReadiness[]>([])
const loading = ref(true)
const statsLoading = ref(true)
const readinessLoading = ref(true)
const statsError = ref('')
const readinessError = ref('')
const readinessPartialError = ref('')
const sources = ref<DatasourceSimpleItem[]>([])
const pendingApprovalCount = ref<number | null>(null)
const approvalLoading = ref(false)
const approvalError = ref('')
interface PendingItem {
  key: string
  title: string
  description: string
  to: { path: string; query?: Record<string, string> }
  count?: number
}

/** 工作台入口功能：与后端 DashboardController 的 admin:workbench:view 一致。 */
const canViewWorkbench = computed(() => iamS1.hasGlobal('admin:workbench:view'))
const readyCount = computed(() => readiness.value.filter((item) => item.askable).length)
const blockedItems = computed(() => readiness.value.filter((item) => !item.askable && item.blockReasons.length))
const progressingCount = computed(() => readiness.value.filter((item) => !item.askable && !item.blockReasons.length).length)
const unknownCount = computed(() => Math.max(0, sources.value.length - readiness.value.length))
const orderedReadiness = computed(() => [...readiness.value].sort((a, b) => Number(a.askable) - Number(b.askable)))
const readinessCountsKnown = computed(() => !readinessLoading.value && !readinessError.value && !readinessPartialError.value && !unknownCount.value)
const allReady = computed(() => Boolean(sources.value.length) && !readinessLoading.value && !readinessError.value && !readinessPartialError.value && !unknownCount.value && readyCount.value === sources.value.length)
const attentionTitle = computed(() => {
  if (readinessLoading.value) return '正在读取准备情况'
  if (readinessError.value) return '准备情况暂不可用'
  if (readinessPartialError.value || unknownCount.value) return '部分数据源的准备情况尚未确认'
  if (!sources.value.length) return '先接入第一个数据源'
  if (blockedItems.value.length) return `${blockedItems.value.length} 个数据源需要处理`
  if (progressingCount.value) return `${progressingCount.value} 个数据源仍在准备中`
  return allReady.value ? '全部数据源已准备就绪' : '当前没有阻断项'
})
const summaryTone = computed<'loading' | 'error' | 'partial' | 'empty' | 'blocked' | 'progressing' | 'ready'>(() => {
  if (readinessLoading.value) return 'loading'
  if (readinessError.value) return 'error'
  if (readinessPartialError.value || unknownCount.value) return 'partial'
  if (!sources.value.length) return 'empty'
  if (blockedItems.value.length) return 'blocked'
  if (progressingCount.value) return 'progressing'
  return 'ready'
})
const summaryDescription = computed(() => {
  if (readinessLoading.value) return '正在确认数据源状态，请稍候。'
  if (readinessError.value) return readinessError.value
  if (readinessPartialError.value || unknownCount.value) {
    return [
      readinessPartialError.value,
      unknownCount.value ? `另有 ${unknownCount.value} 个数据源暂未读取到状态，已保留其他结果。` : '',
    ].filter(Boolean).join(' ')
  }
  if (!sources.value.length) return '连接并采集数据后，可以在这里查看准备条件。'
  if (blockedItems.value.length) {
    return `${blockedItems.value[0]?.datasourceName || '当前数据源'}：${blockedItems.value[0]?.blockReasons[0]?.message || '打开数据源详情查看待处理条件。'}`
  }
  if (progressingCount.value) return `${progressingCount.value} 个数据源正在完成上线准备，请稍后刷新状态。`
  if (allReady.value) return `${readyCount.value} 个数据源已具备问数条件。`
  return '打开数据源详情，查看具体原因和处理入口。'
})
const datasourceTotalText = computed(() => readinessError.value ? '—' : String(sources.value.length))
const readyCountText = computed(() => readinessCountsKnown.value ? String(readyCount.value) : '—')
const blockedCountText = computed(() => readinessCountsKnown.value ? String(blockedItems.value.length) : '—')
const issueCountText = computed(() => stats.value ? String(stats.value.openIssues) : '—')
const readinessStateTitle = computed(() => {
  if (readinessLoading.value) return '数据源准备情况正在读取'
  if (readinessError.value) return '数据源准备情况读取失败'
  if (readinessPartialError.value || unknownCount.value) return '部分数据源的准备情况尚未确认'
  return ''
})
const statsStateTitle = computed(() => {
  if (statsLoading.value) return '治理统计正在读取'
  if (statsError.value) return '治理统计读取失败'
  return ''
})
const readinessDimensionLabels = ['连接', '采集', '治理', '知识', '授权'] as const
const activityItems = computed(() => (stats.value?.recentActivities || []).map((item) => ({
  time: item.time,
  action: item.type,
  description: item.description,
})))
const canViewApprovals = computed(() => iamS1.hasGlobal('security:approval:view'))
const canViewKnowledge = computed(() => iamS1.hasGlobal('knowledge:view')
  || iamS1.datasourcesWithFunction('knowledge:view').length > 0)
const canViewReleases = computed(() => iamS1.hasGlobal('metadata:release:view')
  || iamS1.datasourcesWithFunction('metadata:release:view').length > 0)
const pendingLoading = computed(() => readinessLoading.value || statsLoading.value || approvalLoading.value)
const pendingError = computed(() => {
  const errors = [
    readinessError.value,
    statsError.value,
    approvalError.value,
  ].filter(Boolean)
  return errors.length ? errors.join('；') : ''
})
const pendingItems = computed<PendingItem[]>(() => {
  const items: PendingItem[] = []
  for (const source of blockedItems.value.slice(0, 5)) {
    const reason = source.blockReasons[0]
    items.push({
      key: `datasource-${source.datasourceId}`,
      title: `${source.datasourceName} 需要处理`,
      description: reason?.message || '打开数据源详情查看待处理条件。',
      to: reason?.actionPath
        ? { path: reason.actionPath }
        : { path: `/admin/data-sources/${source.datasourceId}` },
    })
  }
  if (stats.value && stats.value.openIssues > 0) {
    items.push({
      key: 'governance-issues',
      title: `${stats.value.openIssues} 个治理问题待处理`,
      description: '进入问题中心分派、确认或处理。',
      to: { path: '/admin/governance/issues' },
      count: stats.value.openIssues,
    })
  }
  if (canViewApprovals.value && pendingApprovalCount.value !== null && pendingApprovalCount.value > 0) {
    items.push({
      key: 'access-approvals',
      title: `${pendingApprovalCount.value} 条访问申请待审批`,
      description: '审批负责数据源上的访问申请。',
      to: { path: '/admin/access/iam-approvals' },
      count: pendingApprovalCount.value,
    })
  }
  return items
})

async function loadStats() {
  stats.value = null
  statsLoading.value = true
  statsError.value = ''
  if (!canViewWorkbench.value) {
    statsError.value = '没有“查看工作台”功能：需要角色拥有该功能。'
    statsLoading.value = false
    return
  }
  try {
    const result = await getDashboardStats()
    stats.value = result.data
  } catch (cause) {
    statsError.value = cause instanceof Error ? cause.message : '工作台统计加载失败'
  } finally {
    statsLoading.value = false
  }
}

async function loadReadiness() {
  readinessLoading.value = true
  readinessError.value = ''
  readinessPartialError.value = ''
  try {
    const resultSources = await listSimpleDatasources()
    sources.value = resultSources.data
    if (!sources.value.length) {
      readiness.value = []
      return
    }
    const result = await getBatchDatasourceReadiness(sources.value.map((item) => item.id))
    readiness.value = result.data || []
    if (result.failedDatasourceIds.length) {
      readinessPartialError.value = `${result.failedDatasourceIds.length} 个数据源的就绪度读取失败，已保留其余数据源结果。`
    }
  } catch (cause) {
    readinessError.value = cause instanceof Error ? cause.message : '数据源就绪度加载失败'
  } finally {
    readinessLoading.value = false
  }
}

async function loadApprovals() {
  pendingApprovalCount.value = null
  approvalError.value = ''
  if (!canViewApprovals.value) {
    approvalLoading.value = false
    return
  }
  approvalLoading.value = true
  try {
    const result = await listIamS1AccessRequestQueue('PENDING', 1, 1)
    pendingApprovalCount.value = result.data?.total ?? 0
  } catch (cause) {
    approvalError.value = cause instanceof Error ? cause.message : '访问审批数量读取失败'
  } finally {
    approvalLoading.value = false
  }
}

async function reloadPending() {
  await Promise.all([loadStats(), loadReadiness(), loadApprovals()])
}

async function load() {
  loading.value = true
  await Promise.all([loadStats(), loadReadiness(), loadApprovals()])
  loading.value = false
}

function openDatasource(item: DatasourceReadiness) {
  router.push({ path: '/admin/data-sources/' + item.datasourceId })
}

function sourceDatabaseName(id: number) {
  return sources.value.find((source) => source.id === id)?.databaseName
}

function fullSourceLabel(name: string, databaseName?: string) {
  return databaseName ? `${name} · ${databaseName}` : name
}

onMounted(load)
</script>

<template>
  <div class="admin-page workbench-page">
    <TaskPageHeader title="工作台" description="这里汇总需要你处理的事项；先处理阻塞，再查看数据源准备情况。" />
    <LoadingState v-if="loading" variant="skeleton" :rows="5" />
    <section v-else class="workbench-pending do-glass-panel" aria-labelledby="workbench-pending-title">
      <div class="section-heading">
        <div class="section-title">
          <TitleHelp label="待处理事项" text="只汇总真实读取到的阻断、治理问题和待审批申请；读取失败时显示原因，不显示为零。" />
          <h2 id="workbench-pending-title">待处理事项</h2>
        </div>
        <el-button text :loading="pendingLoading" @click="reloadPending">刷新</el-button>
      </div>
      <p v-if="pendingError" class="workbench-pending__error" role="alert">{{ pendingError }}</p>
      <LoadingState v-if="pendingLoading && !pendingItems.length" text="正在汇总待处理事项…" />
      <ul v-else-if="pendingItems.length" class="workbench-pending__list">
        <li v-for="item in pendingItems" :key="item.key" class="workbench-pending__item">
          <div class="workbench-pending__copy">
            <strong>{{ item.title }}</strong>
            <span>{{ item.description }}</span>
          </div>
          <RouterLink class="workbench-pending__action" :to="item.to">
            {{ item.count ? '去处理' : '查看' }}<ArrowRight :size="14" aria-hidden="true" />
          </RouterLink>
        </li>
      </ul>
      <p v-else class="workbench-empty">当前没有需要你立即处理的阻断事项。</p>
      <div class="workbench-pending__shortcuts">
        <RouterLink v-if="canViewReleases" :to="{ path: '/admin/releases' }">检查待发布版本</RouterLink>
        <RouterLink v-if="canViewKnowledge" :to="{ path: '/admin/semantics/knowledge', query: { tab: 'review' } }">审核知识文档</RouterLink>
        <RouterLink v-if="canViewApprovals" :to="{ path: '/admin/access/iam-approvals' }">处理访问申请</RouterLink>
      </div>
    </section>
    <template v-if="!loading">
      <dl class="workbench-stats" aria-label="工作台概览">
        <div class="workbench-stat do-glass-panel" :title="readinessStateTitle || undefined">
          <div class="workbench-stat__head"><dt>数据源</dt></div>
          <dd :class="{ 'is-pending': datasourceTotalText === '—' }">{{ datasourceTotalText }}</dd>
        </div>
        <div class="workbench-stat do-glass-panel" :title="readinessStateTitle || undefined">
          <div class="workbench-stat__head"><dt>可问数</dt></div>
          <dd :class="{ 'is-pending': readyCountText === '—' }">{{ readyCountText }}</dd>
        </div>
        <div class="workbench-stat do-glass-panel" :title="readinessStateTitle || undefined">
          <div class="workbench-stat__head"><dt>待处理数据源</dt></div>
          <dd :class="{ 'is-pending': blockedCountText === '—' }">{{ blockedCountText }}</dd>
        </div>
        <div class="workbench-stat do-glass-panel" :title="statsStateTitle || undefined">
          <div class="workbench-stat__head">
            <dt>治理问题</dt>
            <RouterLink class="workbench-stat__link" to="/admin/governance/issues">查看治理问题<ArrowRight :size="12" aria-hidden="true" /></RouterLink>
          </div>
          <dd :class="{ 'is-pending': issueCountText === '—' }">{{ issueCountText }}</dd>
        </div>
      </dl>

      <section class="workbench-summary do-glass-panel" :class="`workbench-summary--${summaryTone}`" aria-live="polite">
        <span class="workbench-summary__icon" aria-hidden="true">
          <LoaderCircle v-if="summaryTone === 'loading'" :size="20" class="workbench-summary__spin" />
          <CheckCircle2 v-else-if="summaryTone === 'ready'" :size="20" />
          <Clock3 v-else-if="summaryTone === 'progressing'" :size="20" />
          <Database v-else-if="summaryTone === 'empty'" :size="20" />
          <CircleAlert v-else :size="20" />
        </span>
        <div class="workbench-summary__copy">
          <strong>{{ attentionTitle }}</strong>
          <p>{{ summaryDescription }}</p>
        </div>
        <el-button type="primary" :icon="Database" @click="router.push('/admin/data-sources')">管理数据源</el-button>
      </section>

      <div class="workbench-columns">
        <section class="workbench-section workbench-sources do-glass-panel" aria-labelledby="workbench-sources">
          <div class="section-heading"><div class="section-title"><TitleHelp label="数据源准备情况" text="需要处理的数据源排在前面。打开详情查看完整条件。" /><h2 id="workbench-sources">数据源准备情况</h2></div><el-button text :loading="readinessLoading" @click="loadReadiness">刷新</el-button></div>
          <div class="workbench-section__body">
            <el-alert v-if="readinessPartialError" :title="readinessPartialError" type="warning" :closable="false" show-icon />
            <ErrorState v-if="readinessError" :message="readinessError" @retry="loadReadiness" />
            <LoadingState v-else-if="readinessLoading" text="正在读取准备情况..." />
            <p v-else-if="!sources.length" class="workbench-empty">还没有数据源。通过上方“管理数据源”开始接入。</p>
            <div v-else class="workbench-source-list">
              <article v-for="item in orderedReadiness" :key="item.datasourceId" class="workbench-source">
                <div class="workbench-source__top">
                  <el-tooltip :content="fullSourceLabel(item.datasourceName, sourceDatabaseName(item.datasourceId))" placement="top-start" effect="light" :show-after="300" :trigger="['hover', 'focus']" popper-class="workbench-source-tooltip">
                    <div class="workbench-source__name" tabindex="0" :aria-label="fullSourceLabel(item.datasourceName, sourceDatabaseName(item.datasourceId))">
                      <Database :size="18" aria-hidden="true" />
                      <div class="workbench-source__identity">
                        <strong class="notranslate" translate="no">{{ item.datasourceName }}</strong>
                        <span class="notranslate" translate="no">{{ sourceDatabaseName(item.datasourceId) }}</span>
                      </div>
                    </div>
                  </el-tooltip>
                  <div class="workbench-source__aside">
                    <div class="workbench-source__status">
                      <BusinessStatusBadge :status="item.askable ? 'PUBLISHED' : item.stage" :label="item.askable ? '可问数' : item.stageLabel" />
                      <span v-if="item.blockReasons.length" class="workbench-source__reason">{{ item.blockReasons[0]?.message }}</span>
                    </div>
                    <el-button class="workbench-source__action" link type="primary" @click="openDatasource(item)">查看详情<ArrowRight :size="14" aria-hidden="true" /></el-button>
                  </div>
                </div>
                <ReadinessPanel :readiness="item" compact />
              </article>
              <article v-for="source in sources.filter(source => !readiness.some(item => item.datasourceId === source.id))" :key="'unknown-' + source.id" class="workbench-source">
                <div class="workbench-source__top">
                  <el-tooltip :content="fullSourceLabel(source.name, source.databaseName)" placement="top-start" effect="light" :show-after="300" :trigger="['hover', 'focus']" popper-class="workbench-source-tooltip">
                    <div class="workbench-source__name" tabindex="0" :aria-label="fullSourceLabel(source.name, source.databaseName)">
                      <Database :size="18" aria-hidden="true" />
                      <div class="workbench-source__identity">
                        <strong class="notranslate" translate="no">{{ source.name }}</strong>
                        <span class="notranslate" translate="no">{{ source.databaseName }}</span>
                      </div>
                    </div>
                  </el-tooltip>
                  <div class="workbench-source__aside">
                    <div class="workbench-source__status"><span class="workbench-source__reason" role="status">准备情况暂不可用</span></div>
                    <el-button class="workbench-source__action" link type="primary" @click="router.push('/admin/data-sources/' + source.id)">查看详情<ArrowRight :size="14" aria-hidden="true" /></el-button>
                  </div>
                </div>
                <div class="workbench-source__unknown-conditions" role="status" aria-label="五项准备条件尚未确认">
                  <span v-for="label in readinessDimensionLabels" :key="label" class="workbench-unknown-chip"><CircleAlert :size="12" aria-hidden="true" />{{ label }}<small>未确认</small></span>
                </div>
              </article>
            </div>
          </div>
        </section>
        <section class="workbench-section workbench-activity do-glass-panel">
          <div class="section-heading"><h2>最近活动</h2></div>
          <div class="workbench-section__body">
            <ErrorState v-if="statsError" :message="statsError" @retry="loadStats" />
            <LoadingState v-else-if="statsLoading" />
            <ActivityTimeline v-else-if="activityItems.length" :items="activityItems" />
            <div v-else class="workbench-activity__empty"><Clock3 :size="22" aria-hidden="true" /><p>暂无活动记录</p></div>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
<style scoped>
.workbench-page { --workbench-gap: 14px; }
.workbench-page :deep(.task-page-header) { margin-bottom: 14px; }
.workbench-page :deep(.task-page-header h1) { font-size: clamp(28px, 2.4vw, 32px); line-height: 1.2; letter-spacing: -.025em; }

.workbench-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--workbench-gap); margin: 0 0 var(--workbench-gap); padding: 0; }
.workbench-stat { min-width: 0; min-height: 94px; display: flex; flex-direction: column; justify-content: space-between; gap: 10px; padding: 14px 18px 16px; border-radius: 16px; background: var(--do-glass-panel-bg); box-shadow: var(--do-shadow); }
.workbench-stat__head { min-width: 0; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.workbench-stat dt { min-width: 0; overflow: hidden; color: var(--do-muted); font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.workbench-stat dd { margin: 0; color: var(--do-ink); font-size: clamp(28px, 2.4vw, 32px); font-weight: 750; line-height: 1; letter-spacing: -.03em; font-variant-numeric: tabular-nums; }
.workbench-stat dd.is-pending { color: var(--do-muted); }
.workbench-stat__link { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 3px; color: var(--do-primary-strong); font-size: 11.5px; font-weight: 600; white-space: nowrap; }
.workbench-stat__link:hover { text-decoration: underline; }

.workbench-pending { display: grid; gap: 12px; margin-bottom: var(--workbench-gap); padding: 16px 18px; border-radius: 16px; background: var(--do-glass-panel-bg); box-shadow: var(--do-shadow); }
.workbench-pending__error { margin: 0; color: var(--do-danger); font-size: 12px; line-height: 1.5; }
.workbench-pending__list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.workbench-pending__item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--do-line); border-radius: 10px; background: var(--do-surface); }
.workbench-pending__copy { display: grid; gap: 2px; min-width: 0; }
.workbench-pending__copy strong { color: var(--do-ink); font-size: 13px; }
.workbench-pending__copy span { color: var(--do-muted); font-size: 12px; line-height: 1.5; }
.workbench-pending__action { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 3px; color: var(--do-primary-strong); font-size: 12px; font-weight: 700; }
.workbench-pending__action:hover { text-decoration: underline; }
.workbench-pending__shortcuts { display: flex; flex-wrap: wrap; gap: 8px 16px; padding-top: 4px; border-top: 1px dashed var(--do-line); }
.workbench-pending__shortcuts a { color: var(--do-primary-strong); font-size: 12px; font-weight: 600; }
.workbench-pending__shortcuts a:hover { text-decoration: underline; }

.workbench-summary { min-height: 84px; display: grid; grid-template-columns: 40px minmax(0, 1fr) auto; align-items: center; gap: 14px 16px; margin-bottom: var(--workbench-gap); padding: 14px 18px; border-radius: 16px; background: var(--do-glass-panel-bg); box-shadow: var(--do-shadow); }
.workbench-summary__icon { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 12px; color: var(--do-primary-strong); background: var(--do-primary-soft); }
.workbench-summary__copy { min-width: 0; }
.workbench-summary strong { display: block; color: var(--do-ink); font-size: 15px; line-height: 1.4; }
.workbench-summary p { margin: 3px 0 0; color: var(--do-muted); font-size: 12.5px; line-height: 1.6; overflow-wrap: anywhere; }
.workbench-summary > .el-button { justify-self: end; }
.workbench-summary--ready { background: color-mix(in srgb, var(--do-success-soft) 74%, white); border-color: color-mix(in srgb, var(--do-success) 22%, var(--do-line)); box-shadow: none; }
.workbench-summary--ready .workbench-summary__icon { color: var(--do-success); background: color-mix(in srgb, var(--do-success) 12%, white); }
.workbench-summary--ready strong { color: color-mix(in srgb, var(--do-success) 72%, var(--do-ink)); }
.workbench-summary--partial { background: color-mix(in srgb, var(--do-warning-soft) 38%, white); border-color: color-mix(in srgb, var(--do-warning) 18%, var(--do-line)); }
.workbench-summary--partial .workbench-summary__icon { color: var(--do-warning); background: var(--do-warning-soft); }
.workbench-summary--blocked { background: color-mix(in srgb, var(--do-warning-soft) 62%, white); border-color: color-mix(in srgb, var(--do-warning) 26%, var(--do-line)); }
.workbench-summary--blocked .workbench-summary__icon { color: var(--do-warning); background: var(--do-warning-soft); }
.workbench-summary--blocked strong { color: color-mix(in srgb, var(--do-warning) 70%, var(--do-ink)); }
.workbench-summary--error { background: var(--do-danger-soft); border-color: color-mix(in srgb, var(--do-danger) 24%, var(--do-line)); }
.workbench-summary--error .workbench-summary__icon { color: var(--do-danger); background: color-mix(in srgb, var(--do-danger) 8%, white); }
.workbench-summary--error strong { color: var(--do-danger); }
.workbench-summary__spin { animation: workbench-spin 900ms linear infinite; }
@keyframes workbench-spin { to { transform: rotate(360deg); } }

.workbench-columns { display: grid; grid-template-columns: minmax(0, 2.3fr) minmax(300px, 1fr); grid-template-rows: minmax(0, 1fr); align-items: stretch; gap: var(--workbench-gap); height: clamp(220px, 31vh, 320px); height: clamp(220px, 31dvh, 320px); }
.workbench-section { min-width: 0; min-height: 0; height: 100%; margin: 0; padding: 18px 20px; display: grid; grid-template-rows: auto minmax(0, 1fr); overflow: hidden; border-radius: 16px; background: var(--do-glass-panel-bg); }
.section-heading { min-height: 32px; display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.section-title { position: relative; width: fit-content; max-width: 100%; padding-right: 14px; }
.section-heading h2 { margin: 0; color: var(--do-ink); font-size: 16px; }
.workbench-section__body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding-right: 4px; scrollbar-width: thin; scrollbar-color: color-mix(in srgb, var(--do-primary) 22%, transparent) transparent; }
.workbench-section__body::-webkit-scrollbar { width: 5px; }
.workbench-section__body::-webkit-scrollbar-track { background: transparent; }
.workbench-section__body::-webkit-scrollbar-thumb { border-radius: var(--do-radius-full); background: color-mix(in srgb, var(--do-primary) 20%, transparent); }
.workbench-empty { margin: 10px 0; color: var(--do-muted); font-size: 13px; line-height: 1.6; }
.workbench-section :deep(.el-alert) { margin-bottom: 12px; }

.workbench-source-list { border-block: 1px solid var(--do-glass-line); }
.workbench-source { display: grid; gap: 9px; padding: 13px 2px; border-bottom: 1px solid var(--do-glass-line); }
.workbench-source:last-child { border-bottom: 0; }
.workbench-source__top { min-width: 0; display: flex; align-items: center; gap: 14px; }
.workbench-source__name { flex: 1 1 auto; min-width: 0; display: flex; align-items: center; gap: 10px; border-radius: var(--do-radius-sm); outline: none; }
.workbench-source__name:focus-visible { outline: 2px solid var(--do-primary); outline-offset: 2px; }
.workbench-source__name > svg { flex: 0 0 auto; color: var(--do-muted); }
.workbench-source__identity { min-width: 0; display: grid; gap: 2px; }
.workbench-source__identity strong, .workbench-source__identity span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workbench-source__identity strong { color: var(--do-ink); font-size: 14px; }
.workbench-source__identity span { color: var(--do-muted); font-size: 11.5px; }
.workbench-source__aside { flex: 0 1 auto; min-width: 0; max-width: 62%; display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.workbench-source__status { flex: 1 1 auto; min-width: 0; max-width: 380px; display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 4px 10px; }
.workbench-source__reason { font-size: 11.5px; color: var(--do-muted); line-height: 1.45; overflow-wrap: anywhere; }
.workbench-source__action { flex: 0 0 auto; justify-self: end; white-space: nowrap; }
.workbench-source__unknown-conditions { min-width: 0; display: flex; flex-wrap: wrap; gap: 6px; }
.workbench-unknown-chip { display: inline-flex; align-items: center; gap: 5px; min-height: 24px; padding: 3px 8px; border: 1px dashed color-mix(in srgb, var(--do-muted) 28%, var(--do-line)); border-radius: var(--do-radius-full); color: var(--do-muted); background: var(--do-surface); font-size: 11.5px; }
.workbench-unknown-chip small { font-size: 10.5px; opacity: .8; }

.workbench-activity { display: grid; grid-template-rows: auto minmax(0, 1fr); align-content: start; }
.workbench-activity :deep(.activity-timeline) { align-self: start; }
.workbench-activity :deep(.activity-timeline li) { min-width: 0; }
.workbench-activity :deep(.activity-timeline strong),
.workbench-activity :deep(.activity-timeline p),
.workbench-activity :deep(.activity-timeline small) { overflow-wrap: anywhere; }
.workbench-activity__empty { height: 100%; min-height: 120px; display: grid; place-content: center; justify-items: center; gap: 8px; color: var(--do-muted); text-align: center; }
.workbench-activity__empty svg { color: color-mix(in srgb, var(--do-primary) 45%, var(--do-muted)); }
.workbench-activity__empty p { margin: 0; font-size: 13px; }

@media (max-width: 1100px) {
  .workbench-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .workbench-columns { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto auto; height: auto; }
  .workbench-section { display: block; height: auto; overflow: visible; }
  .workbench-section__body { overflow: visible; padding-right: 0; }
  .workbench-activity__empty { min-height: 160px; }
}
@media (max-width: 760px) {
  .workbench-stat { padding: 13px 15px 14px; }
  .workbench-summary { grid-template-columns: 40px minmax(0, 1fr); }
  .workbench-summary > .el-button { grid-column: 1 / -1; width: 100%; justify-self: stretch; }
  .workbench-section { padding: 16px 14px; }
  .section-heading { flex-wrap: wrap; }
  .workbench-source__top { flex-wrap: wrap; }
  .workbench-source__aside { flex: 1 1 100%; max-width: none; justify-content: flex-start; flex-wrap: wrap; }
  .workbench-source__status { max-width: none; justify-content: flex-start; }
}
@media (prefers-reduced-motion: reduce) {
  .workbench-summary__spin { animation-duration: 2s; }
}
</style>

<style>
/* 名称与数据库名单行省略后，用浮层补齐悬停与键盘聚焦时的完整内容。 */
.workbench-source-tooltip.el-popper.el-tooltip__popper {
  max-width: min(320px, calc(100vw - 24px));
  border-color: var(--do-line);
  color: var(--do-ink);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
</style>
