<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Database,
  History,
  MessageSquareText,
  PanelRightOpen,
  RefreshCw,
  UserRound,
} from 'lucide-vue-next'
import { useGsapMotion } from '../../composables/useGsapMotion'
import { useAuthStore } from '../../stores/auth'
import { useIamS1Store } from '../../stores/iamS1'
import { useQuerySession } from '../../composables/useQuerySession'
import { useQuerySubmit } from '../../composables/useQuerySubmit'
import { useQueryExport } from '../../composables/useQueryExport'
import { parseDatasourceId } from '../../utils/queryDatasource'
import QuerySidebar from './QuerySidebar.vue'
import QueryInput from './QueryInput.vue'
import QueryResult from './QueryResult.vue'
import IamS1ResourceSelector from './IamS1ResourceSelector.vue'
import type { IamS1TableDeclaration } from '../../api/iamS1'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const iamS1 = useIamS1Store()
const workspaceRef = ref<HTMLElement | null>(null)
const queryInputRef = ref<InstanceType<typeof QueryInput>>()
const resultPanelOpen = ref(false)
const datasourceInitialized = ref(false)
const resourceDeclarations = ref<IamS1TableDeclaration[]>([])
let datasourceSyncRequest = 0
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(workspaceRef)

const canEnterAdmin = computed(() => iamS1.hasAnyAdminCapability)
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => iamS1.systemAdmin ? 'S1 系统管理员' : 'S1 权限动态判定')

const exampleQuestions = [
  '统计最近30天订单金额趋势',
  '找出销售额最高的10个客户',
  '查看库存低于安全线的商品',
  '按部门汇总本月费用',
]

