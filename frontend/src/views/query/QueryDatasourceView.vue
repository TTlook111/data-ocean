<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleHelp, Info, PanelRightOpen, RefreshCw, UserRound } from 'lucide-vue-next'
import { useGsapMotion } from '../../composables/useGsapMotion'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { useQuerySession, type LocalMessage } from '../../composables/useQuerySession'
import { useQuerySubmit } from '../../composables/useQuerySubmit'
import { useQueryExport } from '../../composables/useQueryExport'
import { parseDatasourceId } from '../../utils/queryDatasource'
import { buildQueryChartOption, getDefaultQueryChartType } from '../../utils/queryChart'
import QuerySidebar from './QuerySidebar.vue'
import QueryDatasourceSelector from './QueryDatasourceSelector.vue'
import QueryInput from './QueryInput.vue'
import QueryResult from './QueryResult.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const iamS1 = useIamS1Store()
const workspaceRef = ref<HTMLElement | null>(null)
const queryInputRef = ref<InstanceType<typeof QueryInput>>()
const queryResultRef = ref<InstanceType<typeof QueryResult>>()
const resultPanelOpen = ref(false)
const workspaceMode = ref<'conversation' | 'result'>('conversation')
const mobileHistoryOpen = ref(false)
const datasourceInitialized = ref(false)
let datasourceSyncRequest = 0
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(workspaceRef)

const canEnterAdmin = computed(() => iamS1.hasAnyAdminCapability)
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => iamS1.systemAdmin ? '系统管理员' : '当前账号')

const session = useQuerySession()
const selectedReadiness = computed(() => {
  const id = session.selectedId.value
  return id ? session.readinessMap.value[id] : undefined
})
const submit = useQuerySubmit({
  selectedId: session.selectedId,
  activeSession: session.activeSession,
  activeMessages: session.activeMessages,
  canAskSelectedDatasource: session.canAskSelectedDatasource,
  selectedBlockReason: session.selectedBlockReason,
  createSession: session.createSession,
  async animateNewMessages() {
    await nextTick()
    const lastMessage = workspaceRef.value?.querySelector('.message-item:last-of-type')
    if (lastMessage) lift(lastMessage, { y: 14, duration: 0.26 })
  },
  async animateMessageUpdate(messageId: string) {
    await nextTick()
    const assistantBubble = workspaceRef.value?.querySelector(`[data-message-id="${messageId}"] .message-bubble`)
    if (assistantBubble) lift(assistantBubble, { y: 6, scale: 1, duration: 0.22 })
  },
  async focusQuestionInput() {
    queryInputRef.value?.focusQuestionInput()
  },
})

const displayResult = submit.displayResult
const resultContextAvailable = computed(() => submit.displayState.value.kind !== 'empty')
const showResultPanel = computed(() => resultPanelOpen.value && resultContextAvailable.value)
const conversationTitle = computed(() => {
  const title = session.activeSession.value?.title?.trim()
  return !title || title === '新的对话' ? '新对话' : title
})
const earlierResultLabel = computed(() => {
  const selectedTaskId = submit.selectedResultTaskId.value
  if (!selectedTaskId) return ''
  if (submit.latestAssistantMessage.value?.taskId === selectedTaskId) return ''
  const message = session.activeMessages.value.find((item) => item.role === 'assistant' && item.taskId === selectedTaskId)
  return message ? `历史结果 · ${formatMessageTime(message.createdAt)}` : '历史结果'
})
const displayDatasourceId = computed(() => session.activeSession.value?.datasourceId ?? session.selectedId.value)
const datasourceFacts = computed(() => {
  const readiness = selectedReadiness.value
  if (!readiness) return []
  return [
    { label: '最新采集快照', value: `v${readiness.latestCollectedSnapshotVersion ?? '—'}` },
    { label: '当前发布快照', value: `v${readiness.snapshotVersion ?? '—'}` },
    { label: 'RAG 来源快照', value: `v${readiness.ragSourceSnapshotVersion ?? '—'}` },
    { label: '知识版本状态', value: readiness.ragStale ? '版本落后' : '与当前发布版本一致' },
  ]
})
const canViewSql = computed(() =>
  displayResult.value?.status === 'COMPLETED'
  && displayResult.value.canViewSql !== false
  && displayDatasourceId.value !== undefined
  && iamS1.canOnDatasource('query:sql:view', displayDatasourceId.value),
)
const canExport = computed(() =>
  displayResult.value?.canExport !== false
  && displayDatasourceId.value !== undefined
  && iamS1.canOnDatasource('query:export', displayDatasourceId.value),
)
const sqlErrorMessage = computed(() =>
  submit.sqlErrorTaskId.value === submit.displayTaskId.value ? submit.sqlErrorMessage.value : '',
)
const displayOriginalQuestion = computed(() => {
  const taskId = submit.displayTaskId.value
  const message = taskId ? session.activeMessages.value.find((item) => item.taskId === taskId) : submit.latestAssistantMessage.value
  return displayResult.value?.question || message?.originalQuestion || ''
})

