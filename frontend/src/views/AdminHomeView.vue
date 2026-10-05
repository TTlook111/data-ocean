<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CheckCircle2, CircleAlert, Clock3, Database, ShieldAlert } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useIamS1Store } from '../stores/iamS1'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'
import { getBatchDatasourceReadiness, listSimpleDatasources, type DatasourceReadiness, type DatasourceSimpleItem } from '../api/admin/datasource'
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

/** 工作台入口功能：与后端 DashboardController 的 admin:workbench:view 一致。 */
const canViewWorkbench = computed(() => iamS1.hasGlobal('admin:workbench:view'))
const readyCount = computed(() => readiness.value.filter((item) => item.askable).length)
const blockedItems = computed(() => readiness.value.filter((item) => !item.askable && item.blockReasons.length))
const progressingCount = computed(() => readiness.value.filter((item) => !item.askable && !item.blockReasons.length).length)
const unknownCount = computed(() => Math.max(0, sources.value.length - readiness.value.length))
const orderedReadiness = computed(() => [...readiness.value].sort((a, b) => Number(a.askable) - Number(b.askable)))
const readinessCountsKnown = computed(() => !readinessLoading.value && !readinessError.value && !readinessPartialError.value && !unknownCount.value)
const attentionTitle = computed(() => {
  if (readinessLoading.value) return '正在读取准备情况'
  if (readinessError.value) return '准备情况暂不可用'
  if (readinessPartialError.value || unknownCount.value) return '部分数据源的准备情况尚未确认'
  if (!sources.value.length) return '先接入第一个数据源'
  if (blockedItems.value.length) return `${blockedItems.value.length} 个数据源需要处理`
  if (progressingCount.value) return `${progressingCount.value} 个数据源仍在准备中`
  return '当前没有阻断项'
})
const allReady = computed(() => Boolean(sources.value.length) && !readinessLoading.value && !readinessError.value && !readinessPartialError.value && !unknownCount.value && readyCount.value === sources.value.length)
const activityItems = computed(() => (stats.value?.recentActivities || []).map((item) => ({
  time: item.time,
  action: item.type,
  description: item.description,
})))

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

async function load() {
  loading.value = true
  await Promise.all([loadStats(), loadReadiness()])
  loading.value = false
}

function openDatasource(item: DatasourceReadiness) {
  router.push({ path: '/admin/data-sources/' + item.datasourceId })
}

onMounted(load)
</script>