const session = useQuerySession()
const sessionTitle = computed(() => {
  const title = session.activeSession.value?.title
  return title && title !== '新的对话' ? title : '智能问答'
})
const submit = useQuerySubmit({
  selectedId: session.selectedId,
  activeSession: session.activeSession,
  activeMessages: session.activeMessages,
  canAskSelectedDatasource: session.canAskSelectedDatasource,
  selectedBlockReason: session.selectedBlockReason,
  resourceDeclarations,
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

const exportUtil = useQueryExport({ latestResult: submit.latestResult })
const chartOption = computed<Record<string, unknown> | null>(() => {
  if (!submit.latestResult.value?.chartConfig) return null
  try {
    const option = JSON.parse(JSON.stringify(submit.latestResult.value.chartConfig))
    if (option.series?.length) option.series[0].type = submit.chartType.value
    return option
  } catch {
    return null
  }
})

watch(submit.latestResult, (result) => {
  exportUtil.tablePage.value = 1
  if (result) resultPanelOpen.value = true
})

watch(submit.resultTab, () => {
  nextTick(() => {
    const resultBody = workspaceRef.value?.querySelector('.result-preview > div:not(.result-tabs)')
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

function applyExample(text: string) {
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
  submit.question.value = ''
  resultPanelOpen.value = false
  resourceDeclarations.value = []
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
  submit.question.value = ''
  resultPanelOpen.value = false
}

function handleResultTab(tab: 'table' | 'sql' | 'chart' | 'trust') {
  submit.resultTab.value = tab
  if (tab === 'sql') void submit.refreshSql()
}

function handleSelectSession(sessionId: string) {
  submit.question.value = ''
  resultPanelOpen.value = false
  session.selectSession(sessionId, { focusQuestionInput: () => queryInputRef.value?.focusQuestionInput() })
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
  withContext(() => reveal('.query-brand, .new-session-button, .datasource-section, .history-section, .sidebar-user, .query-topbar, .chat-composer', { y: 14, stagger: 0.04 }))
  void initializeDatasource()
})
</script>

<template>
  <main ref="workspaceRef" class="query-workspace post-login-page" :class="{ 'result-open': resultPanelOpen && submit.latestResult.value }">
    <QuerySidebar
      :datasources="session.datasources.value"
      :readiness-map="session.readinessMap.value"
      :selected-id="session.selectedId.value"
      :datasource-sessions="session.datasourceSessions.value"
      :active-session-id="session.activeSessionId.value"
      :keyword="session.keyword.value"
      :loading="session.loading.value"
      :readiness-loading="session.readinessLoading.value"
      :error-message="session.errorMessage.value"
      :display-name="displayName"
      :role-text="roleText"
      :can-enter-admin="canEnterAdmin"
      @select-datasource="handleSelectDatasource"
      @select-session="handleSelectSession"
      @remove-session="session.removeSession"
      @new-session="handleStartNewSession"
       @refresh="refreshDatasources"
      @user-command="handleUserCommand"
      @update:keyword="session.keyword.value = $event"
    />

    <section class="query-main">
      <header class="query-topbar">
        <div class="workspace-title"><h1>{{ sessionTitle }}</h1></div>
        <div class="topbar-actions">
          <button v-if="submit.latestResult.value && !resultPanelOpen" class="result-toggle" type="button" @click="resultPanelOpen = true">
            <PanelRightOpen :size="16" /><span>查看结果</span>
          </button>
        </div>
      </header>

      <section class="chat-surface">
        <div v-if="!session.selectedId.value" class="empty-chat">
          <div class="empty-chat-icon"><Database :size="28" /></div>
          <h2>选择数据源开始提问</h2>
          <p>数据源入口在左侧，选择后会加载对应的历史会话。</p>
          <button type="button" @click="session.fetchDatasources()"><RefreshCw :size="15" />刷新数据源</button>
        </div>

        <div v-else-if="!session.activeMessages.value.length" class="welcome-state">
          <span class="welcome-logo">DO</span>
          <strong>DataOcean</strong>
          <small>智能问答</small>
          <h2>我可以帮你查询什么？</h2>
        </div>

        <section v-else class="conversation-stream" aria-label="对话">
          <article v-for="message in session.activeMessages.value" :key="message.id" class="message-item" :class="message.role" :data-message-id="message.id">
            <span class="message-avatar"><UserRound v-if="message.role === 'user'" :size="16" /><MessageSquareText v-else :size="16" /></span>
            <div class="message-bubble">
              <p>{{ message.content }}</p>
              <div v-if="message.role === 'assistant' && message.queryResult?.suggestedQuestions?.length" class="message-suggestions" aria-label="推荐追问">
                <small>推荐追问</small>
                <button v-for="suggestion in message.queryResult.suggestedQuestions" :key="suggestion" type="button" @click="applyExample(suggestion)">
                  {{ suggestion }}
                </button>
              </div>
              <div class="message-meta">
                <small>{{ formatTime(message.createdAt) }}</small>
                <button v-if="message.role === 'assistant' && message.queryResult" type="button" @click="resultPanelOpen = true"><PanelRightOpen :size="14" />查看结果</button>
              </div>
              <div v-if="message.role === 'assistant' && message.status === 'TIMEOUT'" class="message-actions">
                <button @click="submit.retryQuery(message.originalQuestion || '')" :disabled="submit.isQuerying.value"><RefreshCw :size="14" />重试查询</button>
                <button @click="submit.continueWaiting(message.taskId || '')" :disabled="submit.isQuerying.value"><History :size="14" />继续等待</button>
              </div>
              <div v-if="message.role === 'assistant' && (message.status === 'FAILED' || message.status === 'error')" class="message-actions">
                <button @click="submit.retryQuery(message.originalQuestion || '')" :disabled="submit.isQuerying.value || !message.originalQuestion"><RefreshCw :size="14" />重新提问</button>
              </div>
            </div>
          </article>
        </section>
      </section>

      <IamS1ResourceSelector
        v-if="session.selectedId.value"
        v-model="resourceDeclarations"
        :datasource-id="session.selectedId.value"
        :can-query="session.canAskSelectedDatasource.value"
      />
      <QueryInput
        ref="queryInputRef"
        :question="submit.question.value"
        :is-querying="submit.isQuerying.value"
        :selected-id="session.selectedId.value"
        :selected-datasource-name="session.selectedDatasource.value?.name"
        :can-ask="session.canAskSelectedDatasource.value && resourceDeclarations.length > 0"
        :readiness-loading="session.readinessLoading.value"
        :selected-block-reason="session.selectedBlockReason.value"
        :selected-readiness="session.selectedReadiness.value"
        :example-questions="exampleQuestions"
        :show-examples="Boolean(session.selectedId.value && !session.activeMessages.value.length)"
        @update:question="submit.question.value = $event"
        @send="submit.sendQuestion"
        @cancel="submit.cancelCurrentQuery"
        @apply-example="applyExample"
      />
    </section>

    <QueryResult
      v-if="resultPanelOpen && submit.latestResult.value"
      :latest-result="submit.latestResult.value"
      :result-tab="submit.resultTab.value"
      :chart-type="submit.chartType.value"
      :chart-option="chartOption"
      :paged-table-data="exportUtil.pagedTableData.value"
      :table-page="exportUtil.tablePage.value"
      :table-page-size="exportUtil.tablePageSize"
      :agent-progress="submit.agentProgress.value"
      :is-latest-processing="submit.isLatestProcessing.value"
      :trust-summary="submit.trustSummary.value"
      @close="resultPanelOpen = false"
      @update:result-tab="handleResultTab"
      @switch-chart-type="submit.chartType.value = $event"
      @export-csv="exportUtil.exportCsv"
      @export-png="exportUtil.exportPng"
      @feedback="exportUtil.handleFeedback"
      @update:table-page="exportUtil.tablePage.value = $event"
    />

  </main>
</template>

<style scoped>
.query-workspace { min-height: 100vh; display: grid; grid-template-columns: 268px minmax(0, 1fr); color: var(--do-ink); background: #f8fafc; }
.query-workspace.result-open { grid-template-columns: 268px minmax(540px, 1fr) minmax(430px, 31vw); }
.query-main { min-width: 0; height: 100vh; display: grid; grid-template-rows: 68px minmax(0, 1fr) auto; background: radial-gradient(circle at 50% 38%, rgba(238, 248, 255, .75), transparent 44%), #fbfcfe; }
.query-topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 20px; border-bottom: 1px solid rgba(226, 232, 240, .84); background: rgba(255, 255, 255, .72); backdrop-filter: blur(14px); }
.workspace-title { min-width: 0; }
.workspace-title h1 { max-width: 600px; margin: 0; overflow: hidden; color: var(--do-ink); font-size: 14px; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
.topbar-actions { display: flex; align-items: center; gap: 9px; }
.result-toggle { height: 36px; display: inline-flex; align-items: center; gap: 6px; padding: 0 11px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-primary-strong); background: var(--do-surface); font-size: 12px; font-weight: 700; cursor: pointer; }
.result-toggle:hover { border-color: rgba(77, 143, 220, .45); background: var(--do-primary-soft); }
.drawer-info strong, .drawer-info small { display: block; max-width: 120px; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.chat-surface { min-height: 0; overflow-y: auto; padding: 28px 28px 18px; }
.welcome-state { min-height: 100%; display: grid; place-items: center; align-content: center; padding-bottom: 48px; text-align: center; }
.welcome-logo { width: 46px; height: 46px; display: grid; place-items: center; margin-bottom: 14px; border-radius: 13px; color: #fff; background: var(--do-primary); font-size: 13px; font-weight: 900; box-shadow: 0 10px 24px rgba(77, 143, 220, .22); }
.welcome-state strong { color: var(--do-ink); font-size: 27px; line-height: 1.2; }
.welcome-state small { margin-top: 6px; color: var(--do-muted); font-size: 13px; }
.welcome-state h2 { margin: 44px 0 0; color: #42526b; font-size: 25px; font-weight: 500; }
.empty-chat { min-height: 100%; display: grid; place-items: center; align-content: center; gap: 10px; color: var(--do-muted); text-align: center; }
.empty-chat-icon { width: 52px; height: 52px; display: grid; place-items: center; margin-bottom: 4px; border-radius: 14px; color: var(--do-primary-strong); background: var(--do-primary-soft); }
.empty-chat h2 { margin: 0; color: var(--do-ink); font-size: 20px; }
.empty-chat p { margin: 0; font-size: 13px; }
.empty-chat button { min-height: 36px; display: inline-flex; align-items: center; gap: 6px; margin-top: 8px; padding: 0 13px; border: 1px solid var(--do-line); border-radius: 8px; color: var(--do-primary-strong); background: #fff; font-size: 12px; cursor: pointer; }
.conversation-stream { width: min(860px, 100%); display: grid; gap: 28px; margin: 0 auto; padding: 12px 0 30px; }
.message-item { display: grid; grid-template-columns: 34px minmax(0, 1fr); gap: 12px; }
.message-item.user { grid-template-columns: minmax(0, 1fr) 34px; }
.message-item.user .message-avatar { grid-column: 2; }
.message-item.user .message-bubble { grid-column: 1; grid-row: 1; justify-self: end; max-width: 76%; border-color: transparent; background: #e8f2ff; box-shadow: none; }
.message-avatar { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 9px; color: var(--do-primary-strong); background: var(--do-primary-soft); }
.message-item.user .message-avatar { color: #fff; background: var(--do-primary); }
.message-bubble { max-width: 720px; padding: 14px 16px; border: 1px solid var(--do-line); border-radius: 11px; background: #fff; box-shadow: 0 8px 20px rgba(15, 23, 42, .05); }
.message-bubble p { margin: 0; color: inherit; font-size: 14px; line-height: 1.75; white-space: pre-wrap; }
.message-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--do-line); }
.message-suggestions small { flex: 0 0 100%; color: var(--do-muted); font-size: 11px; font-weight: 800; }
.message-suggestions button { padding: 5px 9px; border: 1px solid rgba(77, 143, 220, .24); border-radius: 999px; color: var(--do-primary-strong); background: var(--do-primary-soft); font: inherit; font-size: 11px; cursor: pointer; }
.message-suggestions button:hover { border-color: var(--do-primary); background: #e1f0ff; }
.message-meta { min-height: 24px; display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-top: 8px; }
.message-meta small { color: var(--do-muted); font-size: 10px; opacity: .72; }
.message-meta button, .message-actions button { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 6px; color: var(--do-primary-strong); background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.message-meta button { min-height: 26px; padding: 0 7px; }
.message-meta button:hover { background: var(--do-primary-soft); }
.message-actions { display: flex; gap: 8px; margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--do-line); }
.message-actions button { min-height: 30px; padding: 0 9px; border: 1px solid var(--do-line); background: #fff; }
.message-actions button:disabled { cursor: not-allowed; opacity: .5; }
.result-toggle:focus-visible, .message-meta button:focus-visible, .message-actions button:focus-visible, .message-suggestions button:focus-visible, .empty-chat button:focus-visible { outline: 3px solid rgba(77, 143, 220, .2); outline-offset: 2px; }
@media (max-width: 1280px) {
  .query-workspace.result-open { grid-template-columns: 250px minmax(0, 1fr); }
  .query-workspace.result-open :deep(.result-rail) { position: fixed; top: 0; right: 0; z-index: 80; width: min(500px, calc(100vw - 250px)); }
}
</style>