const exportUtil = useQueryExport({ latestResult: displayResult, canExport })
const chartOption = computed<Record<string, unknown> | null>(() => {
  const styles = getComputedStyle(document.documentElement)
  const token = (name: string) => styles.getPropertyValue(name).trim()
  return buildQueryChartOption(displayResult.value?.chartConfig, submit.chartType.value, {
    colors: ['--do-primary-strong', '--do-accent', '--do-info', '--do-warning', '--do-tone-purple', '--do-primary'].map(token).filter(Boolean),
    textColor: token('--do-ink'), mutedColor: token('--do-muted'), borderColor: token('--do-line'), fontFamily: token('--do-font-family'),
  })
})

watch(() => displayResult.value?.taskId, () => {
  exportUtil.tablePage.value = 1
})
watch([() => displayResult.value?.taskId, () => displayResult.value?.chartConfig], ([taskId, config], [previousTaskId, previousConfig]) => {
  if (config && (taskId !== previousTaskId || !previousConfig)) submit.chartType.value = getDefaultQueryChartType(config)
})
watch(() => submit.latestResult.value?.taskId, (taskId, previousTaskId) => {
  if (!taskId || taskId === previousTaskId) return
  exportUtil.tablePage.value = 1
  // 只有真实完成的结果才默认并排打开；澄清、失败和处理只留在对话中说明。
  if (submit.latestResult.value?.status === 'COMPLETED') resultPanelOpen.value = true
})
watch(submit.resultTab, () => {
  nextTick(() => {
    const resultBody = workspaceRef.value?.querySelector('.result-scroll')
    if (resultBody) lift(resultBody, { y: 5, duration: 0.2, scale: 1 })
  })
})

async function handleUserCommand(command: string) {
  if (command === 'admin') router.push('/admin')
  if (command === 'profile') router.push('/profile')
  if (command === 'password') router.push('/change-password')
  if (command === 'logout') {
    await auth.logout()
    await router.push('/login')
  }
}

function pad(value: number) {
  return String(value).padStart(2, '0')
}

/** 消息时间只保留当天时分；跨天再补月日，减少正文旁的辅助信息。 */
function formatMessageTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  const now = new Date()
  return date.toDateString() === now.toDateString() ? time : `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${time}`
}

function isClarification(message: LocalMessage) {
  return message.role === 'assistant' && message.status === 'CLARIFICATION_REQUIRED'
}

function canOpenMessageResult(message: LocalMessage) {
  return message.role === 'assistant' && message.queryResult?.status === 'COMPLETED'
}

/** 助手消息的可执行操作只出现在澄清、超时、失败等需要用户接手的终态。 */
function hasMessageActions(message: LocalMessage) {
  return message.status === 'CLARIFICATION_REQUIRED'
    || message.status === 'TIMEOUT'
    || submit.isLocallyResumable(message)
    || ['FAILED', 'CANCELLED', 'error'].includes(message.status || '')
}

function messageState(message: LocalMessage): { label: string; tone: 'processing' | 'success' | 'danger' | 'muted' } | undefined {
  if (message.role !== 'assistant') return undefined
  switch (message.status) {
    case 'loading':
      return { label: '正在查询', tone: 'processing' }
    case 'PROCESSING':
      return { label: message.localWaitTimedOut ? '仍在处理' : '正在查询', tone: 'processing' }
    case 'COMPLETED':
      return { label: '已完成', tone: 'success' }
    case 'FAILED':
    case 'error':
      return { label: '未完成', tone: 'danger' }
    case 'TIMEOUT':
      return { label: '已超时', tone: 'danger' }
    case 'CANCELLED':
      return { label: '已停止', tone: 'muted' }
    default:
      return undefined
  }
}

function applySuggestion(text: string) {
  if (!session.selectedId.value || !session.canAskSelectedDatasource.value) return
  submit.question.value = text
  queryInputRef.value?.focusQuestionInput()
}

function afterDatasourceSelected() {
  revealAfterTick('.welcome-state, .message-item', { y: 12, stagger: 0.035 })
}

async function applyDatasource(id: number) {
  if (session.selectedId.value === id) return
  await submit.cancelCurrentQuery()
  submit.followLatestResult()
  submit.question.value = ''
  resultPanelOpen.value = false
  workspaceMode.value = 'conversation'
  await session.selectDatasource(id, { afterSelect: afterDatasourceSelected })
}

function queryWithDatasource(id?: number) {
  const query = { ...route.query }
  if (id) query.datasourceId = String(id)
  else delete query.datasourceId
  return query
}