<template>
  <div class="admin-page workbench-page">
    <TaskPageHeader title="工作台" description="先处理影响问数的问题，再查看数据源的准备情况。" />
    <LoadingState v-if="loading" variant="skeleton" :rows="6" />
    <template v-else>
      <div class="workbench-overview">
        <section class="workbench-attention do-glass-shell" :class="{ 'workbench-attention--ready': allReady, 'workbench-attention--quiet': !readinessCountsKnown || (!blockedItems.length && !allReady) }" aria-live="polite">
          <div class="workbench-attention__count" aria-hidden="true">
            <strong v-if="readinessCountsKnown && blockedItems.length">{{ String(blockedItems.length).padStart(2, '0') }}</strong>
            <CheckCircle2 v-else-if="allReady" :size="64" />
            <Clock3 v-else-if="readinessCountsKnown && progressingCount" :size="42" />
            <CircleAlert v-else :size="42" />
          </div>
          <div class="workbench-attention__copy"><span class="workbench-attention__eyebrow">问数准备</span><h2>{{ attentionTitle }}</h2>
          <p v-if="readinessLoading">正在确认状态，请稍候。</p>
          <p v-else-if="allReady">{{ readyCount }} 个数据源已具备问数条件。</p>
          <p v-else-if="readinessError">请重试读取准备情况，再判断是否需要处理。</p>
          <p v-else-if="unknownCount">另有 {{ unknownCount }} 个数据源暂未读取到状态，已保留其他结果。</p>
          <p v-else-if="!sources.length">连接并采集数据后，可以在这里查看准备条件。</p>
          <p v-else-if="blockedItems.length">{{ blockedItems[0]?.datasourceName }}：{{ blockedItems[0]?.blockReasons[0]?.message || '打开数据源详情查看待处理条件。' }}</p>
          <p v-else-if="progressingCount">{{ progressingCount }} 个数据源正在完成上线准备，请稍后刷新状态。</p>
          <p v-else>打开数据源详情，查看具体原因和处理入口。</p>
          </div>
          <el-button type="primary" :icon="Database" @click="router.push('/admin/data-sources')">管理数据源</el-button>
        </section>
        <dl class="workbench-summary" aria-label="工作台概览">
          <div><dt>数据源</dt><dd>{{ readinessError ? '—' : sources.length }}</dd></div>
          <div><dt>可问数</dt><dd>{{ readinessCountsKnown ? readyCount : '—' }}</dd></div>
          <div><dt>需要处理</dt><dd>{{ readinessCountsKnown ? blockedItems.length : '—' }}</dd></div>
          <div v-if="readinessCountsKnown && progressingCount"><dt>准备中</dt><dd>{{ progressingCount }}</dd></div>
          <div><dt>治理问题</dt><dd>{{ stats ? stats.openIssues : '—' }}</dd></div>
        </dl>
      </div>
      <div class="workbench-columns">
      <section class="workbench-section workbench-sources do-glass-panel" aria-labelledby="workbench-sources">
        <div class="section-heading"><div class="section-title"><TitleHelp label="数据源准备情况" text="需要处理的数据源排在前面。打开详情查看完整条件。" /><h2 id="workbench-sources">数据源准备情况</h2></div><el-button text :loading="readinessLoading" @click="loadReadiness">刷新</el-button></div>
        <el-alert v-if="readinessPartialError" :title="readinessPartialError" type="warning" :closable="false" show-icon />
        <ErrorState v-if="readinessError" :message="readinessError" @retry="loadReadiness" />
        <LoadingState v-else-if="readinessLoading" text="正在读取准备情况..." />
        <p v-else-if="!sources.length" class="workbench-empty">还没有数据源。通过上方“管理数据源”开始接入。</p>
        <div v-else class="workbench-source-list">
          <article v-for="item in orderedReadiness" :key="item.datasourceId" class="workbench-source">
            <div class="workbench-source__name"><Database :size="25" aria-hidden="true" /><div><strong>{{ item.datasourceName }}</strong><span>{{ sources.find(source => source.id === item.datasourceId)?.databaseName }}</span></div></div>
            <div class="workbench-source__status"><BusinessStatusBadge :status="item.askable ? 'PUBLISHED' : item.stage" :label="item.askable ? '可问数' : item.stageLabel" /><span v-if="item.blockReasons.length" class="workbench-source__reason">{{ item.blockReasons[0]?.message }}</span></div>
            <ReadinessPanel :readiness="item" compact />
            <el-button link type="primary" @click="openDatasource(item)">查看详情<ArrowRight :size="15" aria-hidden="true" /></el-button>
          </article>
          <article v-for="source in sources.filter(source => !readiness.some(item => item.datasourceId === source.id))" :key="'unknown-' + source.id" class="workbench-source">
            <div class="workbench-source__name"><Database :size="25" aria-hidden="true" /><div><strong>{{ source.name }}</strong><span>{{ source.databaseName }}</span></div></div>
            <div class="workbench-source__status"><span class="workbench-source__reason" role="status">准备情况暂不可用</span></div>
            <span class="workbench-source__unknown-conditions">五项准备条件尚未确认</span>
            <el-button link type="primary" @click="router.push('/admin/data-sources/' + source.id)">查看详情</el-button>
          </article>
        </div>
      </section>
      <section class="workbench-section workbench-activity do-glass-panel">
        <div class="section-heading"><h2>最近活动</h2><RouterLink class="workbench-link" to="/admin/governance/issues"><ShieldAlert :size="16" aria-hidden="true" />查看治理问题</RouterLink></div>
        <ErrorState v-if="statsError" :message="statsError" @retry="loadStats" /><LoadingState v-else-if="statsLoading" /><ActivityTimeline v-else-if="activityItems.length" :items="activityItems" /><p v-else class="workbench-empty">暂无活动记录</p>
      </section>
      </div>
    </template>
  </div>
