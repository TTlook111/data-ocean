<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CheckCircle2, Database, MessageSquareText, ShieldAlert } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { getDashboardStats, type DashboardStats } from '../api/admin/dashboard'
import { getBatchDatasourceReadiness, listSimpleDatasources, type DatasourceReadiness } from '../api/admin/datasource'
import TaskPageHeader from '../components/admin/TaskPageHeader.vue'
import ReadinessPanel from '../components/admin/ReadinessPanel.vue'
import NextActionCard from '../components/admin/NextActionCard.vue'
import BusinessStatusBadge from '../components/admin/BusinessStatusBadge.vue'
import ActivityTimeline from '../components/admin/ActivityTimeline.vue'
import LoadingState from '../components/common/LoadingState.vue'
import ErrorState from '../components/common/ErrorState.vue'
import EmptyState from '../components/common/EmptyState.vue'

const router = useRouter()
const auth = useAuthStore()
const stats = ref<DashboardStats | null>(null)
const readiness = ref<DatasourceReadiness[]>([])
const loading = ref(true)
const statsLoading = ref(true)
const readinessLoading = ref(true)
const statsError = ref('')
const readinessError = ref('')
const readinessPartialError = ref('')
const selectedDatasource = ref<DatasourceReadiness | null>(null)

const isSuperAdmin = computed(() => auth.permissions.includes('*'))
const readyCount = computed(() => readiness.value.filter((item) => item.askable).length)
const blockedItems = computed(() => readiness.value.filter((item) => !item.askable && item.blockReasons.length))
const progressingCount = computed(() => readiness.value.filter((item) => !item.askable && !item.blockReasons.length).length)
const activityItems = computed(() => (stats.value?.recentActivities || []).map((item) => ({
  time: item.time,
  action: item.type,
  description: item.description,
})))

async function loadStats() {
  statsLoading.value = true
  statsError.value = ''
  if (!isSuperAdmin.value) {
    statsError.value = '工作台统计接口当前仅对拥有 * 权限的超级管理员开放。'
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
    const sources = await listSimpleDatasources()
    if (!sources.data.length) {
      readiness.value = []
      selectedDatasource.value = null
      return
    }
    const result = await getBatchDatasourceReadiness(sources.data.map((item) => item.id))
    readiness.value = result.data || []
    selectedDatasource.value = null
    if (result.failedDatasourceIds.length) {
      readinessPartialError.value = `${result.failedDatasourceIds.length} 个数据源的就绪度读取失败，已保留其余数据源结果。`
    }
    selectedDatasource.value = readiness.value.find((item) => !item.askable) || readiness.value[0] || null
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
    <TaskPageHeader
      eyebrow="DATAOCEAN / WORKBENCH"
      title="工作台"
      description="从数据源准备情况开始，定位阻断原因并沿着业务生命周期推进到可问数。"
    >
      <template #actions>
        <el-button type="primary" :icon="MessageSquareText" @click="router.push('/query')">进入智能问数</el-button>
      </template>
    </TaskPageHeader>

    <LoadingState v-if="loading" variant="skeleton" :rows="7" />
    <template v-else>
      <section class="workbench-page__summary">
        <div class="summary-card summary-card--primary">
          <span>可问数数据源</span>
          <strong>{{ readyCount }}</strong>
          <small>共 {{ readiness.length }} 个数据源</small>
        </div>
        <div class="summary-card summary-card--warning">
          <span>需要处理</span>
          <strong>{{ blockedItems.length }}</strong>
          <small>存在后端返回的阻断原因</small>
        </div>
        <div class="summary-card">
          <span>推进中</span>
          <strong>{{ progressingCount }}</strong>
          <small>尚未形成可问数条件</small>
        </div>
        <div v-if="stats" class="summary-card">
          <span>治理问题</span>
          <strong>{{ stats.openIssues }}</strong>
          <small>已解决 {{ stats.resolvedIssues }} 个</small>
        </div>
      </section>

      <section class="workbench-page__section">
        <div class="section-heading">
          <div>
            <h2>数据源准备情况</h2>
            <p>以下状态直接来自后端 readiness，不在前端重新拼装业务结论。</p>
          </div>
          <el-button text @click="loadReadiness">刷新</el-button>
        </div>
        <el-alert v-if="readinessPartialError" :title="readinessPartialError" type="warning" :closable="false" show-icon />
        <ErrorState v-if="readinessError" :message="readinessError" @retry="loadReadiness" />
        <LoadingState v-else-if="readinessLoading" text="正在读取数据源就绪度..." />
        <EmptyState v-else-if="!readiness.length" message="还没有数据源，请先完成数据源接入。" action-text="去创建数据源" @action="router.push('/admin/data-sources')" />
        <div v-else class="readiness-grid">
          <button
            v-for="item in readiness"
            :key="item.datasourceId"
            type="button"
            class="readiness-card"
            :class="{ selected: selectedDatasource?.datasourceId === item.datasourceId }"
            @click="selectedDatasource = item"
          >
            <div class="readiness-card__heading">
              <Database :size="17" />
              <strong>{{ item.datasourceName }}</strong>
              <BusinessStatusBadge :status="item.askable ? 'PUBLISHED' : item.stage" :label="item.askable ? '可以问数' : item.stageLabel" />
            </div>
            <div class="readiness-card__progress">
              <el-progress :percentage="item.progress" :show-text="false" :status="item.askable ? 'success' : undefined" />
              <span>{{ item.progress }}%</span>
            </div>
            <div class="readiness-card__meta">
              <span>{{ item.blockReasons.length ? item.blockReasons.length + ' 项阻断' : item.stageLabel }}</span>
              <ArrowRight :size="15" />
            </div>
          </button>
        </div>
      </section>

      <div class="workbench-page__columns">
        <div class="workbench-page__main-column">
          <ReadinessPanel v-if="selectedDatasource" :readiness="selectedDatasource" />
          <NextActionCard v-if="selectedDatasource" :reasons="selectedDatasource.blockReasons" :datasource-id="selectedDatasource.datasourceId" />
          <section class="workbench-page__section workbench-page__section--compact">
            <div class="section-heading">
              <div><h2>待处理和阻断</h2><p>先处理阻断原因，再回到相应业务工作区复查。</p></div>
            </div>
            <el-table v-if="blockedItems.length" :data="blockedItems" size="small">
              <el-table-column prop="datasourceName" label="数据源" min-width="150" />
              <el-table-column prop="stageLabel" label="当前阶段" width="120" />
              <el-table-column label="阻断原因" min-width="220">
                <template #default="{ row }">{{ row.blockReasons[0]?.message || '需要继续检查' }}</template>
              </el-table-column>
              <el-table-column label="责任角色" width="130">
                <template #default="{ row }">{{ row.blockReasons[0]?.ownerRole || '未指定' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }"><el-button link type="primary" @click="openDatasource(row)">查看</el-button></template>
              </el-table-column>
            </el-table>
            <EmptyState v-else message="当前没有返回阻断原因的数据源。" />
          </section>
        </div>

        <aside class="workbench-page__side-column">
          <section class="workbench-page__section">
            <div class="section-heading"><div><h2>最近活动</h2><p>来自工作台统计接口。</p></div></div>
            <ErrorState v-if="statsError" :message="statsError" :show-retry="false" />
            <LoadingState v-else-if="statsLoading" />
            <ActivityTimeline v-else-if="stats" :items="activityItems" />
            <EmptyState v-else message="暂无统计活动" />
          </section>
          <section class="workbench-page__section">
            <div class="section-heading"><div><h2>快捷入口</h2><p>继续当前业务任务。</p></div></div>
            <RouterLink class="workbench-link" to="/admin/data-sources"><Database :size="16" />数据源接入<ArrowRight :size="15" /></RouterLink>
            <RouterLink class="workbench-link" to="/admin/governance/issues"><ShieldAlert :size="16" />问题中心<ArrowRight :size="15" /></RouterLink>
            <RouterLink class="workbench-link" to="/query"><CheckCircle2 :size="16" />进入智能问数<ArrowRight :size="15" /></RouterLink>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.workbench-page__summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 18px;
}

.summary-card,
.workbench-page__section {
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-lg);
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
}

