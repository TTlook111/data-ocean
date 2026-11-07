<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'
import { getExperienceDetails, type ExperienceDetail } from '../../../api/admin/dashboard'
import type { PageResult } from '../../../api/types'
import ErrorState from '../../../components/common/ErrorState.vue'
import LoadingState from '../../../components/common/LoadingState.vue'
import { useIamWorkspaceSession } from '../../../composables/useIamWorkspaceSession'
import { getHttpErrorMessage } from '../../../utils/httpError'
import { experiencePeriods, readDetailScope } from '../../../utils/workbenchPresentation'

const route = useRoute(), session = useIamWorkspaceSession()
const scope = computed(() => readDetailScope(route.query))
const allowed = computed(() => {
  if (!scope.value || !session.canGlobal('admin:workbench:view')) return false
  const code = scope.value.kind === 'slow' ? 'audit:view' : 'governance:field:view'
  return session.iam.systemAdmin || session.iam.datasourcesWithFunction(code).length > 0
})
const page = ref(1), data = ref<PageResult<ExperienceDetail> | null>(null), error = ref(''), loading = ref(false)
let sequence = 0
const lastPage = computed(() => data.value ? Math.ceil(data.value.total / data.value.size) : 0)
// 从反馈返回时也保留原图里选中的桶。这个导航提示不进入反馈请求，首页还会按实收桶验证。
const back = computed(() => {
  const bucket = scope.value?.bucketStart ?? (typeof route.query.returnBucket === 'string' ? route.query.returnBucket : undefined)
  return { path: '/admin/workbench', query: scope.value ? { period: scope.value.period, ...(bucket ? { bucketStart: bucket } : {}) } : {} }
})

// 日期、时段、权限或身份变动时清除上一页的评论和任务号，始终按相同截止点重新分页。
async function load() {
  const request = ++sequence, ticket = session.capture(), frozen = scope.value, wanted = page.value
  const current = () => request === sequence && session.current(ticket) && allowed.value
  error.value = ''
  data.value = null
  loading.value = false
  if (!allowed.value || !frozen) return
  loading.value = true
  try {
    const result = await getExperienceDetails(frozen, wanted)
    if (!current()) return
    if (result?.current !== wanted || !(result.size > 0) || !Number.isSafeInteger(result.total) || result.total < 0 || !Array.isArray(result.records)) {
      throw new Error('记录分页返回不完整，请重新读取。')
    }
    data.value = result
  } catch (cause) {
    if (current()) error.value = getHttpErrorMessage(cause, '问数运行明细读取失败')
  } finally {
    if (current()) loading.value = false
  }
}
watch([session.key, scope], () => { page.value = 1; void load() }, { immediate: true, flush: 'sync' })
function changePage(value: number) { page.value = value; void load() }

// 明细只展示服务端授权返回的任务标识、时间、耗时或用户留言；不沿任务 ID 请求他人的问题、SQL 与结果。
const title = computed(() => scope.value?.kind === 'feedback' ? '用户点踩反馈' : scope.value?.kind === 'slow' ? '超过目标的回答' : '问数运行明细')
</script>

<template>
  <article class="experience-details">
    <RouterLink class="back" :to="back" replace><ArrowLeft :size="16" aria-hidden="true" /> 返回工作台</RouterLink>
    <header>
      <p v-if="scope">{{ experiencePeriods[scope.period] }} · 统计截至 {{ scope.asOf.replace('T',' ') }}<template v-if="scope.bucketStart"> ／ 时段起点 {{ scope.bucketStart.replace('T',' ') }}</template></p>
      <h1>{{ title }}</h1>
    </header>
    <ErrorState v-if="!scope" message="明细范围无效，请从工作台重新选择。" />
    <LoadingState v-else-if="!session.ready.value && !session.iam.errorMessage" text="正在核对明细权限…" />
    <ErrorState v-else-if="!allowed" :message="session.iam.errorMessage || '没有查看此范围问数明细的权限。'" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <LoadingState v-else-if="loading" text="正在读取问数运行明细…" />
    <template v-else-if="data">
      <p class="count" role="status">共 {{ data.total }} 条{{ scope.kind === 'slow' ? '超目标回答' : '点踩反馈' }}</p>
      <ol v-if="data.records.length" class="records">
        <li v-for="row in data.records" :key="row.taskId">
          <div>
            <strong>{{ scope.kind === 'slow' ? '问数记录 ' + row.taskId : row.comment?.trim() || '用户未填写说明' }}</strong>
            <small>{{ row.createdAt.replace('T',' ') }} 提问 · 来源 #{{ row.datasourceId }}<template v-if="scope.kind === 'feedback'"> · {{ row.taskId }}</template></small>
          </div>
          <b>{{ scope.kind === 'slow' ? row.seconds == null ? '耗时未知' : row.seconds + ' 秒' : '点踩' }}</b>
        </li>
      </ol>
      <p v-else class="empty">当前范围没有符合条件的记录。</p>
      <nav v-if="lastPage > 1 || page > 1" aria-label="记录分页">
        <button :disabled="page <= 1" @click="changePage(page - 1)">上一页</button><span>第 {{ page }} 页</span><button :disabled="page >= lastPage" @click="changePage(page + 1)">下一页</button>
      </nav>
    </template>
  </article>
</template>

<style scoped>
/* 详情使用可读的来源、时刻和任务标识，长评论或标识换行而不挤出手机屏幕。 */
.experience-details { max-width: 1100px; margin-inline: auto; color: var(--do-dash-ink); }
.back { display: inline-flex; align-items: center; gap: 6px; color: var(--do-dash-cobalt); text-decoration: none; font-weight: 600; }
header { margin: 20px 0 16px; } header p,.count,small,.empty,nav { font-size: 13px; color: var(--do-dash-muted); }
header h1 { margin: 4px 0; font-size: clamp(26px,3vw,38px); letter-spacing: -.04em; }
.records { padding: 0; list-style: none; } .records li { padding: 14px 0; border-top: 1px solid var(--do-dash-border); display: flex; justify-content: space-between; align-items: baseline; gap: 16px; }
.records div { min-width: 0; overflow-wrap: anywhere; } .records strong { font-weight: 550; white-space: pre-line; } .records small { display: block; margin-top: 4px; }
.records b { color: var(--do-dash-ink); white-space: nowrap; } nav { display: flex; gap: 12px; align-items: center; justify-content: flex-end; }
nav button { border: 1px solid var(--do-dash-border); border-radius: 5px; padding: 5px 12px; color: var(--do-dash-cobalt); background: var(--do-dash-surface); }
nav button:disabled { opacity: .5; }
</style>