</template>
<style scoped>
.workbench-page :deep(.task-page-header h1) { font-size: clamp(36px, 4.3vw, 64px); letter-spacing: -.04em; }
.workbench-overview { display: grid; grid-template-columns: minmax(0, 2.05fr) minmax(270px, .95fr); align-items: stretch; gap: 22px; }
.workbench-attention { --do-glass-corner: 18px; min-width: 0; min-height: 246px; display: grid; grid-template-columns: 136px minmax(0, 1fr); align-items: center; gap: 16px 24px; padding: 28px; border-radius: 18px; color: #fff; background: var(--do-glass-ocean-bg); background-size: cover; background-position: center bottom; box-shadow: var(--do-glass-shadow-raised), inset 0 1px 0 rgba(255,255,255,.65); }
.workbench-attention__count { grid-column: 1; grid-row: 1 / 3; }
.workbench-attention__copy { grid-column: 2; grid-row: 1; }
.workbench-attention::before { backdrop-filter: none; -webkit-backdrop-filter: none; }
.workbench-attention--quiet { min-height: 170px; grid-template-columns: 54px minmax(0, 1fr); color: var(--do-primary-strong); background: var(--do-glass-panel-bg); box-shadow: var(--do-shadow); }
.workbench-attention--ready { min-height: 228px; grid-template-columns: 96px minmax(0, 1fr); color: white; }
.workbench-attention__count { display: flex; align-items: center; justify-content: center; min-width: 0; padding-right: 20px; border-right: 1px solid rgba(255,255,255,.42); color: rgba(255,255,255,.82); }
.workbench-attention__count strong { font-size: clamp(72px, 7vw, 112px); font-weight: 720; line-height: .95; letter-spacing: -.07em; }
.workbench-attention--quiet .workbench-attention__count { padding-right: 0; border-right: 0; color: var(--do-primary-strong); }
.workbench-attention--ready .workbench-attention__count { color: white; }
.workbench-attention__copy { min-width: 0; }
.workbench-attention__eyebrow { display: block; margin-bottom: 8px; color: rgba(255,255,255,.78); font-size: 12px; font-weight: 700; letter-spacing: .06em; }
.workbench-attention--quiet .workbench-attention__eyebrow { color: var(--do-muted); }
.workbench-attention--ready .workbench-attention__eyebrow { color: rgba(255,255,255,.88); }
.workbench-attention h2 { margin: 0 0 9px; color: #fff; font-size: clamp(22px, 2vw, 30px); line-height: 1.35; }
.workbench-attention--quiet h2 { color: var(--do-ink); }
.workbench-attention p { margin: 0; color: rgba(255,255,255,.94); line-height: 1.65; font-size: 14px; overflow-wrap: anywhere; }
.workbench-attention--quiet p { color: var(--do-muted); }
.workbench-attention > .el-button { --el-button-bg-color: rgba(5,91,246,.8); --el-button-border-color: rgba(255,255,255,.52); --el-button-text-color: white; --el-button-hover-bg-color: var(--do-primary-strong); --el-button-hover-border-color: rgba(255,255,255,.85); grid-column: 2; grid-row: 2; justify-self: start; align-self: start; min-height: 46px; padding-inline: 22px; box-shadow: 0 5px 18px rgba(8,64,167,.16); }
.workbench-attention--quiet > .el-button { --el-button-bg-color: var(--do-primary-strong); --el-button-border-color: var(--do-primary-strong); --el-button-hover-bg-color: #1b56c8; --el-button-hover-border-color: #1b56c8; }
.workbench-summary { min-width: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-content: stretch; margin: 0; padding: 12px 0; }
.workbench-summary > div { min-width: 0; display: flex; flex-direction: column; justify-content: center; padding: 12px 18px; border-right: 1px solid var(--do-glass-line); border-bottom: 1px solid var(--do-glass-line); }
.workbench-summary > div:nth-child(even) { border-right: 0; }
.workbench-summary > div:nth-last-child(-n+2) { border-bottom: 0; }
dt { color: var(--do-muted); font-size: 13px; } dd { margin: 7px 0 0; font-size: clamp(27px, 3vw, 42px); font-weight: 720; color: var(--do-ink); line-height: 1; letter-spacing: -.04em; }
.workbench-columns { display: grid; grid-template-columns: minmax(0, 2.1fr) minmax(280px, .9fr); align-items: stretch; gap: 14px; margin-top: 18px; }
.workbench-section { min-width: 0; margin: 0; padding: 21px 22px; border-radius: 16px; }
.workbench-sources { background: var(--do-glass-panel-bg); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.section-title { position: relative; width: fit-content; max-width: 100%; padding-right: 14px; }
.section-heading h2 { margin: 0; color: var(--do-ink); font-size: 20px; }
.section-heading p, .workbench-empty { margin: 8px 0 0; color: var(--do-muted); font-size: 14px; line-height: 1.6; }
.workbench-source-list { border-block: 1px solid var(--do-glass-line); padding-inline: 4px; }
.workbench-source { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 14px 18px; padding: 19px 0; border-bottom: 1px solid var(--do-glass-line); }
.workbench-source__name { grid-column: 1; grid-row: 1; }
.workbench-source__status { grid-column: 2; grid-row: 1; max-width: 240px; }
.workbench-source > .readiness-panel, .workbench-source__unknown-conditions { grid-column: 1; grid-row: 2; }
.workbench-source > .el-button { grid-column: 2; grid-row: 2; justify-self: end; }
.workbench-source:last-child { border-bottom: 0; }
.workbench-source__name, .workbench-source__status { display: grid; gap: 8px; min-width: 0; justify-items: start; }
.workbench-source__name { grid-template-columns: 28px minmax(0, 1fr); align-items: center; gap: 12px; }
.workbench-source__name > svg { color: var(--do-muted); }
.workbench-source__name > div { min-width: 0; display: grid; gap: 7px; }
.workbench-source__name strong { font-size: 15px; color: var(--do-ink); overflow-wrap: anywhere; }
.workbench-source__name span, .workbench-source__reason { font-size: 12px; color: var(--do-muted); overflow-wrap: anywhere; line-height: 1.6; }
.workbench-source__unknown-conditions { min-width: 0; color: var(--do-muted); font-size: 12px; line-height: 1.6; }
.workbench-activity { display: grid; grid-template-rows: auto minmax(0, 1fr); align-content: start; background: var(--do-glass-panel-bg); }
.workbench-activity :deep(.activity-timeline) { align-self: start; }
.workbench-link { display: inline-flex; align-items: center; gap: 6px; color: var(--do-primary-strong); font-size: 13px; }
@media (max-width: 1200px) { .workbench-overview, .workbench-columns { grid-template-columns: minmax(0, 1fr); } .workbench-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) { .workbench-attention, .workbench-attention--quiet { grid-template-columns: 48px minmax(0, 1fr); gap: 14px; min-height: 0; padding: 18px; } .workbench-attention__count { grid-row: 1; padding-right: 0; border-right: 0; } .workbench-attention__count svg { width: 44px; height: 44px; } .workbench-attention__count strong { font-size: 52px; } .workbench-attention > .el-button { grid-column: 1 / -1; width: 100%; } .workbench-summary { padding: 8px 0; } .workbench-summary > div { padding: 12px; } .workbench-columns { gap: 12px; } .workbench-section { padding: 16px 14px; } .workbench-source { grid-template-columns: minmax(0, 1fr); } .workbench-source > * { grid-column: 1; grid-row: auto; } .workbench-source > .el-button { justify-self: start; } }
</style>
