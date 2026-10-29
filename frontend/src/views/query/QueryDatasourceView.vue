<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleHelp, LoaderCircle, RefreshCw, Sparkles } from 'lucide-vue-next'
import brandMark from '../../assets/brand/dataocean-mark.png'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { useQuerySession, type LocalMessage } from '../../composables/useQuerySession'
import { useQuerySubmit } from '../../composables/useQuerySubmit'
import { parseDatasourceId } from '../../utils/queryDatasource'
import QuerySidebar from './QuerySidebar.vue'
import QueryDatasourceSelector from './QueryDatasourceSelector.vue'
import QueryInput from './QueryInput.vue'
import QueryAnswer from './QueryAnswer.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const iamS1 = useIamS1Store()
const queryInputRef = ref<InstanceType<typeof QueryInput>>()
const chatSurface = ref<HTMLElement>()
const mobileHistoryOpen = ref(false)
const datasourceInitialized = ref(false)
const followConversation = ref(true)
let datasourceSyncRequest = 0
let sourceChangeRequest = 0
let alive = true
const session = useQuerySession()
const selectedReadiness = computed(() => session.selectedId.value ? session.readinessMap.value[session.selectedId.value] : undefined)
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const conversationTitle = computed(() => session.activeSession.value?.title === '新的对话' ? '新对话' : session.activeSession.value?.title || '新对话')

async function scrollToLatest() {
  await nextTick()
  const surface = chatSurface.value
  if (surface) surface.scrollTop = surface.scrollHeight
}
function trackScroll() {
  const surface = chatSurface.value
  if (surface) followConversation.value = surface.scrollHeight - surface.scrollTop - surface.clientHeight < 96
}
function followLayout() { if (followConversation.value) void scrollToLatest() }
const submit = useQuerySubmit({
  selectedId: session.selectedId, activeSession: session.activeSession, activeMessages: session.activeMessages,
  canAskSelectedDatasource: session.canAskSelectedDatasource, selectedBlockReason: session.selectedBlockReason,
  createSession: session.createSession, animateNewMessages: scrollToLatest,
  async focusQuestionInput() { await queryInputRef.value?.focusQuestionInput() },
})