.summary-card {
  display: grid;
  gap: 5px;
}

.summary-card span,
.summary-card small,
.section-heading p {
  color: var(--do-muted);
  font-size: 12px;
}

.summary-card strong {
  color: var(--do-ink);
  font-size: 28px;
  line-height: 1;
}

.summary-card--primary {
  border-color: rgba(22, 163, 74, .25);
  background: var(--do-success-soft);
}

.summary-card--warning {
  border-color: rgba(217, 119, 6, .25);
  background: var(--do-warning-soft);
}

.workbench-page__section {
  margin-bottom: 18px;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
}

.section-heading h2 {
  margin: 0;
  color: var(--do-ink);
  font-size: 16px;
}

.section-heading p {
  margin: 5px 0 0;
}

.readiness-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.readiness-card {
  display: grid;
  gap: 14px;
  padding: 15px;
  border: 1px solid var(--do-line);
  border-radius: var(--do-radius-md);
  color: var(--do-ink);
  background: var(--do-surface);
  cursor: pointer;
  text-align: left;
}

.readiness-card:hover,
.readiness-card.selected {
  border-color: var(--do-primary);
  box-shadow: 0 0 0 3px rgba(77, 143, 220, .1);
}

.readiness-card__heading,
.readiness-card__progress,
.readiness-card__meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.readiness-card__heading strong {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.readiness-card__heading > svg {
  color: var(--do-primary-strong);
}

.readiness-card__progress :deep(.el-progress) {
  flex: 1;
}

.readiness-card__progress span {
  color: var(--do-muted);
  font-size: 12px;
}

.readiness-card__meta {
  justify-content: space-between;
  color: var(--do-muted);
  font-size: 12px;
}

.workbench-page__columns {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(280px, .7fr);
  gap: 18px;
}

.workbench-page__main-column,
.workbench-page__side-column {
  min-width: 0;
}

.workbench-page__main-column > * {
  margin-bottom: 18px;
}

.workbench-page__section--compact {
  margin-bottom: 0;
}

.workbench-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 0;
  border-bottom: 1px solid var(--do-line);
  color: var(--do-primary-strong);
  font-size: 13px;
  font-weight: 700;
}

.workbench-link:last-child {
  border-bottom: 0;
}

.workbench-link svg:last-child {
  margin-left: auto;
}

@media (max-width: 1050px) {
  .workbench-page__summary {
    grid-template-columns: repeat(2, 1fr);
  }

  .readiness-grid,
  .workbench-page__columns {
    grid-template-columns: 1fr;
  }
}

</style>