async function syncDatasourceFromUrl() {
  const requestId = ++datasourceSyncRequest
  const rawDatasourceId = route.query.datasourceId
  const requestedId = parseDatasourceId(rawDatasourceId)
  const requestedDatasource = requestedId === undefined
    ? undefined
    : session.datasources.value.find((item) => item.id === requestedId)
  const currentDatasource = session.selectedId.value
    ? session.datasources.value.find((item) => item.id === session.selectedId.value)
    : undefined
  const fallbackDatasource = currentDatasource
    || session.datasources.value.find((item) => session.readinessMap.value[item.id]?.askable === true)
    || session.datasources.value[0]
  const targetDatasource = requestedDatasource || fallbackDatasource

  if (rawDatasourceId !== undefined && !requestedDatasource) {
    const fallbackLabel = fallbackDatasource ? `已切换到「${fallbackDatasource.name}」` : '当前没有可用数据源'
    ElMessage.warning(`URL 指定的数据源不存在或当前账号无权访问，${fallbackLabel}。`)
  }

  if (requestId !== datasourceSyncRequest) return
  if (targetDatasource) await applyDatasource(targetDatasource.id)
  if (requestId !== datasourceSyncRequest) return

  const targetId = targetDatasource?.id
  const currentQueryId = parseDatasourceId(route.query.datasourceId)
  if (targetId !== currentQueryId || (rawDatasourceId !== undefined && requestedId === undefined)) {
    await router.replace({ query: queryWithDatasource(targetId) })
  }
}

async function refreshDatasources() {
  await session.fetchDatasources({ autoSelect: false })
  await syncDatasourceFromUrl()
}

async function handleSelectDatasource(id: number) {
  await applyDatasource(id)
  await router.push({ query: queryWithDatasource(id) })
  queryInputRef.value?.focusQuestionInput()
}

function handleStartNewSession() {
  session.startNewSession({ focusQuestionInput: () => queryInputRef.value?.focusQuestionInput() })
  submit.followLatestResult()
  submit.question.value = ''
  resultPanelOpen.value = false
  workspaceMode.value = 'conversation'
  mobileHistoryOpen.value = false
}

function handleSendQuestion() {
  submit.followLatestResult()
  resultPanelOpen.value = true
  void submit.sendQuestion()
}

function handleResultTab(tab: 'table' | 'sql' | 'chart' | 'trust') {
  submit.resultTab.value = tab
  if (tab === 'sql' && canViewSql.value) void submit.refreshSql(displayResult.value)
}

function handleRetrySql() {
  if (canViewSql.value) void submit.refreshSql(displayResult.value)
}

function toggleResultPanel() {
  if (!resultContextAvailable.value) return
  resultPanelOpen.value = !resultPanelOpen.value
  workspaceMode.value = resultPanelOpen.value ? 'result' : 'conversation'
}

async function handleOpenDetails() {
  if (!resultContextAvailable.value) return
  resultPanelOpen.value = true
  workspaceMode.value = 'result'
  await nextTick()
  queryResultRef.value?.openDetails()
}

function handleViewResult(taskId: string) {
  resultPanelOpen.value = true
  workspaceMode.value = 'result'
  exportUtil.tablePage.value = 1
  void submit.selectResultForTask(taskId)
  mobileHistoryOpen.value = false
}

function handleReloadSelectedResult(taskId: string) {
  void submit.selectResultForTask(taskId)
}

function handleResultClose() {
  resultPanelOpen.value = false
  workspaceMode.value = 'conversation'
}

function handleSelectSession(sessionId: string) {
  submit.followLatestResult()
  submit.question.value = ''
  resultPanelOpen.value = false
  workspaceMode.value = 'conversation'
  mobileHistoryOpen.value = false
  void session.selectSession(sessionId, { focusQuestionInput: () => queryInputRef.value?.focusQuestionInput() })
}

function handleRetryFromResult(question: string) {
  if (!question) return
  submit.question.value = question
  void submit.sendQuestion()
}

function handleClarify(question: string) {
  submit.prepareClarification(question)
  workspaceMode.value = 'conversation'
}

async function initializeDatasource() {
  await session.fetchDatasources({ autoSelect: false })
  datasourceInitialized.value = true
  await syncDatasourceFromUrl()
  queryInputRef.value?.focusQuestionInput()
}

watch(() => route.query.datasourceId, () => {
  if (datasourceInitialized.value) void syncDatasourceFromUrl()
})

onMounted(() => {
  void iamS1.load()
  withContext(() => reveal('.query-brand, .new-session-button, .datasource-picker, .history-section, .sidebar-user, .query-topbar, .chat-composer', { y: 14, stagger: 0.04 }))
  void initializeDatasource()
})
</script>

