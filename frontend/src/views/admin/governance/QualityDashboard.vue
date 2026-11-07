<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import ErrorState from '../../../components/common/ErrorState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import TaskPageHeader from '../../../components/admin/TaskPageHeader.vue'
import { getQualityIssueTrend, type IssueTrendPeriod, type QualityIssueTrend } from '../../../api/admin/governance'
import { useQualityBusinessScope } from '../../../composables/useQualityBusinessScope'
import { getHttpErrorMessage } from '../../../utils/httpError'
import IssueTrendChart from './IssueTrendChart.vue'
import { issueDayFlows, validIssueTrend } from './issueTrend'

const scope = useQualityBusinessScope()
const periods = [7, 30] as const
const period = ref<IssueTrendPeriod>(7)
const trend = ref<QualityIssueTrend | null>(null)
const selectedDate = ref('')
const loading = ref(false)
const error = ref('')
let request = 0
const canView = computed(() => scope.iam.canOnDatasource('governance:view', scope.datasourceId.value))
const canReadIssues = computed(() => scope.iam.canOnDatasource('governance:issue:view', scope.datasourceId.value))
// 浏览器记住的源和快照编号只是偏好。须经当前账号目录或主任务范围核验后才可读取趋势。
const verified = computed(() => scope.ready.value && (scope.primaryVerified.value || (
  scope.context.initialized && scope.context.currentSnapshot?.id === scope.snapshotId.value
)))
const selectedDay = computed(() => trend.value?.days.find(day => day.date === selectedDate.value))
const latest = computed(() => trend.value?.days.at(-1))
const today = computed(() => trend.value?.asOf.slice(0, 10))
const start = computed(() => trend.value?.days[0])
const partialWindow = computed(() => trend.value?.days.some(day => day.coverage !== 'FULL'))
const scopeQuery = computed(() => ({ datasourceId: String(scope.datasourceId.value), snapshotId: String(scope.snapshotId.value) }))

// 整个区间均有记录才计算相对期初的净变化。启用日前的空档不能默认为零余额。
const change = computed(() => {
  const day = start.value
  if (!day || partialWindow.value || latest.value?.remaining == null || day.remaining == null) return null
  const first = issueDayFlows(day)
  return first ? latest.value.remaining - (day.remaining - first.entered + first.left) : null
})
const dailyDescription = computed(() => {
  const day = selectedDay.value
  if (!day || day.coverage === 'NONE') return '此日期尚无历史记录。'
  const changes = [
    [day.added, '新增'], [day.reopened, '重开'], [day.resolved, '人工解决'], [day.otherClosed, '其他移出'],
  ] as const
  const actions = changes.filter(([count]) => count != null && count > 0).map(([count, label]) => `${label} ${count} 条`)
  const cutoff = day.date === today.value ? '截至目前' : '日末'
  return `${actions.length ? actions.join('，') : '没有状态进出'}；${cutoff}剩余 ${day.remaining} 条。`
})

// 日期切换、身份变化及权限撤回立刻清空旧趋势，晚到的成功与失败都无权更新新范围。
async function readTrend() {
  const id = ++request
  const ticket = scope.capture()
  const source = scope.datasourceId.value, snapshot = scope.snapshotId.value, days = period.value
  trend.value = null
  error.value = ''
  loading.value = false
  if (!source || !snapshot || !verified.value || !canView.value || !canReadIssues.value) return
  const current = () => id === request && scope.isCurrent(ticket) && verified.value && canView.value && canReadIssues.value && period.value === days
  loading.value = true
  try {
    // 公共范围同步会在同一轮更新多个字段。先清屏，再只对最终确认的范围发送一次 GET。
    await nextTick()
    if (!current()) return
    const response = await getQualityIssueTrend(snapshot, days)
    if (!current()) return
    if (!validIssueTrend(response.data, source, snapshot, days)) throw new Error('返回的日期或数据范围与当前选择不一致。')
    trend.value = response.data
    if (!response.data.days.some(day => day.date === selectedDate.value)) selectedDate.value = response.data.days.at(-1)!.date
  } catch (cause) {
    if (current()) error.value = getHttpErrorMessage(cause, '问题趋势读取失败。')
  } finally {
    if (current()) loading.value = false
  }
}

// 范围栏管理公共目录；缺辅助目录权的显式范围由质量主接口核实，首页只发趋势 GET。
watch([scope.generation, verified, canReadIssues, canView], () => {
  selectedDate.value = ''
  void readTrend()
}, { immediate: true, flush: 'sync' })
watch(period, readTrend, { flush: 'sync' })
onBeforeUnmount(() => { request++ })
</script>

