<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, RefreshCw } from 'lucide-vue-next'
import { getQueryExperience, type ExperienceDetailKind, type ExperiencePeriod, type QueryExperience } from '../api/admin/dashboard'
import ErrorState from '../components/common/ErrorState.vue'
import LoadingState from '../components/common/LoadingState.vue'
import { useIamWorkspaceSession } from '../composables/useIamWorkspaceSession'
import { getHttpErrorMessage } from '../utils/httpError'
import { bucketLabel, dateLabel, experiencePeriods, isExperiencePeriod, slowestMeasuredBucket, timeLabel, validExperience, waitLabel } from '../utils/workbenchPresentation'
import WaitingTimeChart from './admin/workbench/WaitingTimeChart.vue'

const route = useRoute()
const router = useRouter()
const session = useIamWorkspaceSession()
const period = computed(() => {
  const requested = route.query.period
  if (requested === undefined) return 'today'
  return isExperiencePeriod(requested) ? requested : null
})
const canView = computed(() => session.canGlobal('admin:workbench:view'))
const canAudit = computed(() => session.iam.systemAdmin || session.iam.datasourcesWithFunction('audit:view').length > 0)
const canFeedback = computed(() => session.iam.systemAdmin || session.iam.datasourcesWithFunction('governance:field:view').length > 0)
const report = ref<QueryExperience | null>(null)
const loading = ref(false)
const error = ref('')
const pickedStart = ref('')
const usage = computed(() => canAudit.value ? report.value?.usage : null)
const feedback = computed(() => canFeedback.value ? report.value?.feedback : null)
let sequence = 0

// 旧工作台的来源书签直接落入该源详情，非法或无权 ID 不替换成其他来源。
function openLegacySource(id: unknown) {
  if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id))) {
    error.value = '指定来源标识无效。'
  } else if (!session.can('datasource:view', Number(id))) {
    error.value = '没有查看指定来源的权限。'
  } else {
    void router.replace({ name: 'admin-data-source-detail', params: { id } })
  }
}

// 每次换账号、权限或周期都清掉旧报告。迟到的成功或 403 都不能覆盖新页面。
async function load() {
  const request = ++sequence
  const identity = session.capture()
  const requested = period.value
  report.value = null
  error.value = ''
  loading.value = false
  // 路由先更新、组件随后才卸载；离开工作台的这一拍不得触发新的汇总读取。
  if (route.path !== '/admin/workbench' || !canView.value) return
  if (route.query.focusSourceId !== undefined) {
    openLegacySource(route.query.focusSourceId)
    return
  }
  if (!requested) {
    error.value = '时间范围无效，请重新选择。'
    return
  }
  loading.value = true
  const current = () => request === sequence && session.current(identity) && canView.value && period.value === requested
  try {
    const next = await getQueryExperience(requested)
    if (!current()) return
    if (!validExperience(next, requested)) {
      throw new Error('问数统计返回的范围或指标不完整。')
    }
    report.value = next
    const buckets = usage.value?.buckets ?? []
    const requestedBucket = buckets.find(bucket => bucket.start === route.query.bucketStart)
    pickedStart.value = (requestedBucket ?? slowestMeasuredBucket(buckets))?.start ?? ''
  } catch (cause) {
    if (current()) error.value = getHttpErrorMessage(cause, '问数统计读取失败')
  } finally {
    if (current()) loading.value = false
  }
}
watch([session.key, period, () => route.path, () => route.query.focusSourceId], load, { immediate: true, flush: 'sync' })