<template>
  <main
    ref="workspaceRef"
    class="query-workspace post-login-page"
    :class="{ 'result-open': showResultPanel, 'result-mode': workspaceMode === 'result', 'mobile-history-visible': mobileHistoryOpen }"
    :data-workspace-mode="workspaceMode"
  >
    <QuerySidebar
      :datasource-sessions="session.datasourceSessions.value"
      :active-session-id="session.activeSessionId.value"
      :keyword="session.keyword.value"
      :display-name="displayName"
      :role-text="roleText"
      :can-enter-admin="canEnterAdmin"
      :mobile-history-open="mobileHistoryOpen"
      @select-session="handleSelectSession"
      @remove-session="session.removeSession"
      @new-session="handleStartNewSession"
      @toggle-mobile-history="mobileHistoryOpen = !mobileHistoryOpen"
      @user-command="handleUserCommand"
      @update:keyword="session.keyword.value = $event"
    />

    <section class="query-main">
      <header class="query-topbar do-glass-panel">
        <div class="topbar-heading">
          <h1>智能问数</h1>
          <QueryDatasourceSelector
            :datasources="session.datasources.value"
            :readiness-map="session.readinessMap.value"
            :selected-id="session.selectedId.value"
            :loading="session.loading.value"
            :readiness-loading="session.readinessLoading.value"
            :error-message="session.errorMessage.value"
            @select="handleSelectDatasource"
            @refresh="refreshDatasources"
          />
          <small v-if="selectedReadiness?.ragStale" class="readiness-note" role="status">知识版本落后，按当前快照与权限校验</small>
          <small v-if="iamS1.errorMessage" class="capability-error" role="alert">
            {{ iamS1.errorMessage }}
            <button type="button" @click="iamS1.load(true)">重试能力检查</button>
          </small>
        </div>
        <div class="topbar-actions">
          <div class="workspace-switch" role="tablist" aria-label="工作区视图">
            <button type="button" role="tab" :aria-selected="workspaceMode === 'conversation'" :class="{ active: workspaceMode === 'conversation' }" @click="workspaceMode = 'conversation'"><UserRound :size="14" aria-hidden="true" />对话</button>
            <button
              type="button"
              role="tab"
              :aria-selected="workspaceMode === 'result'"
              :class="{ active: workspaceMode === 'result' }"
              :disabled="!resultContextAvailable"
              :title="resultContextAvailable ? undefined : '发起一次提问后可查看结果'"
              @click="resultPanelOpen = true; workspaceMode = 'result'"
            ><PanelRightOpen :size="14" aria-hidden="true" />结果</button>
          </div>
          <button
            type="button"
            class="result-toggle"
            :disabled="!resultContextAvailable"
            :aria-expanded="showResultPanel"
            :title="resultContextAvailable ? (showResultPanel ? '收起结果面板' : '展开结果面板') : '发起一次提问后可查看结果'"
            @click="toggleResultPanel"
          >
            <PanelRightOpen :size="15" aria-hidden="true" /><span>{{ showResultPanel ? '收起结果' : '查看结果' }}</span>
          </button>
          <button
            type="button"
            class="details-toggle"
            :disabled="!resultContextAvailable"
            :title="resultContextAvailable ? '打开结果详细信息' : '发起一次提问后可查看详细信息'"
            @click="handleOpenDetails"
          ><Info :size="14" aria-hidden="true" />详细信息</button>
        </div>
      </header>

      <div class="workspace-content">
        <section class="conversation-pane do-glass-panel" aria-label="当前对话">
          <header class="conversation-heading">
            <h2 :title="conversationTitle">{{ conversationTitle }}</h2>
            <span v-if="session.activeMessages.value.length" class="conversation-count">{{ session.activeMessages.value.length }} 条消息</span>
          </header>
          <div class="chat-surface">
            <div v-if="!session.selectedId.value" class="empty-chat">
              <h3>选择数据源开始提问</h3>
              <p>选择有问数权限的数据源后，会显示对应的历史会话。</p>
              <button type="button" :disabled="session.loading.value" @click="refreshDatasources"><RefreshCw :size="15" aria-hidden="true" />刷新数据源</button>
            </div>
            <div v-else-if="!session.activeMessages.value.length" class="welcome-state">
              <span class="welcome-logo" aria-hidden="true">DO</span>
              <strong>DataOcean</strong>
              <p>可先说明要了解的指标，再补充时间范围或分组方式。</p>
            </div>

            <section v-else class="conversation-stream" aria-label="对话消息">
              <button
                v-if="session.activeSession.value?.hasMoreHistory"
                class="load-older-messages"
                type="button"
                :disabled="session.activeSession.value.historyLoading"
                @click="session.loadOlderMessages(session.activeSession.value)"
              >
                {{ session.activeSession.value.historyLoading ? '正在加载…' : '加载更早消息' }}
              </button>
              <article v-for="message in session.activeMessages.value" :key="message.id" class="message-item" :class="[message.role, { clarification: isClarification(message) }]" :data-message-id="message.id">
                <span class="message-avatar" aria-hidden="true"><UserRound v-if="message.role === 'user'" :size="14" /><span v-else class="assistant-mark">DO</span></span>
                <div class="message-bubble">
                  <div v-if="isClarification(message)" class="message-state-title" role="status">
                    <CircleHelp :size="14" aria-hidden="true" /><span>需要补充信息</span>
                  </div>
                  <p>{{ message.content }}</p>
                  <div v-if="message.role === 'assistant' && message.queryResult?.suggestedQuestions?.length" class="message-suggestions" aria-label="推荐追问">
                    <small>推荐追问</small>
                    <button v-for="suggestion in message.queryResult.suggestedQuestions" :key="suggestion" type="button" @click="applySuggestion(suggestion)">{{ suggestion }}</button>
                  </div>
                  <div class="message-footer">
                    <div v-if="message.role === 'assistant' && hasMessageActions(message)" class="message-actions">
                      <button v-if="message.status === 'CLARIFICATION_REQUIRED'" type="button" class="message-primary-action" :disabled="submit.isQuerying.value" @click="submit.prepareClarification(message.originalQuestion || message.queryResult?.question || '')">补充问题</button>
                      <button v-if="message.status === 'TIMEOUT'" type="button" :disabled="submit.isQuerying.value" @click="submit.retryQuery(message.originalQuestion || message.queryResult?.question || '')">重新提问</button>
                      <template v-if="submit.isLocallyResumable(message)">
                        <button type="button" :disabled="submit.isQuerying.value || !message.originalQuestion" @click="submit.retryQuery(message.originalQuestion || '')">重新提问</button>
                        <button type="button" :disabled="submit.isQuerying.value || !message.taskId" @click="submit.continueWaiting(message.taskId || '')">恢复等待</button>
                      </template>
                      <button v-if="['FAILED', 'CANCELLED', 'error'].includes(message.status || '')" type="button" :disabled="submit.isQuerying.value" @click="submit.retryQuery(message.originalQuestion || message.queryResult?.question || '')">保留原问题重试</button>
                    </div>
                    <div class="message-meta">
                      <span class="message-meta-note">
                        <small class="message-time">{{ formatMessageTime(message.createdAt) }}</small>
                        <span v-if="messageState(message)" class="message-state-chip" :class="messageState(message)?.tone"><i aria-hidden="true"></i>{{ messageState(message)?.label }}</span>
                      </span>
                      <button v-if="canOpenMessageResult(message)" type="button" class="message-result-link" @click="handleViewResult(message.taskId || '')"><PanelRightOpen :size="13" aria-hidden="true" />查看结果</button>
                    </div>
                  </div>
                </div>
              </article>
            </section>
          </div>
        </section>

        <QueryInput
          ref="queryInputRef"
          :question="submit.question.value"
          :is-querying="submit.isQuerying.value"
          :selected-id="session.selectedId.value"
          :selected-datasource-name="session.selectedDatasource.value?.name"
          :can-ask="session.canAskSelectedDatasource.value"
          :readiness-loading="session.readinessLoading.value"
          :selected-block-reason="session.selectedBlockReason.value"
          @update:question="submit.question.value = $event"
          @send="handleSendQuestion"
          @cancel="submit.cancelCurrentQuery"
        />

        <QueryResult
          v-if="showResultPanel"
          ref="queryResultRef"
          :latest-result="displayResult"
          :display-task-id="submit.displayTaskId.value"
          :view-state="submit.displayState.value"
          :result-tab="submit.resultTab.value"
          :chart-type="submit.chartType.value"
          :chart-option="chartOption"
          :paged-table-data="exportUtil.pagedTableData.value"
          :table-page="exportUtil.tablePage.value"
          :table-page-size="exportUtil.tablePageSize"
          :agent-progress="submit.agentProgress.value"
          :trust-summary="submit.trustSummary.value"
          :datasource-facts="datasourceFacts"
          :can-view-sql="canViewSql"
          :can-export="canExport"
          :sql-loading="submit.sqlLoading.value"
          :sql-error-message="sqlErrorMessage"
          :original-question="displayOriginalQuestion"
          :context-label="earlierResultLabel"
          @close="handleResultClose"
          @update:result-tab="handleResultTab"
          @switch-chart-type="submit.chartType.value = $event"
          @export-csv="exportUtil.exportCsv"
          @feedback="exportUtil.handleFeedback"
          @update:table-page="exportUtil.tablePage.value = $event"
          @retry="handleRetryFromResult"
          @clarify="handleClarify"
          @resume="submit.continueWaiting"
          @reload-result="handleReloadSelectedResult"
          @retry-sql="handleRetrySql"
        />
      </div>
    </section>
  </main>