async function handleUserCommand(command: string) {
  if (command === 'admin') await router.push('/admin')
  if (command === 'profile') await router.push('/profile')
  if (command === 'password') await router.push('/change-password')
  if (command === 'logout') { await submit.cancelCurrentQuery(); await auth.logout(); await router.push('/login') }
}
function formatMessageTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
function messageState(message: LocalMessage) {
  return ({ loading: '正在查询', PROCESSING: message.localWaitTimedOut ? '仍在处理' : '正在查询', COMPLETED: '已完成',
    FAILED: '未完成', error: '未完成', CANCELLED: '已停止', TIMEOUT: '已超时', CLARIFICATION_REQUIRED: '需要补充信息',
  } as Record<string, string>)[message.status || '']
}
function isProcessing(message: LocalMessage) { return ['loading', 'PROCESSING'].includes(message.status || '') && !message.localWaitTimedOut }
function applySuggestion(text: string) {
  if (!session.canAskSelectedDatasource.value) return
  submit.question.value = text
  void queryInputRef.value?.focusQuestionInput()
}
async function applyDatasource(id: number) {
  const ticket = ++sourceChangeRequest
  if (session.selectedId.value === id) return
  await submit.cancelCurrentQuery()
  if (!alive || ticket !== sourceChangeRequest) return
  submit.followLatestResult()
  submit.question.value = ''
  await session.selectDatasource(id)
  if (alive && ticket === sourceChangeRequest) await scrollToLatest()
}
function queryWithDatasource(id?: number) {
  const query = { ...route.query }
  if (id) query.datasourceId = String(id)
  else delete query.datasourceId
  return query
}
async function syncDatasourceFromUrl() {
  const ticket = ++datasourceSyncRequest
  const rawId = route.query.datasourceId
  const requestedId = parseDatasourceId(rawId)
  const requested = session.datasources.value.find(item => item.id === requestedId)
  const current = session.datasources.value.find(item => item.id === session.selectedId.value)
  const target = requested || current || session.datasources.value.find(item => session.readinessMap.value[item.id]?.askable) || session.datasources.value[0]
  if (rawId !== undefined && !requested) ElMessage.warning('URL 指定的数据源不存在或当前账号无权访问，已回到可访问范围。')
  if (target) await applyDatasource(target.id)
  if (!alive || ticket !== datasourceSyncRequest || (target && session.selectedId.value !== target.id)) return
  if (parseDatasourceId(route.query.datasourceId) !== target?.id || (rawId !== undefined && requestedId === undefined)) await router.replace({ query: queryWithDatasource(target?.id) })
}
async function refreshDatasources() {
  await session.fetchDatasources({ autoSelect: false })
  if (!alive) return
  if (session.historyError.value && session.selectedId.value) await session.retryHistory()
  if (alive) await syncDatasourceFromUrl()
}
async function handleRetryHistory() {
  const ticket = ++sourceChangeRequest
  await submit.cancelCurrentQuery()
  if (alive && ticket === sourceChangeRequest) await session.retryHistory()
}
async function handleSelectDatasource(id: number) {
  await applyDatasource(id)
  // A later selection owns the URL as well as the visible conversation.
  if (alive && session.selectedId.value === id) await router.push({ query: queryWithDatasource(id) })
}
async function handleStartNewSession() {
  const ticket = ++sourceChangeRequest
  await submit.cancelCurrentQuery()
  if (!alive || ticket !== sourceChangeRequest) return
  session.startNewSession({ focusQuestionInput: () => { void queryInputRef.value?.focusQuestionInput() } })
  submit.followLatestResult()
  submit.question.value = ''
  mobileHistoryOpen.value = false
}
async function handleSelectSession(id: string) {
  const ticket = ++sourceChangeRequest
  await submit.cancelCurrentQuery()
  if (!alive || ticket !== sourceChangeRequest) return
  submit.followLatestResult()
  submit.question.value = ''
  mobileHistoryOpen.value = false
  await session.selectSession(id)
  if (!alive || ticket !== sourceChangeRequest) return
  await scrollToLatest()
}
watch(() => route.query.datasourceId, () => { if (datasourceInitialized.value) void syncDatasourceFromUrl() })
watch(() => session.activeMessages.value.map(message => `${message.status}:${message.content}`).join('|'), followLayout, { flush: 'post' })
watch(() => auth.token, () => {
  sourceChangeRequest++
  datasourceSyncRequest++
  void submit.cancelCurrentQuery()
  submit.invalidateContext()
  submit.question.value = ''
  session.reset()
  if (auth.token) void refreshDatasources()
})
onMounted(async () => {
  void iamS1.load()
  await session.fetchDatasources({ autoSelect: false })
  if (!alive) return
  datasourceInitialized.value = true
  await syncDatasourceFromUrl()
})
onBeforeUnmount(() => { alive = false; sourceChangeRequest++; datasourceSyncRequest++; void submit.cancelCurrentQuery() })
</script>

