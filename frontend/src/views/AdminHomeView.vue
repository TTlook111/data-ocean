<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CheckCircle2, CircleAlert, Database, ShieldAlert } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useIamS1Store } from '../stores/iamS1'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'
import { getBatchDatasourceReadiness, listSimpleDatasources, type DatasourceReadiness, type DatasourceSimpleItem } from '../api/admin/datasource'
import TaskPageHeader from '../components/admin/TaskPageHeader.vue'
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
    statsError.value = '没有“查看工作台”功能：需要 IAM-SIMPLE-1 角色包含该功能。'
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
      <section class="workbench-attention" :class="{ 'workbench-attention--ready': allReady }" aria-live="polite">
        <component :is="allReady ? CheckCircle2 : CircleAlert" :size="32" aria-hidden="true" />
        <div><h2>{{ attentionTitle }}</h2>
          <p v-if="readinessLoading">正在确认状态，请稍候。</p>
          <p v-else-if="allReady">{{ readyCount }} 个数据源已具备问数条件。</p>
          <p v-else-if="readinessError">请重试读取准备情况，再判断是否需要处理。</p>
          <p v-else-if="unknownCount">另有 {{ unknownCount }} 个数据源暂未读取到状态，已保留其他结果。</p>
          <p v-else-if="!sources.length">连接并采集数据后，可以在这里查看准备条件。</p>
          <p v-else>打开数据源详情，查看具体原因和处理入口。</p>
        </div>
        <el-button type="primary" :icon="Database" @click="router.push('/admin/data-sources')">管理数据源</el-button>
      </section>
      <dl class="workbench-summary">
        <div><dt>数据源</dt><dd>{{ readinessError ? '—' : sources.length }}</dd></div>
        <div><dt>可问数</dt><dd>{{ readinessError ? '—' : readyCount }}</dd></div>
        <div><dt>需要处理</dt><dd>{{ readinessError ? '—' : blockedItems.length }}</dd></div>
        <div v-if="progressingCount"><dt>准备中</dt><dd>{{ progressingCount }}</dd></div>
        <div><dt>治理问题</dt><dd>{{ stats ? stats.openIssues : '—' }}</dd></div>
      </dl>
      <section class="workbench-section" aria-labelledby="workbench-sources">
        <div class="section-heading"><div><h2 id="workbench-sources">数据源准备情况</h2><p>需要处理的数据源排在前面。打开详情查看完整条件。</p></div><el-button text :loading="readinessLoading" @click="loadReadiness">刷新</el-button></div>
        <el-alert v-if="readinessPartialError" :title="readinessPartialError" type="warning" :closable="false" show-icon />
        <ErrorState v-if="readinessError" :message="readinessError" @retry="loadReadiness" />
        <LoadingState v-else-if="readinessLoading" text="正在读取准备情况..." />
        <p v-else-if="!sources.length" class="workbench-empty">还没有数据源。通过上方“管理数据源”开始接入。</p>
        <div v-else class="workbench-source-list">
          <article v-for="item in orderedReadiness" :key="item.datasourceId" class="workbench-source">
            <div class="workbench-source__name"><strong>{{ item.datasourceName }}</strong><span>{{ sources.find(source => source.id === item.datasourceId)?.databaseName }}</span></div>
            <div class="workbench-source__status"><BusinessStatusBadge :status="item.askable ? 'PUBLISHED' : item.stage" :label="item.askable ? '可问数' : item.stageLabel" /><span v-if="item.blockReasons.length" class="workbench-source__reason">{{ item.blockReasons[0]?.message }}</span></div>
            <ReadinessPanel :readiness="item" compact />
            <el-button link type="primary" @click="openDatasource(item)">查看详情<ArrowRight :size="15" aria-hidden="true" /></el-button>
          </article>
          <article v-for="source in sources.filter(source => !readiness.some(item => item.datasourceId === source.id))" :key="'unknown-' + source.id" class="workbench-source">
            <div class="workbench-source__name"><strong>{{ source.name }}</strong><span>{{ source.databaseName }}</span></div><span class="workbench-source__reason">准备情况暂不可用</span><el-button link type="primary" @click="router.push('/admin/data-sources/' + source.id)">查看详情</el-button>
          </article>
        </div>
      </section>
      <section class="workbench-section workbench-activity">
        <div class="section-heading"><h2>最近活动</h2><RouterLink class="workbench-link" to="/admin/governance/issues"><ShieldAlert :size="16" aria-hidden="true" />查看治理问题</RouterLink></div>
        <ErrorState v-if="statsError" :message="statsError" @retry="loadStats" /><LoadingState v-else-if="statsLoading" /><ActivityTimeline v-else-if="activityItems.length" :items="activityItems" /><p v-else class="workbench-empty">暂无活动记录</p>
      </section>
    </template>
  </div>