</template>

<style scoped>
/* Keep desktop split and compact single-view breakpoints in sync with the workspace state. */
.query-workspace { width: 100%; height: 100dvh; min-height: 0; display: grid; grid-template-columns: 248px minmax(0, 1fr); grid-template-rows: minmax(0, 1fr); align-items: stretch; gap: 10px; padding: 10px; overflow: hidden; color: var(--do-ink); background: transparent; }
.query-main { position: relative; z-index: 0; min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr); gap: 0; padding: 0; background: transparent; }
.query-topbar { min-width: 0; min-height: 56px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 14px; border-color: var(--do-glass-border); border-radius: 14px; }
.query-topbar.do-glass-panel { background: rgba(255, 255, 255, .76); }
.topbar-heading { min-width: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.topbar-heading h1 { flex: 0 0 auto; margin: 0; color: var(--do-ink); font-size: 18px; font-weight: 750; letter-spacing: -.01em; }
.readiness-note { max-width: 320px; color: color-mix(in srgb, var(--do-warning) 65%, var(--do-ink)); font-size: 10.5px; font-weight: 600; line-height: 1.4; }
.capability-error { flex: 1 1 100%; color: var(--do-danger); font-size: 12px; line-height: 1.45; }
.capability-error button { min-height: 32px; margin-left: 5px; padding: 0 8px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; cursor: pointer; }
.topbar-actions { flex: 0 0 auto; display: flex; align-items: center; justify-content: flex-end; gap: 6px; }
.result-toggle, .details-toggle { min-height: 36px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 11px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-primary-strong); background: rgba(255, 255, 255, .9); font: inherit; font-size: 12px; font-weight: 650; cursor: pointer; }
.result-toggle:hover:not(:disabled), .details-toggle:hover:not(:disabled) { border-color: var(--do-primary); background: var(--do-primary-soft); }
.result-toggle:disabled, .details-toggle:disabled { color: var(--do-muted); cursor: not-allowed; opacity: .55; }
.workspace-switch { display: none; align-items: center; gap: 2px; padding: 3px; border: 1px solid var(--do-line); border-radius: 9px; background: rgba(255, 255, 255, .8); }
.workspace-switch button { min-height: 30px; display: inline-flex; align-items: center; justify-content: center; gap: 5px; padding: 0 10px; border: 0; border-radius: 6px; color: var(--do-muted); background: transparent; font: inherit; font-size: 11.5px; font-weight: 650; cursor: pointer; }
.workspace-switch button.active { color: var(--do-primary-strong); background: var(--do-surface); box-shadow: 0 1px 4px rgba(34, 72, 126, .1); }
.workspace-switch button:disabled { color: var(--do-muted); cursor: not-allowed; opacity: .55; }
.workspace-content { min-width: 0; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) auto; column-gap: 10px; padding-top: 10px; }
.query-workspace.result-open .workspace-content { grid-template-columns: minmax(440px, 1fr) minmax(420px, 1fr); }
.conversation-pane { --do-glass-corner: 16px 16px 0 0; min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr); grid-column: 1; grid-row: 1; border: 1px solid var(--do-glass-line); border-bottom: 0; border-radius: 16px 16px 0 0; }
.conversation-pane.do-glass-panel { background: rgba(255, 255, 255, .95); }
.conversation-heading { min-height: 42px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 28px; border-bottom: 1px solid var(--do-line); }
.conversation-heading h2 { flex: 1 1 auto; min-width: 0; margin: 0; overflow: hidden; color: var(--do-muted); font-size: 12.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.conversation-count { flex: 0 0 auto; color: var(--do-muted); font-size: 10.5px; font-variant-numeric: tabular-nums; opacity: .85; }
.chat-surface { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 18px 28px 24px; scrollbar-width: thin; scrollbar-color: color-mix(in srgb, var(--do-primary) 20%, transparent) transparent; }
.chat-surface::-webkit-scrollbar { width: 6px; }
.chat-surface::-webkit-scrollbar-track { background: transparent; }
.chat-surface::-webkit-scrollbar-thumb { border-radius: var(--do-radius-full); background: color-mix(in srgb, var(--do-primary) 18%, transparent); }
.welcome-state { min-height: 100%; display: grid; place-items: center; align-content: center; gap: 8px; padding: 20px; text-align: center; }
.welcome-logo { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 11px; color: #fff; background: var(--do-primary); font-size: 12px; font-weight: 900; }
.welcome-state strong { color: var(--do-ink); font-size: 20px; }
.welcome-state p { max-width: 380px; margin: 4px 0 0; color: var(--do-muted); font-size: 13.5px; line-height: 1.65; }
.empty-chat { min-height: 100%; display: grid; place-items: center; align-content: center; gap: 10px; color: var(--do-muted); text-align: center; }
.empty-chat h3 { margin: 0; color: var(--do-ink); font-size: 17px; }
.empty-chat p { margin: 0; font-size: 13px; line-height: 1.6; }
.empty-chat button { min-height: 38px; display: inline-flex; align-items: center; gap: 6px; margin-top: 6px; padding: 0 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.empty-chat button:disabled { cursor: wait; opacity: .55; }
.conversation-stream { width: min(780px, 100%); display: grid; align-content: start; gap: 22px; margin: 0 auto; padding: 8px 0 12px; }
.load-older-messages { min-height: 32px; justify-self: center; padding: 0 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 11px; cursor: pointer; }
.load-older-messages:disabled { cursor: wait; opacity: .55; }
.message-item { min-width: 0; display: grid; grid-template-columns: 28px minmax(0, 1fr); align-items: start; gap: 10px; }
.message-item.user { grid-template-columns: minmax(0, 1fr) 28px; }
.message-item.user .message-avatar { grid-column: 2; }
.message-item.user .message-bubble { grid-column: 1; grid-row: 1; justify-self: end; max-width: 76%; padding: 10px 13px; border-color: color-mix(in srgb, var(--do-primary) 10%, transparent); background: var(--do-primary-soft); box-shadow: none; }
.message-avatar { width: 28px; height: 28px; display: grid; place-items: center; border-radius: 50%; color: var(--do-primary-strong); background: var(--do-primary-soft); }
.message-item.user .message-avatar { color: #fff; background: var(--do-primary); }
.assistant-mark { font-size: 8.5px; font-weight: 900; letter-spacing: .02em; }
.message-bubble { min-width: 0; max-width: 100%; padding: 12px 14px 10px; border: 1px solid var(--do-line); border-radius: 14px; color: var(--do-ink); background: var(--do-surface); box-shadow: none; }
.message-item.assistant .message-bubble { width: 100%; max-width: 620px; justify-self: start; }
.message-bubble p { margin: 0; font-size: 15px; line-height: 1.65; letter-spacing: .004em; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-state-title { display: inline-flex; align-items: center; gap: 5px; margin-bottom: 7px; padding: 3px 8px; border-radius: var(--do-radius-full); color: color-mix(in srgb, var(--do-warning) 60%, var(--do-ink)); background: var(--do-warning-soft); font-size: 11px; font-weight: 700; line-height: 1.4; }
.message-suggestions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.message-suggestions small { flex-basis: 100%; color: var(--do-muted); font-size: 10.5px; }
.message-suggestions button { min-height: 30px; padding: 0 10px; border: 1px solid var(--do-line); border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 11.5px; cursor: pointer; }
.message-suggestions button:hover { border-color: var(--do-primary); background: var(--do-primary-soft); }
.message-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 6px 10px; margin-top: 8px; }
.message-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 0; padding-top: 0; border-top: 0; }
.message-actions button { min-height: 32px; padding: 0 11px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 11.5px; font-weight: 650; cursor: pointer; }
.message-actions button:hover:not(:disabled) { border-color: var(--do-primary); background: var(--do-primary-soft); }
.message-actions button:disabled { cursor: not-allowed; opacity: .5; }
.message-actions .message-primary-action { border-color: var(--do-primary); color: #fff; background: var(--do-primary); }
.message-actions .message-primary-action:hover:not(:disabled) { border-color: var(--do-primary-strong); background: var(--do-primary-strong); }
.message-meta { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 0; margin-left: auto; padding: 0; border: 0; color: var(--do-muted); }
.message-meta-note { min-width: 0; display: inline-flex; align-items: center; gap: 8px; }
.message-time { color: var(--do-muted); font-size: 10px; opacity: .82; font-variant-numeric: tabular-nums; }
.message-state-chip { display: inline-flex; align-items: center; gap: 4px; color: var(--do-muted); font-size: 10.5px; font-weight: 600; }
.message-state-chip i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.message-state-chip.processing { color: var(--do-primary-strong); }
.message-state-chip.processing i { animation: state-pulse 1.4s ease-in-out infinite; }
.message-state-chip.success { color: color-mix(in srgb, var(--do-success) 65%, var(--do-ink)); }
.message-state-chip.danger { color: var(--do-danger); }
.message-state-chip.muted { color: var(--do-muted); }
.message-result-link { min-height: 26px; display: inline-flex; align-items: center; gap: 4px; padding: 0 7px; border: 1px solid transparent; border-radius: 6px; color: var(--do-primary-strong); background: transparent; font: inherit; font-size: 10.5px; font-weight: 650; cursor: pointer; }
.message-result-link:hover { border-color: var(--do-line); background: var(--do-primary-soft); }
.query-workspace :deep(.query-input) { grid-column: 1; grid-row: 2; align-self: end; width: auto; min-width: 0; margin: 0; padding: 8px 28px 10px; border: 1px solid var(--do-glass-line); border-top: 0; border-radius: 0 0 16px 16px; background: rgba(255, 255, 255, .95); }
.query-workspace :deep(.composer-readiness) { color: color-mix(in srgb, var(--do-warning) 65%, var(--do-ink)); }
.query-workspace.result-open .result-rail { grid-column: 2; grid-row: 1 / 3; }
.query-workspace :deep(.result-rail) { grid-column: 1; grid-row: 1 / 2; border-radius: 16px; box-shadow: var(--do-shadow); }
.query-workspace :deep(.result-rail .result-actions) { margin: 0; }
.query-workspace :deep(.result-rail .result-scroll) { scrollbar-gutter: stable; }
.result-toggle:focus-visible, .details-toggle:focus-visible, .workspace-switch button:focus-visible, .message-result-link:focus-visible, .message-actions button:focus-visible, .message-suggestions button:focus-visible, .empty-chat button:focus-visible, .load-older-messages:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@keyframes state-pulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: .35; transform: scale(.8); } }
@media (max-width: 1279px) and (min-width: 769px) {
  .query-workspace { grid-template-columns: 240px minmax(0, 1fr); }
  .query-workspace.result-open .workspace-content { grid-template-columns: minmax(0, 1fr); }
  .workspace-switch { display: flex; }
  .result-toggle { display: none; }
  .query-workspace:not(.result-mode) :deep(.result-rail) { display: none; }
  .query-workspace.result-mode .conversation-pane { display: none; }
  .query-workspace.result-mode :deep(.result-rail) { display: grid; grid-column: 1; grid-row: 1; }
  .query-workspace :deep(.query-input) { padding-inline: 20px; }
  .query-topbar { padding-inline: 12px; }
  .topbar-heading { gap: 8px 10px; flex-wrap: nowrap; }
  .topbar-heading h1 { font-size: 17px; }
  .readiness-note { display: none; }
  .conversation-heading { padding-inline: 20px; }
  .chat-surface { padding-inline: 20px; }
}
@media (max-width: 899px) and (min-width: 769px) {
  /* 中等宽度下把操作区换到第二行，避免数据源选择器与视图切换互相覆盖。 */
  .query-topbar { flex-wrap: wrap; row-gap: 6px; }
  .topbar-heading { flex: 1 1 100%; }
  .topbar-actions { width: 100%; justify-content: flex-end; }
}
@media (max-width: 768px) {
  .query-workspace { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); gap: 8px; padding: 8px; }
  .query-main { min-height: 0; grid-template-rows: auto minmax(0, 1fr); padding: 0; }
  .query-topbar { min-height: 0; flex-wrap: wrap; align-items: flex-start; gap: 8px; padding: 9px 11px; }
  .topbar-heading { width: 100%; display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 8px 10px; }
  .topbar-heading h1 { font-size: 16px; }
  .readiness-note { grid-column: 1 / -1; display: block; max-width: none; }
  .capability-error { grid-column: 1 / -1; }
  .topbar-actions { width: 100%; justify-content: space-between; }
  .workspace-switch { display: flex; flex: 1; }
  .result-toggle { display: none; }
  .workspace-switch button { flex: 1; min-height: 36px; }
  .result-toggle, .details-toggle { min-height: 38px; }
  .workspace-content, .query-workspace.result-open .workspace-content { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) auto; }
  .query-workspace:not(.result-mode) :deep(.result-rail) { display: none; }
  .query-workspace.result-mode .conversation-pane { display: none; }
  .query-workspace.result-mode :deep(.result-rail) { display: grid; grid-column: 1; grid-row: 1; }
  .conversation-heading { min-height: 38px; padding-inline: 14px; }
  .conversation-heading h2 { font-size: 11.5px; }
  .chat-surface { padding: 12px 12px 16px; }
  .welcome-logo { width: 38px; height: 38px; }
  .welcome-state strong { font-size: 18px; }
  .welcome-state p { font-size: 13px; }
  .conversation-stream { gap: 16px; padding: 4px 0 8px; }
  .message-item { grid-template-columns: 26px minmax(0, 1fr); gap: 8px; }
  .message-item.user { grid-template-columns: minmax(0, 1fr) 26px; }
  .message-avatar { width: 26px; height: 26px; }
  .message-bubble { padding: 10px 12px 9px; }
  .message-item.user .message-bubble { max-width: 90%; }
  .query-workspace :deep(.query-input) { padding: 7px 12px max(8px, env(safe-area-inset-bottom)); }
  .query-workspace :deep(.result-rail) { border-radius: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  .message-state-chip.processing i { animation: none; }
  .message-meta button, .message-actions button, .message-suggestions button, .workspace-switch button { transition-duration: 1ms; }
}
</style>