// 当前服务端截止点随每次刷新推进；返回首页保留用户选中的服务端时间桶，不把上一日时段混入新周期。
function changePeriod(next: ExperiencePeriod) {
  void router.replace({ path: '/admin/workbench', query: { period: next } })
}
function pick(start: string) {
  if (!usage.value?.buckets.some(bucket => bucket.start === start) || !period.value) return
  pickedStart.value = start
  void router.replace({ path: '/admin/workbench', query: { period: period.value, bucketStart: start } })
}
const selected = computed(() => usage.value?.buckets.find(item => item.start === pickedStart.value))
// 只有一个可计时桶时直接标注时段；孤立观测不能连成一条趋势。
const measuredBuckets = computed(() => usage.value?.buckets.filter(b => b.measured > 0 && b.avgSeconds !== null) ?? [])
const votes = computed(() => feedback.value ? feedback.value.likes + feedback.value.dislikes : 0)
const negativePercent = computed(() => feedback.value && votes.value ? 100 * feedback.value.dislikes / votes.value : null)
const priorPercent = computed(() => {
  const f = feedback.value
  if (f?.previousDislikes == null || f.previousLikes == null || !(f.previousLikes + f.previousDislikes)) return null
  return Math.round(100 * f.previousDislikes / (f.previousLikes + f.previousDislikes))
})
const meanDelta = computed(() => usage.value?.avgSeconds != null && usage.value.previousAvgSeconds != null
  ? usage.value.avgSeconds - usage.value.previousAvgSeconds : null)
const scopeLabel = computed(() => {
  const r = report.value
  if (!r) return ''
  if (r.period === 'yesterday') return dateLabel(r.start) + ' · 全天'
  if (r.period === 'week') return dateLabel(r.start) + ' — ' + dateLabel(r.end) + ' · 截至 ' + timeLabel(r.asOf)
  return dateLabel(r.start) + ' · 截至 ' + timeLabel(r.asOf)
})

// 慢回答沿用当前时段与目标，反馈使用整个选定周期。两个入口的数字与权限互相独立。
function detailTarget(kind: ExperienceDetailKind) {
  const query: Record<string,string> = { kind, period: report.value!.period, asOf: report.value!.asOf }
  if (selected.value) {
    const bucketKey = kind === 'slow' ? 'bucketStart' : 'returnBucket'
    query[bucketKey] = selected.value.start
  }
  return { name: 'admin-workbench-details', query }
}
</script>

