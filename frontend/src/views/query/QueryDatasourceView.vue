<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Info, PanelRightOpen, RefreshCw, UserRound } from 'lucide-vue-next'
import { useGsapMotion } from '../../composables/useGsapMotion'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { useQuerySession } from '../../composables/useQuerySession'
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
watch(() => submit.latestResult.value?.taskId, (taskId) => {
  if (!taskId) return
  resultPanelOpen.value = true
  exportUtil.tablePage.value = 1
})
watch(submit.resultTab, () => {
  nextTick(() => {
    const resultBody = workspaceRef.value?.querySelector('.result-scroll')
    if (resultBody) lift(resultBody, { y: 5, duration: 0.2, scale: 1 })
  })
})

function handleUserCommand(command: string) {
  if (command === 'admin') router.push('/admin')
  if (command === 'profile') router.push('/profile')
  if (command === 'password') router.push('/change-password')
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
  }
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
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
      <header class="query-topbar">
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
          <small v-if="selectedReadiness?.ragStale" class="readiness-note" role="status">知识版本落后，查询仍按当前快照与权限校验</small>
          <small v-if="iamS1.errorMessage" class="capability-error" role="alert">
            {{ iamS1.errorMessage }}
            <button type="button" @click="iamS1.load(true)">重试能力检查</button>
          </small>
        </div>
        <div class="topbar-actions">
          <button v-if="resultContextAvailable && !showResultPanel" type="button" class="result-toggle" @click="resultPanelOpen = true; workspaceMode = 'result'">
            <PanelRightOpen :size="16" aria-hidden="true" /><span>查看结果</span>
          </button>
          <div v-if="resultContextAvailable" class="workspace-switch" role="tablist" aria-label="工作区视图">
            <button type="button" role="tab" :aria-selected="workspaceMode === 'conversation'" :class="{ active: workspaceMode === 'conversation' }" @click="workspaceMode = 'conversation'"><UserRound :size="15" aria-hidden="true" />对话</button>
            <button type="button" role="tab" :aria-selected="workspaceMode === 'result'" :class="{ active: workspaceMode === 'result' }" @click="resultPanelOpen = true; workspaceMode = 'result'"><PanelRightOpen :size="15" aria-hidden="true" />结果</button>
          </div>
          <button v-if="showResultPanel" type="button" class="details-toggle" @click="handleOpenDetails"><Info :size="15" aria-hidden="true" />详细信息</button>
        </div>
      </header>

      <div class="workspace-content">
        <section class="conversation-pane" aria-label="当前对话">
          <header class="conversation-heading"><h2>当前对话</h2></header>
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
              <article v-for="message in session.activeMessages.value" :key="message.id" class="message-item" :class="message.role" :data-message-id="message.id">
                <span class="message-avatar" aria-hidden="true"><UserRound v-if="message.role === 'user'" :size="16" /><span v-else class="assistant-mark">DO</span></span>
                <div class="message-bubble">
                  <p>{{ message.content }}</p>
                  <div v-if="message.role === 'assistant' && message.queryResult?.suggestedQuestions?.length" class="message-suggestions" aria-label="推荐追问">
                    <small>推荐追问</small>
                    <button v-for="suggestion in message.queryResult.suggestedQuestions" :key="suggestion" type="button" @click="applySuggestion(suggestion)">{{ suggestion }}</button>
                  </div>
                  <div class="message-meta">
                    <small>{{ formatTime(message.createdAt) }}</small>
                    <button v-if="message.role === 'assistant' && message.taskId" type="button" @click="handleViewResult(message.taskId)"><PanelRightOpen :size="14" aria-hidden="true" />查看结果</button>
                  </div>
                  <div v-if="message.role === 'assistant' && message.status === 'TIMEOUT'" class="message-actions">
                    <button type="button" :disabled="submit.isQuerying.value" @click="submit.retryQuery(message.originalQuestion || message.queryResult?.question || '')">重新提问</button>
                  </div>
                  <div v-if="message.role === 'assistant' && submit.isLocallyResumable(message)" class="message-actions">
                    <button type="button" :disabled="submit.isQuerying.value || !message.originalQuestion" @click="submit.retryQuery(message.originalQuestion || '')">重新提问</button>
                    <button type="button" :disabled="submit.isQuerying.value || !message.taskId" @click="submit.continueWaiting(message.taskId || '')">恢复等待</button>
                  </div>
                  <div v-if="message.role === 'assistant' && message.status === 'CLARIFICATION_REQUIRED'" class="message-actions">
                    <button type="button" :disabled="submit.isQuerying.value" @click="submit.prepareClarification(message.originalQuestion || message.queryResult?.question || '')">补充查询条件</button>
                  </div>
                  <div v-if="message.role === 'assistant' && ['FAILED', 'CANCELLED', 'error'].includes(message.status || '')" class="message-actions">
                    <button type="button" :disabled="submit.isQuerying.value" @click="submit.retryQuery(message.originalQuestion || message.queryResult?.question || '')">保留原问题重试</button>
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
.query-workspace { width: 100%; height: 100dvh; min-height: 0; display: grid; grid-template-columns: 240px minmax(0, 1fr); overflow: hidden; color: var(--do-ink); background: var(--do-bg); }
.query-main { min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr); background: var(--do-surface); }
.query-topbar { min-width: 0; min-height: 76px; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 18px; border-bottom: 1px solid var(--do-line); background: var(--do-surface); }
.topbar-heading { min-width: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.topbar-heading h1 { flex: 0 0 auto; margin: 0; color: var(--do-ink); font-size: 20px; font-weight: 780; }
.readiness-note { max-width: 300px; color: var(--do-warning); font-size: 11px; line-height: 1.4; }
.capability-error { flex: 1 1 100%; color: var(--do-danger); font-size: 12px; line-height: 1.45; }
.capability-error button { min-height: 34px; margin-left: 5px; padding: 0 8px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; cursor: pointer; }
.topbar-actions { flex: 0 0 auto; display: flex; align-items: center; gap: 8px; }
.result-toggle, .details-toggle { min-height: 42px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding: 0 12px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }
.result-toggle:hover, .details-toggle:hover { border-color: var(--do-primary); background: var(--do-primary-soft); }
.workspace-switch { display: none; align-items: center; gap: 3px; padding: 3px; border: 1px solid var(--do-line); border-radius: var(--do-radius-md); background: var(--do-bg); }
.workspace-switch button { min-height: 36px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 10px; border: 0; border-radius: 6px; color: var(--do-muted); background: transparent; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }
.workspace-switch button.active { color: var(--do-primary-strong); background: var(--do-surface); box-shadow: var(--do-shadow); }
.workspace-content { min-width: 0; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) auto; }
.query-workspace.result-open .workspace-content { grid-template-columns: minmax(460px, 1.1fr) minmax(400px, .9fr); }
.conversation-pane { min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr); grid-column: 1; grid-row: 1; }
.conversation-heading { min-height: 60px; display: flex; align-items: center; padding: 0 24px; border-bottom: 1px solid var(--do-line); }
.conversation-heading h2 { max-width: 100%; margin: 0; overflow: hidden; color: var(--do-ink); font-size: 17px; font-weight: 740; text-overflow: ellipsis; white-space: nowrap; }
.chat-surface { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 20px 24px 24px; }
.welcome-state { min-height: 100%; display: grid; place-items: center; align-content: center; gap: 8px; padding: 20px; text-align: center; }
.welcome-logo { width: 44px; height: 44px; display: grid; place-items: center; border-radius: 12px; color: #fff; background: var(--do-primary); font-size: 13px; font-weight: 900; }
.welcome-state strong { color: var(--do-ink); font-size: 22px; }
.welcome-state p { max-width: 380px; margin: 5px 0 0; color: var(--do-muted); font-size: 14px; line-height: 1.6; }
.empty-chat { min-height: 100%; display: grid; place-items: center; align-content: center; gap: 10px; color: var(--do-muted); text-align: center; }
.empty-chat h3 { margin: 0; color: var(--do-ink); font-size: 18px; }
.empty-chat p { margin: 0; font-size: 13px; line-height: 1.6; }
.empty-chat button { min-height: 42px; display: inline-flex; align-items: center; gap: 7px; margin-top: 7px; padding: 0 13px; border: 1px solid var(--do-line); border-radius: var(--do-radius-sm); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.empty-chat button:disabled { cursor: wait; opacity: .55; }
.conversation-stream { width: min(860px, 100%); display: grid; align-content: start; gap: 24px; margin: 0 auto; padding: 10px 0 30px; }
.load-older-messages { min-height: 40px; justify-self: center; padding: 0 13px; border: 1px solid var(--do-line); border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.load-older-messages:disabled { cursor: wait; opacity: .55; }
.message-item { min-width: 0; display: grid; grid-template-columns: 36px minmax(0, 1fr); gap: 11px; }
.message-item.user { grid-template-columns: minmax(0, 1fr) 36px; }
.message-item.user .message-avatar { grid-column: 2; }
.message-item.user .message-bubble { grid-column: 1; grid-row: 1; justify-self: end; max-width: 80%; border-color: transparent; background: var(--do-primary-soft); box-shadow: none; }
.message-avatar { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%; color: var(--do-primary-strong); background: var(--do-primary-soft); }
.message-item.user .message-avatar { color: #fff; background: var(--do-primary); }
.assistant-mark { font-size: 10px; font-weight: 900; }
.message-bubble { min-width: 0; max-width: 720px; padding: 13px 15px; border: 1px solid var(--do-line); border-radius: 13px; color: var(--do-ink); background: var(--do-surface); box-shadow: var(--do-shadow); }
.message-bubble p { margin: 0; font-size: 14px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-suggestions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 12px; }
.message-suggestions small { flex-basis: 100%; color: var(--do-muted); font-size: 11px; }
.message-suggestions button { min-height: 36px; padding: 0 11px; border: 1px solid var(--do-line); border-radius: var(--do-radius-full); color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 12px; cursor: pointer; }
.message-suggestions button:hover { border-color: var(--do-primary); background: var(--do-primary-soft); }
.message-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 8px; color: var(--do-muted); }
.message-meta small { font-size: 11px; }
.message-meta button { min-height: 32px; display: inline-flex; align-items: center; gap: 5px; padding: 0 8px; border: 1px solid transparent; border-radius: 6px; color: var(--do-primary-strong); background: transparent; font: inherit; font-size: 11px; font-weight: 700; cursor: pointer; }
.message-meta button:hover { border-color: var(--do-line); background: var(--do-primary-soft); }
.message-actions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 10px; padding-top: 9px; border-top: 1px solid var(--do-line); }
.message-actions button { min-height: 36px; padding: 0 10px; border: 1px solid var(--do-line); border-radius: 7px; color: var(--do-primary-strong); background: var(--do-surface); font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.message-actions button:hover:not(:disabled) { border-color: var(--do-primary); background: var(--do-primary-soft); }
.message-actions button:disabled { cursor: not-allowed; opacity: .5; }
.query-workspace :deep(.query-input) { width: auto; min-width: 0; margin: 0; padding: 12px 20px 14px; border-top: 1px solid var(--do-line); background: var(--do-surface); }
.query-workspace :deep(.chat-composer) { padding: 9px 9px 9px 14px; box-shadow: var(--do-shadow); }
.query-workspace :deep(.send-button), .query-workspace :deep(.cancel-button) { min-width: 88px; min-height: 44px; }
.query-workspace :deep(.composer-hint) { margin-top: 5px; font-size: 11px; }
.query-workspace :deep(.composer-readiness) { color: var(--do-warning); }
.query-workspace.result-open .result-rail { grid-column: 2; grid-row: 1 / 3; }
.query-workspace :deep(.result-rail) { grid-column: 1; grid-row: 1 / 2; }
.query-workspace :deep(.result-rail) { box-shadow: none; }
.query-workspace :deep(.result-rail .result-actions) { margin: 0; }
.query-workspace :deep(.result-rail .result-scroll) { scrollbar-gutter: stable; }
.query-workspace.result-mode .conversation-pane { display: block; }
.result-toggle:focus-visible, .details-toggle:focus-visible, .workspace-switch button:focus-visible, .message-meta button:focus-visible, .message-actions button:focus-visible, .message-suggestions button:focus-visible, .empty-chat button:focus-visible, .load-older-messages:focus-visible { outline: 3px solid color-mix(in srgb, var(--do-primary) 30%, transparent); outline-offset: 2px; }
@media (max-width: 1279px) and (min-width: 769px) {
  .query-workspace { grid-template-columns: 220px minmax(0, 1fr); }
  .query-workspace.result-open .workspace-content { grid-template-columns: minmax(0, 1fr); }
  .workspace-switch { display: flex; }
  .query-workspace:not(.result-mode) :deep(.result-rail) { display: none; }
  .query-workspace.result-mode .conversation-pane { display: none; }
  .query-workspace.result-mode :deep(.result-rail) { display: grid; grid-column: 1; grid-row: 1; }
  .query-workspace :deep(.query-input) { padding-inline: 16px; }
  .query-topbar { padding-inline: 14px; }
  .topbar-heading { gap: 10px; }
  .topbar-heading h1 { font-size: 18px; }
  .topbar-heading { flex-wrap: nowrap; }
  .readiness-note { display: none; }
}
@media (max-width: 768px) {
  .query-workspace { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); }
  .query-main { min-height: 0; grid-template-rows: auto minmax(0, 1fr); }
  .query-topbar { min-height: 0; flex-wrap: wrap; align-items: flex-start; gap: 8px; padding: 10px 12px; }
  .topbar-heading { width: 100%; display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 8px 10px; }
  .topbar-heading h1 { font-size: 16px; }
  .readiness-note { grid-column: 1 / -1; display: block; max-width: none; }
  .capability-error { grid-column: 1 / -1; }
  .topbar-actions { width: 100%; justify-content: space-between; }
  .workspace-switch { display: flex; flex: 1; }
  .workspace-switch button { flex: 1; min-height: 42px; }
  .result-toggle, .details-toggle { min-height: 42px; }
  .workspace-content, .query-workspace.result-open .workspace-content { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) auto; }
  .query-workspace:not(.result-mode) :deep(.result-rail) { display: none; }
  .query-workspace.result-mode .conversation-pane { display: none; }
  .query-workspace.result-mode :deep(.result-rail) { display: grid; grid-column: 1; grid-row: 1; }
  .conversation-heading { min-height: 48px; padding: 0 14px; }
  .conversation-heading h2 { font-size: 15px; }
  .chat-surface { padding: 12px 12px 16px; }
  .welcome-logo { width: 38px; height: 38px; }
  .welcome-state strong { font-size: 19px; }
  .welcome-state p { font-size: 13px; }
  .conversation-stream { gap: 16px; padding: 4px 0 18px; }
  .message-item { grid-template-columns: 32px minmax(0, 1fr); gap: 8px; }
  .message-item.user { grid-template-columns: minmax(0, 1fr) 32px; }
  .message-avatar { width: 32px; height: 32px; }
  .message-bubble { padding: 10px 11px; }
  .message-item.user .message-bubble { max-width: 94%; }
  .query-workspace :deep(.query-input) { padding: 9px 12px max(10px, env(safe-area-inset-bottom)); }
  .query-workspace :deep(.chat-composer) { gap: 7px; padding: 7px; }
  .query-workspace :deep(.chat-composer textarea) { min-height: 42px; padding-inline: 5px; }
  .query-workspace :deep(.send-button), .query-workspace :deep(.cancel-button) { min-width: 78px; }
  .query-workspace :deep(.composer-hint) { text-align: left; }
  .query-workspace :deep(.result-rail) { border: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .message-meta button, .message-actions button, .message-suggestions button, .workspace-switch button { transition-duration: 1ms; }
}
</style>