<template>
  <main class="query-workspace post-login-page">
    <QuerySidebar :datasource-sessions="session.datasourceSessions.value" :active-session-id="session.activeSessionId.value"
      :keyword="session.keyword.value" :display-name="displayName" :role-text="iamS1.systemAdmin ? '系统管理员' : '当前账号'"
      :can-enter-admin="iamS1.hasAnyAdminCapability" :mobile-history-open="mobileHistoryOpen"
      :loading="session.loading.value || session.historyLoading.value" :error-message="session.historyError.value"
      @select-session="handleSelectSession" @remove-session="session.removeSession" @new-session="handleStartNewSession"
      @retry-history="handleRetryHistory"
      @toggle-mobile-history="mobileHistoryOpen = !mobileHistoryOpen" @user-command="handleUserCommand" @update:keyword="session.keyword.value = $event" />
    <section class="query-main">
      <header class="query-topbar">
        <h1>智能问数</h1><span class="topbar-divider" aria-hidden="true">/</span><span class="conversation-title" :title="conversationTitle">{{ conversationTitle }}</span>
        <RouterLink to="/guide/query" class="query-help"><CircleHelp :size="16" aria-hidden="true" /><span>使用帮助</span></RouterLink>
      </header>
      <div v-if="iamS1.errorMessage" class="workspace-error" role="alert">{{ iamS1.errorMessage }} <button type="button" @click="iamS1.load(true)">重试能力检查</button></div>
      <div ref="chatSurface" class="chat-surface" aria-label="当前对话" @scroll="trackScroll">
        <div v-if="session.loading.value || session.historyLoading.value" class="welcome-state" role="status"><LoaderCircle :size="24" aria-hidden="true" /><h2>正在读取数据与会话</h2></div>
        <div v-else-if="session.errorMessage.value" class="welcome-state" role="alert"><h2>数据源读取失败</h2><p>{{ session.errorMessage.value }}</p><button class="state-button" type="button" @click="refreshDatasources">重新读取</button></div>
        <div v-else-if="!session.selectedId.value" class="welcome-state"><img class="welcome-logo" :src="brandMark" alt="" /><h2>从一份业务数据开始</h2><p>当前账号没有可查询的数据源。请联系管理员确认数据授权与知识就绪情况。</p><button class="state-button" type="button" @click="refreshDatasources"><RefreshCw :size="15" aria-hidden="true" />刷新数据源</button></div>
        <div v-else-if="!session.activeMessages.value.length" class="welcome-state">
          <span class="welcome-eyebrow"><Sparkles :size="15" aria-hidden="true" />让业务问题，找到数据答案</span>
          <h2>你想从数据中了解什么？</h2><p>用自然语言描述问题，补充指标、时间范围和分组方式。<br />我会依据当前授权知识查询，口径不明确时先向你确认。</p>
          <div class="question-tips"><span>一个清楚的问题，可以包含</span><strong>要看什么指标</strong><strong>哪个时间范围</strong><strong>按什么维度比较</strong></div>
        </div>
        <section v-else class="conversation-stream" aria-label="对话消息">
          <button v-if="session.activeSession.value?.hasMoreHistory" class="state-button load-older-messages" type="button" :disabled="session.activeSession.value.historyLoading" @click="session.loadOlderMessages(session.activeSession.value)">{{ session.activeSession.value.historyLoading ? '正在加载…' : '加载更早消息' }}</button>
          <article v-for="message in session.activeMessages.value" :key="message.id" class="message-item" :class="message.role" :data-message-id="message.id">
            <div class="message-author"><img v-if="message.role === 'assistant'" :src="brandMark" alt="" width="16" height="24" /><strong>{{ message.role === 'assistant' ? 'DataOcean' : '你' }}</strong><time :datetime="message.createdAt">{{ formatMessageTime(message.createdAt) }}</time></div>
            <div class="message-bubble">
              <div v-if="message.role === 'assistant' && messageState(message)" class="message-state" :class="{ processing: isProcessing(message), failed: ['FAILED', 'error', 'TIMEOUT'].includes(message.status || '') }" role="status"><LoaderCircle v-if="isProcessing(message)" :size="14" aria-hidden="true" /><span>{{ messageState(message) }}</span><code v-if="isProcessing(message) && message.queryResult?.progressNode">{{ message.queryResult.progressNode }}</code></div>
              <p v-if="message.role !== 'assistant' || message.status !== 'COMPLETED'">{{ message.content }}</p>
              <QueryAnswer v-if="message.role === 'assistant' && message.status === 'COMPLETED' && message.taskId && session.activeSession.value" :key="`${auth.token}:${message.taskId}`" :task-id="message.taskId" :datasource-id="session.activeSession.value.datasourceId" :original-question="message.originalQuestion || message.queryResult?.question || ''" @suggestion="applySuggestion" @layout-ready="followLayout" />
              <div v-if="message.role === 'assistant'" class="message-actions">
                <button v-if="message.status === 'CLARIFICATION_REQUIRED'" type="button" :disabled="submit.isQuerying.value" @click="submit.prepareClarification(message.originalQuestion || message.queryResult?.question || '')">补充问题</button>
                <button v-if="['FAILED', 'CANCELLED', 'TIMEOUT', 'error'].includes(message.status || '')" type="button" :disabled="submit.isQuerying.value" @click="submit.retryQuery(message.originalQuestion || message.queryResult?.question || '')">保留原问题重试</button>
                <button v-if="submit.isLocallyResumable(message) && message.taskId" type="button" :disabled="submit.isQuerying.value" @click="submit.continueWaiting(message.taskId)">恢复等待</button>
                <button v-if="submit.isLocallyResumable(message)" type="button" :disabled="submit.isQuerying.value" @click="submit.retryQuery(message.originalQuestion || '')">重新提问</button>
              </div>
              <div v-if="message.role === 'assistant' && message.status !== 'COMPLETED' && message.queryResult?.suggestedQuestions?.length" class="message-suggestions" aria-label="推荐追问"><small>继续了解</small><button v-for="suggestion in message.queryResult.suggestedQuestions" :key="suggestion" type="button" @click="applySuggestion(suggestion)">{{ suggestion }}</button></div>
            </div>
          </article>
        </section>
      </div>
      <div class="composer-dock">
        <QueryInput ref="queryInputRef" :question="submit.question.value" :is-querying="submit.isQuerying.value" :selected-id="session.selectedId.value"
          :selected-datasource-name="session.selectedDatasource.value?.name" :can-ask="session.canAskSelectedDatasource.value"
          :readiness-loading="session.readinessLoading.value" :selected-block-reason="session.selectedBlockReason.value"
          @update:question="submit.question.value = $event" @send="submit.sendQuestion" @cancel="submit.cancelCurrentQuery">
          <template #context><QueryDatasourceSelector :datasources="session.datasources.value" :readiness-map="session.readinessMap.value" :selected-id="session.selectedId.value"
            :loading="session.loading.value" :readiness-loading="session.readinessLoading.value" :error-message="session.errorMessage.value" @select="handleSelectDatasource" @refresh="refreshDatasources" />
            <small v-if="selectedReadiness?.ragStale" class="readiness-note" role="status">知识版本落后，按当前快照与权限校验</small></template>
        </QueryInput>
      </div>
    </section>
  </main>
