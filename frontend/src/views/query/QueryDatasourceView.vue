<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleHelp, LoaderCircle, RefreshCw } from 'lucide-vue-next'
import BrandMark from '../../components/common/BrandMark.vue'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { useQuerySession, type LocalMessage, type LocalSession } from '../../composables/useQuerySession'
import { useQuerySubmit } from '../../composables/useQuerySubmit'
import { parseDatasourceId } from '../../utils/queryDatasource'
import { buildAgentProgress, queryFailureMessage } from '../../utils/queryDisplayState'
import QuerySidebar from './QuerySidebar.vue'
import QueryDatasourceSelector from './QueryDatasourceSelector.vue'
import QueryInput from './QueryInput.vue'
import QueryAnswer from './QueryAnswer.vue'
import QueryScope from './QueryScope.vue'

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
  // CANCELLED 表示本次结果交付已取消，不把远端接收或本地终态说成执行已确认停止。
  return ({ loading: '正在查询', PROCESSING: message.localWaitTimedOut ? '仍在处理' : '正在查询', COMPLETED: '已完成',
    FAILED: '未完成', error: '未完成', CANCELLED: '已取消', TIMEOUT: '已超时', CLARIFICATION_REQUIRED: '需要补充信息',
  } as Record<string, string>)[message.status || '']
}
function progressLabel(message: LocalMessage) { return buildAgentProgress(message.queryResult || null).find(node => node.status === 'active')?.label || '正在核对查询' }
function messageText(message: LocalMessage) { return message.role === 'assistant' && ['FAILED', 'error'].includes(message.status || '') ? queryFailureMessage(message.content) : message.content }
function isProcessing(message: LocalMessage) { return ['loading', 'PROCESSING'].includes(message.status || '') && !message.localWaitTimedOut }
function applySuggestion(text: string) {
  if (!session.canAskSelectedDatasource.value) return
  submit.question.value = text
  void queryInputRef.value?.focusQuestionInput()
}
async function applyDatasource(id: number) {
  const ticket = ++sourceChangeRequest
  if (session.selectedId.value === id) return
  if (!await submit.cancelCurrentQuery()) { ElMessage.warning('停止尚未确认，已保留当前对话；请等待结果或重试停止。'); return }
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
  if (!await submit.cancelCurrentQuery()) { ElMessage.warning('停止尚未确认，已保留当前对话；请等待结果或重试停止。'); return }
  if (alive && ticket === sourceChangeRequest) await session.retryHistory()
}
async function handleSelectDatasource(id: number) {
  await applyDatasource(id)
  // A later selection owns the URL as well as the visible conversation.
  if (alive && session.selectedId.value === id) await router.push({ query: queryWithDatasource(id) })
}
async function handleStartNewSession() {
  const ticket = ++sourceChangeRequest
  if (!await submit.cancelCurrentQuery()) { ElMessage.warning('停止尚未确认，已保留当前对话；请等待结果或重试停止。'); return }
  if (!alive || ticket !== sourceChangeRequest) return
  session.startNewSession({ focusQuestionInput: () => { void queryInputRef.value?.focusQuestionInput() } })
  submit.followLatestResult()
  submit.question.value = ''
  mobileHistoryOpen.value = false
}
async function handleSelectSession(id: string) {
  const ticket = ++sourceChangeRequest
  if (!await submit.cancelCurrentQuery()) { ElMessage.warning('停止尚未确认，已保留当前对话；请等待结果或重试停止。'); return }
  if (!alive || ticket !== sourceChangeRequest) return
  submit.followLatestResult()
  submit.question.value = ''
  mobileHistoryOpen.value = false
  await session.selectSession(id)
  if (!alive || ticket !== sourceChangeRequest) return
  await scrollToLatest()
}
async function handleRemoveSession(target: LocalSession) {
  const identity = auth.token
  const accepts = () => alive && auth.token === identity
  await session.removeSession(target, {
    accepts,
    async beforeDelete() {
      if (!accepts()) return false
      if (session.activeSessionId.value !== target.id || !submit.isQuerying.value) return true
      const stopped = await submit.cancelCurrentQuery()
      if (!accepts()) return false
      if (!stopped) ElMessage.warning('停止尚未确认，已保留当前对话；请等待结果或重试删除。')
      return stopped
    },
  })
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
      @select-session="handleSelectSession" @remove-session="handleRemoveSession" @new-session="handleStartNewSession"
      @retry-history="handleRetryHistory"
      @toggle-mobile-history="mobileHistoryOpen = !mobileHistoryOpen" @user-command="handleUserCommand" @update:keyword="session.keyword.value = $event" />
    <section class="query-main">
      <!-- 查询范围常驻页头，长对话滚动时仍能核对来源；所有操作沿用原有取消与授权保护。 -->
      <header class="query-topbar">
        <div class="query-heading">
          <h1>智能问数</h1>
          <span class="conversation-title" :title="conversationTitle">{{ conversationTitle }}</span>
        </div>
        <div class="query-context-bar" aria-label="当前查询范围">
          <QueryDatasourceSelector :datasources="session.datasources.value" :readiness-map="session.readinessMap.value" :readiness-errors="session.readinessErrors.value" :selected-id="session.selectedId.value"
            :loading="session.loading.value" :readiness-loading="session.readinessLoading.value" :error-message="session.errorMessage.value" @select="handleSelectDatasource" @refresh="refreshDatasources" />
          <QueryScope v-if="session.selectedId.value" :datasource-id="session.selectedId.value" :datasource-name="session.selectedDatasource.value?.name" />
        </div>
        <RouterLink to="/guide/query" class="query-help" aria-label="问数使用帮助"><CircleHelp :size="18" aria-hidden="true" /></RouterLink>
      </header>
      <!-- 版本提示属于当前查询范围，保持可见但不将旧索引误报为失效。 -->
      <div v-if="selectedReadiness?.ragStale" class="readiness-note" role="status">知识版本落后，按当前快照与权限校验</div>
      <div v-if="iamS1.errorMessage" class="workspace-error" role="alert">{{ iamS1.errorMessage }} <button type="button" @click="iamS1.load(true)">重试能力检查</button></div>
      <div ref="chatSurface" class="chat-surface" aria-label="当前对话" @scroll="trackScroll">
        <div v-if="session.loading.value || session.historyLoading.value" class="welcome-state" role="status"><LoaderCircle :size="24" aria-hidden="true" /><h2>正在读取数据与会话</h2></div>
        <div v-else-if="session.errorMessage.value" class="welcome-state" role="alert"><h2>数据源读取失败</h2><p>{{ session.errorMessage.value }}</p><button class="state-button" type="button" @click="refreshDatasources">重新读取</button></div>
        <div v-else-if="!session.selectedId.value" class="welcome-state"><BrandMark class="welcome-logo" aria-hidden="true" /><h2>从一份业务数据开始</h2><p>当前账号没有可查询的数据源。请联系组织指定管理员核对授权及知识准备情况。此处不展示无权来源。</p><details class="no-source-next-step"><summary>向管理员提供哪些信息</summary><ul><li>当前账号，以及希望完成的业务问题</li><li>期望业务主题与所需数据范围</li><li>当前页面提示“没有可查询的数据源”</li></ul><p>本页没有配置授权申请或联系入口，请通过组织既有渠道联系；管理员核对后再刷新。</p></details><button class="state-button" type="button" @click="refreshDatasources"><RefreshCw :size="15" aria-hidden="true" />刷新数据源</button></div>
        <!-- 空对话只引导实际提问，不展示未经授权目录核对的业务示例。 -->
        <div v-else-if="!session.activeMessages.value.length" class="welcome-state welcome-prompt">
          <BrandMark class="welcome-logo" aria-hidden="true" />
          <h2>想从数据中了解什么？</h2>
          <p>描述你想了解的指标、时间范围或比较维度。</p>
        </div>
        <section v-else class="conversation-stream" aria-label="对话消息">
          <button v-if="session.activeSession.value?.hasMoreHistory" class="state-button load-older-messages" type="button" :disabled="session.activeSession.value.historyLoading" @click="session.loadOlderMessages(session.activeSession.value)">{{ session.activeSession.value.historyLoading ? '正在加载…' : '加载更早消息' }}</button>
          <article v-for="message in session.activeMessages.value" :key="message.id" class="message-item" :class="message.role" :data-message-id="message.id">
            <div class="message-author"><BrandMark v-if="message.role === 'assistant'" class="message-author-mark" aria-hidden="true" /><strong>{{ message.role === 'assistant' ? 'DataOcean' : '你' }}</strong><time :datetime="message.createdAt">{{ formatMessageTime(message.createdAt) }}</time></div>
            <div class="message-bubble">
              <div v-if="message.role === 'assistant' && messageState(message)" class="message-state" :class="{ processing: isProcessing(message), failed: ['FAILED', 'error', 'TIMEOUT'].includes(message.status || '') }" role="status"><LoaderCircle v-if="isProcessing(message)" :size="14" aria-hidden="true" /><span>{{ messageState(message) }}</span><code v-if="isProcessing(message) && message.queryResult?.progressNode">{{ progressLabel(message) }}</code></div>
              <p v-if="message.role !== 'assistant' || message.status !== 'COMPLETED'">{{ messageText(message) }}</p>
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
      <!-- 输入与消息共用阅读宽度，停止按钮始终由原任务状态控制。 -->
      <div class="composer-dock">
        <QueryInput ref="queryInputRef" :question="submit.question.value" :is-querying="submit.isQuerying.value" :selected-id="session.selectedId.value"
          :selected-datasource-name="session.selectedDatasource.value?.name" :can-ask="session.canAskSelectedDatasource.value"
          :readiness-loading="session.readinessLoading.value" :selected-block-reason="session.selectedBlockReason.value"
          @update:question="submit.question.value = $event" @send="submit.sendQuestion" @cancel="submit.cancelCurrentQuery" @retry-readiness="session.fetchDatasourceReadiness(session.datasources.value)" />
      </div>
    </section>
  </main>
</template>

<style scoped>
/* 单层工作面固定导航与范围，只有对话正文和必要的大输入区独立滚动。 */
.query-workspace { height: 100dvh; min-height: 0; width: 100%; display: grid; grid-template-columns: 248px minmax(0, 1fr); overflow: hidden; color: var(--do-ink); background: var(--do-surface); font-size: 14px; }
.query-main { min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.query-topbar { flex: none; min-width: 0; min-height: 84px; display: flex; align-items: center; gap: 20px; padding: 16px 32px; border-bottom: 1px solid var(--do-line); }
.query-heading { min-width: 120px; display: grid; gap: 5px; }
.query-topbar h1 { margin: 0; font-size: 17px; font-weight: 650; line-height: 1.25; }
.conversation-title { max-width: 320px; overflow: hidden; color: var(--do-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.query-context-bar { min-width: 0; display: flex; align-items: center; gap: 8px; margin-left: auto; }
.query-help { flex: none; width: 36px; height: 36px; display: grid; place-items: center; border-radius: var(--do-radius-sm); color: var(--do-muted); }
.query-help:hover { color: var(--do-primary-strong); background: var(--do-soft); }
:deep(.query-context-bar .datasource-trigger) { min-height: 38px; max-width: min(360px, 34vw); background: var(--do-soft); border-color: transparent; }
:deep(.query-context-bar .query-scope-trigger) { margin-left: 0; white-space: nowrap; }
.readiness-note { padding: 9px 32px; color: var(--do-warning); background: var(--do-warning-soft); font-size: 12px; }
.workspace-error { padding: 10px 32px; color: var(--do-danger); background: var(--do-danger-soft); }
.workspace-error button { min-height: 36px; margin-left: 8px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-ink); background: var(--do-surface); cursor: pointer; }

/* 对话正文使用连续阅读列；用户问题与助手答案通过对齐、字重及表面区分。 */
.chat-surface { flex: 1; min-width: 0; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; padding: 36px 32px; }
.conversation-stream { max-width: 900px; margin-inline: auto; display: flex; flex-direction: column; gap: 36px; }
.message-item { min-width: 0; }
.message-author { display: flex; align-items: center; gap: 9px; margin-bottom: 12px; font-size: 13px; }
/* 作者名称已出现在标识旁，消息行只保留紧凑的品牌图形。 */
.message-author-mark { width: 18px; height: 24px; flex: none; }
.message-author strong { font-weight: 650; }
.message-author time { margin-left: auto; color: var(--do-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.message-bubble { min-width: 0; }
.message-bubble > p { margin: 0; color: var(--do-ink); font-size: 15px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-item.user { align-self: flex-end; max-width: 88%; padding: 14px 18px; border-radius: 14px 14px 4px 14px; background: var(--do-soft); }
.message-item.user .message-author { gap: 20px; margin-bottom: 7px; }
.message-state { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-bottom: 8px; color: var(--do-muted); font-size: 12px; }
.message-state.processing { color: var(--do-primary-strong); }
.message-state.failed { color: var(--do-danger); }
.message-state code { font: inherit; color: var(--do-muted); }
.message-state.processing svg { animation: stage-spin 1.1s linear infinite; }
@keyframes stage-spin { to { transform: rotate(360deg); } }

/* 失败、澄清和恢复操作保留在所属回答下，不以新的布局改变任务语义。 */
.message-actions, .message-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 14px; }
.message-actions:empty { display: none; }
.message-actions button, .message-suggestions button, .state-button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 38px; padding: 0 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.message-actions button:hover:not(:disabled), .message-suggestions button:hover, .state-button:hover:not(:disabled) { border-color: var(--do-primary); background: var(--do-primary-soft); }
.message-suggestions small { color: var(--do-muted); }
button:disabled { cursor: not-allowed; opacity: .55; }
.load-older-messages { align-self: center; }

/* 空态提供简短任务引导，缺授权时保留具体核对步骤和刷新入口。 */
.welcome-state { min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px 0 40px; text-align: center; }
.welcome-state h2 { margin: 0 0 14px; font-size: clamp(24px, 2.2vw, 30px); font-weight: 600; letter-spacing: -.6px; }
.welcome-state p { max-width: 520px; margin: 0 0 24px; color: var(--do-muted); font-size: 14px; line-height: 1.8; }
.welcome-logo { width: 32px; height: 48px; margin-bottom: 24px; }
.welcome-prompt { padding-bottom: 56px; }
.no-source-next-step { max-width:520px; text-align:left; padding:10px 14px; border:1px solid var(--do-line); border-radius:8px; font-size:13px; line-height:1.6; }
.no-source-next-step summary { min-height:32px; cursor:pointer; color:var(--do-primary-strong); }
.no-source-next-step summary:focus-visible { outline:2px solid var(--do-primary); }
.no-source-next-step p { font-size:12px; }

/* 输入停靠区与正文分层但不叠卡，长草稿仍保持发送或停止按钮可达。 */
.composer-dock { flex: none; min-width: 0; max-height: 48dvh; overflow-y: auto; padding: 12px 32px 20px; background: var(--do-surface); }
@media (max-width: 1100px) { .query-workspace { grid-template-columns: 220px minmax(0, 1fr); } .query-topbar { gap: 12px; padding-inline: 20px; } .chat-surface, .composer-dock { padding-inline: 24px; } .conversation-title { max-width: 200px; } }
/* 窄窗口沿用历史展开机制；范围换行后仍显示同一组真实入口。 */
@media (max-width: 768px) {
  .query-workspace { grid-template-columns: minmax(0,1fr); grid-template-rows: auto minmax(0,1fr); }
  .query-topbar { flex-wrap: wrap; gap: 10px; min-height: 0; padding: 12px 16px; }
  .query-heading { flex: 1; }
  .query-help { margin-left: auto; }
  .query-context-bar { order: 1; flex-basis: 100%; margin-left: 0; }
  :deep(.query-context-bar .datasource-picker) { flex: 1; }
  :deep(.query-context-bar .datasource-trigger) { width: 100%; max-width: none; }
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