<template>
  <article class="experience-home">
    <header class="experience-heading">
      <div>
        <p v-if="scopeLabel">{{ scopeLabel }} <span aria-hidden="true">／</span> 负责范围内的问数</p>
        <h1>问数运行概况</h1>
      </div>
      <div v-if="canView" class="experience-controls">
        <div class="periods" role="group" aria-label="统计时间">
          <button v-for="(label, key) in experiencePeriods" :key="key" type="button" :aria-pressed="period === key" @click="changePeriod(key)">{{ label }}</button>
        </div>
        <button class="refresh" type="button" aria-label="刷新问数统计" title="刷新问数统计" :disabled="loading" @click="load"><RefreshCw :size="17" /></button>
      </div>
    </header>
    <LoadingState v-if="!session.ready.value && !session.iam.errorMessage" text="正在核对工作台权限…" />
    <ErrorState v-else-if="!canView" :message="session.iam.errorMessage || '没有查看工作台的权限。'" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <LoadingState v-else-if="loading" text="正在读取问数运行情况…" />
    <template v-else-if="report">
      <p v-if="!usage && !feedback" class="availability" role="status">当前负责范围没有可查看的问数审计或反馈统计。</p>
      <div v-else class="experience-panels" :class="{ single: !usage || !feedback }">
        <!-- 计时样本与任务状态分开；没有合法耗时的完成记录仍计入下方的任务数量。 -->
        <section v-if="usage" class="speed" aria-labelledby="wait-heading">
          <header><h2 id="wait-heading">平均等候时间</h2><small>受理至答案就绪</small></header>
          <div v-if="usage.measured" class="big-value">
            <strong :class="{ breach: usage.avgSeconds != null && usage.avgSeconds > report.targetSeconds }">{{ waitLabel(usage.avgSeconds, report.targetSeconds) }}</strong><span v-if="usage.avgSeconds != null">秒</span>
            <small v-if="meanDelta != null">{{ Math.abs(meanDelta) < .05 ? '与昨日同时段持平' : '较昨日同时段' + (meanDelta > 0 ? '慢 ' : '快 ') + Math.abs(meanDelta).toFixed(1) + ' 秒' }}</small>
          </div>
          <p v-else class="empty-value">暂无计时样本</p>
          <div class="chart-key"><span v-if="usage.measured">超目标 {{ usage.slow }} / {{ usage.measured }} 次 <template v-if="usage.p95Seconds != null"> · <abbr title="95% 的可计时回答在此时间内就绪">P95</abbr> {{ usage.p95Seconds }} 秒</template></span><span class="target">目标 {{ report.targetSeconds }} 秒</span></div>
          <WaitingTimeChart v-if="measuredBuckets.length > 1" :buckets="usage.buckets" :period="report.period" :target="report.targetSeconds" :selected="pickedStart" @select="pick" />
          <p v-else-if="measuredBuckets[0]" class="chart-empty">仅 {{ bucketLabel(measuredBuckets[0], report.period) }} 有可计时回答。</p>
          <p v-else class="chart-empty">{{ usage.completed ? '已返回的回答缺少可核对的起止时间。' : '所选周期暂无已完成的回答。' }}</p>
          <footer v-if="selected" class="chart-footer">
            <div><select aria-label="选择排查时段" :value="pickedStart" @change="pick(($event.target as HTMLSelectElement).value)">
              <option v-for="b in usage.buckets" :key="b.start" :value="b.start">{{ bucketLabel(b, report.period) }}</option>
            </select><small>{{ selected.measured }} 个可计时回答，{{ selected.slow }} 个超目标</small></div>
            <RouterLink v-if="selected.slow > 0" :to="detailTarget('slow')">查慢回答 <ArrowUpRight :size="15" aria-hidden="true" /></RouterLink>
          </footer>
        </section>
        <!-- 点踩占比的分母为已评价回答；独立答案抽检目前没有来源，不能用赞踩推算正确率。 -->
        <section v-if="feedback" class="feedback" aria-labelledby="feedback-heading">
          <div><h2 id="feedback-heading">用户反馈</h2>
            <div v-if="votes" class="down-value"><strong :class="{ 'has-dislikes': feedback.dislikes > 0 }">{{ feedback.dislikes }}</strong><span>次点踩</span></div>
            <p v-else class="empty-value">暂无评价</p>
            <p v-if="negativePercent != null" class="secondary">占已评价回答 {{ Math.round(negativePercent) }}%<template v-if="priorPercent != null">，昨日同时段 {{ priorPercent }}%</template></p>
            <div v-if="votes" class="vote-bar" aria-hidden="true"><i v-if="feedback.likes" :style="{ flex: feedback.likes }"></i><i v-if="feedback.dislikes" class="dislike" :style="{ flex: feedback.dislikes }"></i></div>
            <div v-if="votes" class="vote-labels"><span>赞 <b>{{ feedback.likes }}</b></span><span>踩 <b>{{ feedback.dislikes }}</b></span></div>
            <p v-if="votes" class="secondary coverage">{{ feedback.eligible }} 个回答中有 {{ votes }} 个获得评价</p>
            <p v-else class="secondary coverage">{{ feedback.eligible ? feedback.eligible + ' 个已返回回答尚未收到评价。' : '负责范围内暂无已返回的回答。' }}</p>
            <RouterLink v-if="feedback.dislikes" :to="detailTarget('feedback')">看点踩反馈 <ArrowUpRight :size="15" aria-hidden="true" /></RouterLink>
          </div>
          <div class="accuracy"><h3>抽检正确率</h3><strong>暂无独立抽检数据</strong></div>
        </section>
      </div>
      <!-- 各终态和仍在处理的任务均属于发起总数；非零的取消和澄清单列，避免误算失败。 -->
      <section v-if="usage" class="traffic">
        <div><h2>提问情况</h2><p>{{ experiencePeriods[report.period] }}</p></div>
        <dl><div><dt>发起提问</dt><dd>{{ usage.total }}</dd></div><div><dt>已返回答案</dt><dd>{{ usage.completed }}</dd></div>
          <div><dt>失败 / 超时</dt><dd>{{ usage.failed }} / {{ usage.timedOut }}</dd></div><div><dt>仍在处理</dt><dd>{{ usage.processing }}</dd></div>
          <div v-if="usage.cancelled"><dt>已取消</dt><dd>{{ usage.cancelled }}</dd></div><div v-if="usage.clarification"><dt>待澄清</dt><dd>{{ usage.clarification }}</dd></div>
        </dl>
      </section>
    </template>
  </article>
</template>

<style scoped src="./admin/workbench/experience.css"></style>