</template>

<style scoped>
.query-workspace { height: 100dvh; min-height: 0; width: 100%; display: grid; grid-template-columns: 248px minmax(0, 1fr); overflow: hidden; color: var(--do-ink); background: var(--do-bg); font-size: 14px; }
.query-main { min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.query-topbar { flex: none; min-width: 0; min-height: 72px; display: flex; align-items: center; gap: 16px; padding: 12px 36px; border-bottom: 1px solid var(--do-line); }
.query-topbar h1 { flex: none; margin: 0; font-size: 16px; font-weight: 650; }
.topbar-divider { color: var(--do-line-strong); }
.conversation-title { min-width: 0; overflow: hidden; color: var(--do-muted); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.query-help { flex: none; display: flex; align-items: center; gap: 6px; margin-left: auto; min-height: 40px; color: var(--do-muted); font-size: 12px; }
.workspace-error { padding: 10px 24px; color: var(--do-danger); background: var(--do-danger-soft); }
.workspace-error button { min-height: 36px; border: 1px solid var(--do-line); border-radius: 6px; color: var(--do-ink); background: var(--do-surface); cursor: pointer; }
.chat-surface { flex: 1; min-width: 0; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; padding: 34px 36px 40px; }
.conversation-stream { max-width: 820px; margin-inline: auto; display: flex; flex-direction: column; gap: 32px; }
.message-item { min-width: 0; }
.message-author { display: flex; align-items: center; gap: 9px; margin-bottom: 10px; font-size: 13px; }
.message-author img { object-fit: contain; }
.message-author strong { font-weight: 650; }
.message-author time { margin-left: auto; color: var(--do-muted); font-size: 11px; }
.message-bubble { min-width: 0; }
.message-bubble > p { margin: 0; color: var(--do-ink); font-size: 15px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-item.user { align-self: flex-end; max-width: 88%; padding: 14px 18px; border: 1px solid var(--do-line); border-radius: 14px 14px 4px 14px; background: #f0f2ec; }
.message-item.user .message-author { gap: 20px; }
.message-state { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-bottom: 7px; color: var(--do-muted); font-size: 12px; }
.message-state.processing { color: var(--do-primary-strong); }
.message-state.failed { color: var(--do-danger); }
.message-state code { font-size: 10px; color: var(--do-muted); }
.message-state.processing svg { animation: stage-spin 1.1s linear infinite; }
@keyframes stage-spin { to { transform: rotate(360deg); } }
.message-actions, .message-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.message-actions:empty { display: none; }
.message-actions button, .message-suggestions button, .state-button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 40px; padding: 0 12px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.message-actions, .message-suggestions { margin-top: 14px; }
.message-suggestions small { color: var(--do-muted); }
button:disabled { cursor: not-allowed; opacity: .55; }
.load-older-messages { align-self: center; }
.welcome-state { min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px 0 54px; text-align: center; }
.welcome-eyebrow { display: inline-flex; align-items: center; gap: 8px; margin-bottom: 22px; color: var(--do-primary-strong); font-size: 12px; }
.welcome-state h2 { margin: 0 0 16px; font-size: clamp(24px, 2.5vw, 32px); font-weight: 580; letter-spacing: -1px; }
.welcome-state p { max-width: 520px; margin: 0 0 24px; color: var(--do-muted); font-size: 14px; line-height: 1.9; }
.welcome-logo { width: 34px; height: 54px; object-fit: contain; margin-bottom: 20px; }
.question-tips { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; max-width: 570px; margin-top: 12px; }
.question-tips > span { flex-basis: 100%; margin-bottom: 5px; color: var(--do-muted); font-size: 11px; }
.question-tips strong { padding: 10px 14px; border: 1px solid var(--do-line); border-radius: 9px; color: var(--do-ink); font-size: 12px; font-weight: 500; background: var(--do-surface); }
.composer-dock { flex: none; min-width: 0; max-height: 48dvh; overflow-y: auto; padding: 12px 36px 22px; background: var(--do-bg); }
.readiness-note { display: block; margin-top: 5px; color: var(--do-warning); font-size: 11px; }
:deep(.datasource-trigger) { min-height: 34px; max-width: 100%; border-color: transparent; background: var(--do-soft); }
:deep(.source-name) { font-size: 12px; }
@media (max-width: 1024px) { .query-workspace { grid-template-columns: 220px minmax(0,1fr); } .chat-surface { padding-inline: 24px; } .query-topbar { padding-inline: 24px; } .composer-dock { padding-inline: 24px; } }
@media (max-width: 768px) {
  .query-workspace { grid-template-columns: minmax(0,1fr); grid-template-rows: auto minmax(0,1fr); }
  .query-topbar { gap: 10px; min-height: 50px; padding: 4px 16px; }
  .query-help span { display: none; }
  .chat-surface { padding: 24px 16px; }
  .composer-dock { padding: 8px 12px max(12px, env(safe-area-inset-bottom)); }
  .welcome-state { padding-bottom: 24px; }
  .welcome-state h2 { font-size: 24px; }
  .welcome-state p { font-size: 13px; }
  .message-item.user { max-width: 95%; }
  .message-bubble > p { font-size: 16px; }
  .conversation-stream { gap: 24px; }
}
</style>