<template>
  <main class="post-login-page issue-dashboard">
    <TaskPageHeader title="治理总览">
      <template #actions>
        <div class="trend-period" role="group" aria-label="问题趋势时间范围">
          <button v-for="n in periods" :key="n" type="button" :aria-pressed="period === n" @click="period = n">近 {{ n }} 天</button>
        </div>
      </template>
    </TaskPageHeader>
    <!-- 稀疏授权下同样展示主接口验证过的身份，不把显式范围当作目录权限。 -->
    <p v-if="scope.primaryVerified.value" class="scope-identity">数据源 #{{ scope.datasourceId.value }} · 快照 v{{ scope.currentSnapshot.value?.snapshotVersion }} / #{{ scope.snapshotId.value }}</p>
    <ErrorState v-if="scope.iam.errorMessage" :message="scope.iam.errorMessage" @retry="scope.iam.load(true)" />
    <LoadingState v-else-if="scope.scopeLoading.value" text="正在核对快照范围…" />
    <p v-else-if="!verified" class="dashboard-notice" role="status">{{ scope.scopeIssue.value || '请在范围栏选择已核验的来源和快照。' }}</p>
    <p v-else-if="!canView || !canReadIssues" class="dashboard-notice" role="status">当前账号没有此来源的问题趋势查看权限。</p>
    <ErrorState v-else-if="error" :message="error" @retry="readTrend" />
    <LoadingState v-else-if="loading" text="正在读取问题趋势…" />
    <section v-else-if="trend && latest" class="trend-surface" aria-label="未解决问题趋势">
      <header class="balance">
        <div><p>当前未解决问题</p><strong>{{ latest.remaining ?? '—' }}</strong><span>条</span></div>
        <div class="balance-context">
          <b v-if="change != null">{{ change === 0 ? '与期初持平' : `较期初${change > 0 ? '增加' : '减少'} ${Math.abs(change)} 条` }}</b>
          <span>截至 {{ trend.asOf.replace('T', ' ').slice(0, 16) }}</span>
        </div>
      </header>
      <div class="trend-legend" aria-label="图例">
        <span><i class="balance-mark"></i>日末未解决</span><span><i class="in-mark"></i>进入待处理</span><span><i class="out-mark"></i>移出待处理</span>
      </div>
      <IssueTrendChart :days="trend.days" :selected-date="selectedDate" @select="selectedDate = $event" />
      <!-- 日期点只解释当日记录；问题中心的链接明确读取当前状态，不携带假历史筛选。 -->
      <div class="trend-detail">
        <label>查看日期 <select v-model="selectedDate" aria-label="问题变化日期">
          <option v-for="day in trend.days" :key="day.date" :value="day.date">{{ day.date }}{{ day.date === today ? '（今天）' : '' }}</option>
        </select></label><p aria-live="polite">{{ dailyDescription }}</p>
      </div>
      <p v-if="partialWindow" class="coverage">历史记录自 {{ trend.coverageStart.replace('T', ' ').slice(0, 16) }} 起，起始日仅统计已记录时段。</p>
    </section>
    <nav v-if="verified && canView" class="trend-nav" aria-label="治理明细">
      <RouterLink :to="{ path: '/admin/governance/checks', query: scopeQuery }">质量检查 ↗</RouterLink>
      <RouterLink v-if="canReadIssues" :to="{ path: '/admin/governance/issues', query: scopeQuery }">查看当前问题 ↗</RouterLink>
    </nav>
  </main>
</template>

<style scoped>
/* 局部白底与图例、日期筛选共用两张看板的令牌，外层导航仍继承全站主题。 */
.issue-dashboard { min-width: 0; padding-bottom: 18px; color: var(--do-dash-ink); }
.issue-dashboard p { margin: 0; }
.trend-period { display: flex; gap: 2px; padding: 3px; background: var(--do-dash-surface); border: 1px solid var(--do-dash-border); border-radius: 7px; }
.trend-period button { padding: 6px 12px; background: transparent; border: 0; border-radius: 4px; color: var(--do-dash-muted); cursor: pointer; font: inherit; font-size: 13px; }
.trend-period button[aria-pressed="true"] { background: var(--do-dash-selected); color: var(--do-dash-cobalt); font-weight: 600; }
.trend-period button:hover,.trend-nav a:hover { color: var(--do-dash-cobalt); }
.issue-dashboard :is(button,select,a):focus-visible { outline: 2px solid var(--do-dash-cobalt); outline-offset: 2px; }
.scope-identity,.dashboard-notice,.coverage { color: var(--do-dash-muted); font-size: 13px; }
.dashboard-notice,.scope-identity { padding: 15px 0; }
.trend-surface { background: var(--do-dash-surface); border: 1px solid var(--do-dash-border); border-radius: var(--do-radius-lg); padding: clamp(18px, 2.5vw, 34px); }
.balance { display:flex; gap: 26px; align-items: flex-end; flex-wrap: wrap; }
.balance p { font-size: 14px; font-weight: 550; }
.balance strong { font-size: clamp(44px, 5.3vw, 74px); font-weight: 550; line-height: 1.15; letter-spacing: -.055em; font-variant-numeric: tabular-nums; }
.balance strong + span { margin-left: 8px; font-size: 15px; }
.balance-context { display: grid; gap: 3px; padding-bottom: 5px; color: var(--do-dash-muted); font-size: 12px; }
.balance-context b { color: var(--do-dash-ink); font-size: 15px; }
.trend-legend,.trend-detail,.trend-nav { display:flex; align-items:center; flex-wrap:wrap; }
.trend-legend { gap: 16px; margin-top: 18px; color: var(--do-dash-muted); font-size: 12px; }
.trend-legend i { display:inline-block; width: 8px; height: 8px; margin-right: 5px; border-radius: 2px; }
.trend-legend .balance-mark { width: 17px; height: 3px; vertical-align: middle; background: var(--do-dash-cobalt); }
.in-mark { background: var(--do-dash-in); }.out-mark { background: var(--do-dash-out); }
.trend-detail { border-top: 1px solid var(--do-dash-grid); gap: 12px; padding-top: 14px; font-size: 14px; }
.trend-detail label { color: var(--do-dash-muted); font-size: 13px; }
.trend-detail select { margin-left: 5px; padding: 5px; border: 1px solid var(--do-dash-border); border-radius: 4px; color: var(--do-dash-ink); background: var(--do-dash-surface); font: inherit; }
.trend-surface .coverage { margin-top: 8px; font-size: 12px; }
.trend-nav { justify-content: flex-end; gap: 18px; padding: 12px 0; }
.trend-nav a { color: var(--do-dash-cobalt); font-size: 13px; }
</style>
