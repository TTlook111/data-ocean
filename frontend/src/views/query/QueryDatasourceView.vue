<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Database,
  History,
  LogOut,
  MessageSquareText,
  PanelRightOpen,
  RefreshCw,
  ShieldCheck,
  UserCog,
  UserRound,
} from 'lucide-vue-next'
import { useGsapMotion } from '../../composables/useGsapMotion'
import { useAuthStore } from '../../stores/auth'
import { roleCodesLabel } from '../../utils/enumLabels'
import { useQuerySession } from '../../composables/useQuerySession'
import { useQuerySubmit } from '../../composables/useQuerySubmit'
import { useQueryExport } from '../../composables/useQueryExport'
import QuerySidebar from './QuerySidebar.vue'
import QueryInput from './QueryInput.vue'
import QueryResult from './QueryResult.vue'

const adminPermissionCodes = [
  'admin:view', 'datasource:manage', 'metadata:manage', 'skills:manage', 'prompt:manage',
  'field:manage', 'field-tag:manage', 'feedback:review', 'audit:view', 'user:manage',
  'role:manage', 'role:view', 'department:manage', 'knowledge:manage',
]

const router = useRouter()
const auth = useAuthStore()
const workspaceRef = ref<HTMLElement | null>(null)
const queryInputRef = ref<InstanceType<typeof QueryInput>>()
const resultPanelOpen = ref(false)
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(workspaceRef)

const permissions = computed(() => auth.currentUser?.permissions || auth.user?.permissions || [])
const canEnterAdmin = computed(() => permissions.value.includes('*') || adminPermissionCodes.some((code) => permissions.value.includes(code)))
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => roleCodesLabel(auth.currentUser?.roles || auth.user?.roles, '普通用户'))

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
  session.drawerVisible.value = false
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

function handleSelectDatasource(id: number) {
  submit.question.value = ''
  resultPanelOpen.value = false
  session.selectDatasource(id, {
    afterSelect() {
      revealAfterTick('.welcome-state, .message-item', { y: 12, stagger: 0.035 })
    },
  })
  queryInputRef.value?.focusQuestionInput()
}

function handleStartNewSession() {
  session.startNewSession({ focusQuestionInput: () => queryInputRef.value?.focusQuestionInput() })
  submit.question.value = ''
  resultPanelOpen.value = false
}

function handleSelectSession(sessionId: string) {
  submit.question.value = ''
  resultPanelOpen.value = false
  session.selectSession(sessionId, { focusQuestionInput: () => queryInputRef.value?.focusQuestionInput() })
}