</template>
<style scoped>
.workbench-attention { display: flex; align-items: center; gap: 18px; padding: 24px; border: 1px solid var(--do-line); border-radius: var(--do-radius-lg); background: var(--do-primary-soft); color: var(--do-primary-strong); }
.workbench-attention--ready { color: var(--do-success); }
.workbench-attention > div { flex: 1; min-width: 0; }
.workbench-attention h2 { margin: 0 0 7px; color: var(--do-ink); font-size: 20px; }
.workbench-attention p { margin: 0; color: var(--do-muted); line-height: 1.6; font-size: 14px; }
.workbench-summary { display: flex; padding: 24px 0; margin: 24px 0 32px; border-block: 1px solid var(--do-line); }
.workbench-summary > div { flex: 1; padding: 0 24px; border-right: 1px solid var(--do-line); }
.workbench-summary > div:first-child { padding-left: 12px; }
.workbench-summary > div:last-child { border: 0; }
dt { color: var(--do-muted); font-size: 13px; } dd { margin: 8px 0 0; font-size: 25px; font-weight: 700; color: var(--do-ink); }
.workbench-section { margin-top: 28px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.section-heading h2 { margin: 0; color: var(--do-ink); font-size: 18px; }
.section-heading p, .workbench-empty { margin: 8px 0 0; color: var(--do-muted); font-size: 14px; line-height: 1.6; }
.workbench-source-list { border-block: 1px solid var(--do-line); background: var(--do-surface); padding-inline: 14px; }
.workbench-source { display: grid; grid-template-columns: minmax(160px, 1fr) minmax(110px, .7fr) minmax(280px, 1.2fr) auto; align-items: center; gap: 20px; padding: 24px 0; border-bottom: 1px solid var(--do-line); }
.workbench-source:last-child { border-bottom: 0; }
.workbench-source__name, .workbench-source__status { display: grid; gap: 8px; min-width: 0; justify-items: start; }
.workbench-source__name strong { font-size: 15px; color: var(--do-ink); }
.workbench-source__name span, .workbench-source__reason { font-size: 12px; color: var(--do-muted); overflow-wrap: anywhere; line-height: 1.6; }
.workbench-activity { border-top: 1px solid var(--do-line); margin-top: 36px; padding-top: 26px; }
.workbench-link { display: inline-flex; align-items: center; gap: 6px; color: var(--do-primary-strong); font-size: 13px; }
@media (max-width: 1150px) { .workbench-source { grid-template-columns: minmax(130px, 1fr) auto; gap: 16px; } .workbench-source > .readiness-panel { grid-column: 1; } }
@media (max-width: 760px) { .workbench-attention { flex-wrap: wrap; padding: 18px; } .workbench-attention > button { width: 100%; } .workbench-summary { flex-wrap: wrap; gap: 20px 0; } .workbench-summary > div { flex: 1 0 45%; padding-left: 12px; } .workbench-source { grid-template-columns: minmax(0, 1fr); } }
</style>