onMounted(() => {
  withContext(() => reveal('.query-brand, .new-session-button, .datasource-section, .history-section, .query-topbar, .chat-composer', { y: 14, stagger: 0.04 }))
  session.fetchDatasources({
    afterSelect() { revealAfterTick('.welcome-state, .message-item', { y: 12, stagger: 0.035 }) },
    focusQuestionInput() { queryInputRef.value?.focusQuestionInput() },
  })
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
      @select-datasource="handleSelectDatasource"
      @select-session="handleSelectSession"
      @remove-session="session.removeSession"
      @new-session="handleStartNewSession"
      @refresh="() => session.fetchDatasources()"
      @update:keyword="session.keyword.value = $event"
    />

    <section class="query-main">
      <header class="query-topbar">
        <div class="workspace-title"><h1>{{ sessionTitle }}</h1></div>
        <div class="topbar-actions">
          <button v-if="submit.latestResult.value && !resultPanelOpen" class="result-toggle" type="button" @click="resultPanelOpen = true">
            <PanelRightOpen :size="16" /><span>查看结果</span>
          </button>
          <button class="query-user" type="button" @click="session.drawerVisible.value = true">
            <span>{{ displayName.slice(0, 1) }}</span>
            <div><strong>{{ displayName }}</strong><small>{{ roleText }}</small></div>
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

      <QueryInput
        ref="queryInputRef"
        :question="submit.question.value"
        :is-querying="submit.isQuerying.value"
        :selected-id="session.selectedId.value"
        :selected-datasource-name="session.selectedDatasource.value?.name"
        :can-ask="session.canAskSelectedDatasource.value"
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
      @update:result-tab="submit.resultTab.value = $event"
      @switch-chart-type="submit.chartType.value = $event"
      @export-csv="exportUtil.exportCsv"
      @export-png="exportUtil.exportPng"
      @feedback="exportUtil.handleFeedback"
      @apply-example="applyExample"
      @update:table-page="exportUtil.tablePage.value = $event"
    />

    <el-drawer v-model="session.drawerVisible.value" direction="rtl" size="280px" :show-close="false">
      <template #header>
        <div class="drawer-profile"><div class="drawer-avatar">{{ displayName.slice(0, 1) }}</div><div class="drawer-info"><strong>{{ displayName }}</strong><small>{{ roleText }}</small></div></div>
      </template>
      <nav class="drawer-nav">
        <button class="drawer-item" @click="handleUserCommand('profile')"><UserRound :size="18" /><span>个人资料</span></button>
        <button class="drawer-item" @click="handleUserCommand('password')"><ShieldCheck :size="18" /><span>修改密码</span></button>
        <button v-if="canEnterAdmin" class="drawer-item" @click="handleUserCommand('admin')"><UserCog :size="18" /><span>后台管理</span></button>
        <div class="drawer-divider"></div>
        <button class="drawer-item drawer-item--danger" @click="handleUserCommand('logout')"><LogOut :size="18" /><span>退出登录</span></button>
      </nav>
    </el-drawer>
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
.query-user { height: 40px; display: grid; grid-template-columns: 30px auto; align-items: center; gap: 8px; padding: 4px 10px 4px 5px; border: 1px solid var(--do-line); border-radius: 9px; color: var(--do-ink); background: var(--do-surface); cursor: pointer; }
.query-user > span { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 7px; color: #fff; background: var(--do-primary); font-size: 12px; font-weight: 900; }
.query-user strong, .query-user small, .drawer-info strong, .drawer-info small { display: block; max-width: 120px; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.query-user strong { font-size: 12px; }
.query-user small { margin-top: 2px; color: var(--do-muted); font-size: 10px; }
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
.message-meta { min-height: 24px; display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-top: 8px; }
.message-meta small { color: var(--do-muted); font-size: 10px; opacity: .72; }
.message-meta button, .message-actions button { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 6px; color: var(--do-primary-strong); background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.message-meta button { min-height: 26px; padding: 0 7px; }
.message-meta button:hover { background: var(--do-primary-soft); }
.message-actions { display: flex; gap: 8px; margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--do-line); }
.message-actions button { min-height: 30px; padding: 0 9px; border: 1px solid var(--do-line); background: #fff; }
.message-actions button:disabled { cursor: not-allowed; opacity: .5; }
.drawer-profile { display: flex; align-items: center; gap: 12px; }
.drawer-avatar { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 10px; color: #fff; background: var(--do-primary); font-weight: 900; }
.drawer-info strong { color: var(--do-ink); font-size: 14px; }
.drawer-info small { margin-top: 3px; color: var(--do-muted); font-size: 11px; }
.drawer-nav { display: flex; flex-direction: column; gap: 4px; padding: 8px 0; }
.drawer-item { display: flex; align-items: center; gap: 11px; padding: 11px 14px; border: 0; border-radius: 8px; color: var(--do-ink); background: transparent; font-size: 13px; cursor: pointer; }
.drawer-item:hover { background: var(--do-bg); }
.drawer-item--danger { color: #b42318; }
.drawer-divider { height: 1px; margin: 8px 14px; background: var(--do-line); }
.result-toggle:focus-visible, .query-user:focus-visible, .message-meta button:focus-visible, .message-actions button:focus-visible, .empty-chat button:focus-visible { outline: 3px solid rgba(77, 143, 220, .2); outline-offset: 2px; }
@media (max-width: 1280px) {
  .query-workspace.result-open { grid-template-columns: 250px minmax(0, 1fr); }
  .query-workspace.result-open :deep(.result-rail) { position: fixed; top: 0; right: 0; z-index: 80; width: min(500px, calc(100vw - 250px)); }
}
@media (max-width: 920px) {
  .query-workspace, .query-workspace.result-open { grid-template-columns: 224px minmax(0, 1fr); }
  .query-workspace.result-open :deep(.result-rail) { width: min(500px, calc(100vw - 224px)); }
  .query-user div { display: none; }
  .query-user { grid-template-columns: 30px; padding-right: 5px; }
}
</style>
