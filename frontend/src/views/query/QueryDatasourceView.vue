<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  BookOpen,
  Database,
  History,
  LogOut,
  MessageSquareText,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  UserCog,
  UserRound,
  X,
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

// ---- 管理员权限 ----
const adminPermissionCodes = [
  'admin:view',
  'datasource:manage',
  'metadata:manage',
  'skills:manage',
  'prompt:manage',
  'field:manage',
  'field-tag:manage',
  'feedback:review',
  'audit:view',
  'user:manage',
  'role:manage',
  'role:view',
  'department:manage',
  'knowledge:manage',
]

// ---- 基础状态 ----
const router = useRouter()
const auth = useAuthStore()
const showGuideBanner = ref(!localStorage.getItem('do-query-guide-dismissed'))
const workspaceRef = ref<HTMLElement | null>(null)
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(workspaceRef)

// ---- 用户信息 computed ----
const permissions = computed(() => auth.currentUser?.permissions || auth.user?.permissions || [])
const canEnterAdmin = computed(() => permissions.value.includes('*') || adminPermissionCodes.some((code) => permissions.value.includes(code)))
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => roleCodesLabel(auth.currentUser?.roles || auth.user?.roles, '普通用户'))

// ---- 示例问题 ----
const exampleQuestions = [
  '统计最近30天订单金额趋势',
  '找出销售额最高的10个客户',
  '查看库存低于安全线的商品',
  '按部门汇总本月费用',
]

// ---- Composables ----
const session = useQuerySession()

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
    if (lastMessage) {
      lift(lastMessage, { y: 14, duration: 0.26 })
    }
  },
  async animateMessageUpdate(messageId: string) {
    await nextTick()
    const assistantBubble = workspaceRef.value?.querySelector(`[data-message-id="${messageId}"] .message-bubble`)
    if (assistantBubble) {
      lift(assistantBubble, { y: 6, scale: 1, duration: 0.22 })
    }
  },
  async focusQuestionInput() {
    queryInputRef.value?.focusQuestionInput()
  },
})

const exportUtil = useQueryExport({
  latestResult: submit.latestResult,
})

const chartOption = computed<Record<string, unknown> | null>(() => {
  if (!submit.latestResult.value?.chartConfig) return null
  try {
    const option = JSON.parse(JSON.stringify(submit.latestResult.value.chartConfig))
    if (option.series && option.series.length > 0) {
      option.series[0].type = submit.chartType.value
    }
    return option
  } catch {
    return null
  }
})

// ---- 引用子组件 ----
const queryInputRef = ref<InstanceType<typeof QueryInput>>()

// ---- 监听结果变化时重置分页 ----
watch(submit.latestResult, () => { exportUtil.tablePage.value = 1 })

// ---- 监听 resultTab 变化时触发动画 ----
watch(submit.resultTab, () => {
  nextTick(() => {
    const resultBody = workspaceRef.value?.querySelector('.result-preview > div:not(.result-tabs)')
    if (resultBody) {
      lift(resultBody, { y: 6, duration: 0.22, scale: 1 })
    }
  })
})

// ---- 引导横幅 ----
function dismissGuideBanner() {
  showGuideBanner.value = false
  localStorage.setItem('do-query-guide-dismissed', '1')
}

// ---- 用户菜单 ----
function handleUserCommand(command: string) {
  session.drawerVisible.value = false
  if (command === 'admin') {
    router.push('/admin')
    return
  }
  if (command === 'profile') {
    router.push('/profile')
    return
  }
  if (command === 'password') {
    router.push('/change-password')
    return
  }
  if (command === 'logout') {
    auth.logout()
    router.push('/login')
  }
}

// ---- 格式化时间 ----
function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

// ---- 示例问题应用 ----
function applyExample(text: string) {
  if (!session.selectedId.value || !session.canAskSelectedDatasource.value) return
  submit.question.value = text
  queryInputRef.value?.focusQuestionInput()
}

// ---- 侧边栏事件 ----
function handleSelectDatasource(id: number) {
  submit.question.value = ''
  session.selectDatasource(id, {
    afterSelect() {
      revealAfterTick('.workspace-brief, .example-strip, .message-item, .result-preview', {
        y: 14,
        stagger: 0.04,
      })
    },
  })
  queryInputRef.value?.focusQuestionInput()
}

function handleStartNewSession() {
  session.startNewSession({
    focusQuestionInput() {
      queryInputRef.value?.focusQuestionInput()
    },
  })
  submit.question.value = ''
}

function handleSelectSession(sessionId: string) {
  submit.question.value = ''
  session.selectSession(sessionId, {
    focusQuestionInput() {
      queryInputRef.value?.focusQuestionInput()
    },
  })
}

// ---- 生命周期 ----
onMounted(() => {
  withContext(() => {
    reveal('.query-brand, .sidebar-block, .query-topbar, .chat-surface, .chat-composer', {
      y: 16,
      stagger: 0.045,
    })
  })
  session.fetchDatasources({
    afterSelect() {
      revealAfterTick('.workspace-brief, .example-strip, .message-item, .result-preview', {
        y: 14,
        stagger: 0.04,
      })
    },
    focusQuestionInput() {
      queryInputRef.value?.focusQuestionInput()
    },
  })
})

watch(
  () => session.datasources.value.length,
  () => {
    revealAfterTick('.datasource-row', {
      y: 8,
      duration: 0.28,
      stagger: 0.025,
    })
  },
)

watch(
  () => session.datasourceSessions.value.length,
  () => {
    revealAfterTick('.history-row', {
      y: 8,
      duration: 0.24,
      stagger: 0.022,
    })
  },
)
</script>

<template>
  <main ref="workspaceRef" class="query-workspace post-login-page">
    <!-- 首次使用引导横幅 -->
    <div v-if="showGuideBanner" class="guide-banner">
      <BookOpen :size="16" />
      <span>第一次使用？查看<RouterLink to="/guide/query">快速入门指南</RouterLink>，4 步学会用自然语言查数据</span>
      <button class="banner-close" @click="dismissGuideBanner">
        <X :size="14" />
      </button>
    </div>

    <!-- 左侧边栏 -->
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

    <!-- 主区域 -->
    <section class="query-main">
      <header class="query-topbar">
        <div class="workspace-title">
          <span>智能问答</span>
          <h1>{{ session.selectedDatasource.value ? session.selectedDatasource.value.name : '请选择数据源' }}</h1>
        </div>

        <button class="query-user" type="button" @click="session.drawerVisible.value = true">
          <span>{{ displayName.slice(0, 1) }}</span>
          <div>
            <strong>{{ displayName }}</strong>
            <small>{{ roleText }}</small>
          </div>
        </button>
      </header>

      <section class="chat-surface">
        <!-- 未选择数据源 -->
        <div v-if="!session.selectedId.value" class="empty-chat">
          <Database :size="38" />
          <h2>先选择一个数据源</h2>
          <p>每个数据源都有独立的会话历史，选择后再开始提问，避免跨库上下文污染。</p>
        </div>

        <!-- 已选择数据源 -->
        <div v-else class="message-list">
          <!-- 数据源概要 -->
          <section class="workspace-brief">
            <div class="brief-main">
              <span class="brief-kicker">当前上下文</span>
              <h2>{{ session.selectedDatasource.value?.databaseName || session.selectedDatasource.value?.name }}</h2>
              <p>{{ session.selectedDatasource.value?.description || '当前会话限定在此数据源。' }}</p>
            </div>
            <div class="brief-metrics">
              <span class="metric-chip"><Database :size="14" />{{ session.askableDatasourceCount.value }} / {{ session.datasources.value.length }} 个可询问</span>
              <span class="metric-chip"><History :size="14" />{{ session.datasourceSessions.value.length }} 个当前库会话</span>
            </div>
          </section>

          <!-- 数据源不可用提示 -->
          <section v-if="session.selectedId.value && !session.canAskSelectedDatasource.value" class="readiness-notice">
            <ShieldAlert :size="16" />
            <div>
              <strong>{{ session.selectedReadiness.value?.stageLabel || (session.readinessLoading.value ? '正在确认状态' : '状态确认失败') }}</strong>
              <span>{{ session.selectedBlockReason.value?.message || (session.readinessLoading.value ? '正在确认该数据源是否可询问。' : '未能确认该数据源上线状态，请刷新后重试。') }}</span>
            </div>
            <RouterLink v-if="session.selectedBlockReason.value?.actionPath && canEnterAdmin" :to="session.selectedBlockReason.value.actionPath">
              {{ session.selectedBlockReason.value.actionText || '去处理' }}
            </RouterLink>
          </section>

          <!-- 对话 + 结果双栏 -->
          <div class="query-cockpit">
            <section class="conversation-rail" aria-label="对话">
              <article
                v-for="message in session.activeMessages.value"
                :key="message.id"
                class="message-item"
                :class="message.role"
                :data-message-id="message.id"
              >
                <span class="message-avatar">
                  <UserRound v-if="message.role === 'user'" :size="16" />
                  <MessageSquareText v-else :size="16" />
                </span>
                <div class="message-bubble">
                  <p>{{ message.content }}</p>
                  <small>{{ formatTime(message.createdAt) }}</small>
                  <!-- TIMEOUT 状态操作按钮 -->
                  <div v-if="message.role === 'assistant' && message.status === 'TIMEOUT'" class="message-actions">
                    <button class="action-btn retry" @click="submit.retryQuery(message.originalQuestion || '')" :disabled="submit.isQuerying.value">
                      <RefreshCw :size="14" />重试查询
                    </button>
                    <button class="action-btn wait" @click="submit.continueWaiting(message.taskId || '')" :disabled="submit.isQuerying.value">
                      <History :size="14" />继续等待
                    </button>
                  </div>
                  <!-- 失败状态操作按钮 -->
                  <div v-if="message.role === 'assistant' && (message.status === 'FAILED' || message.status === 'error')" class="message-actions">
                    <button class="action-btn retry" @click="submit.retryQuery(message.originalQuestion || '')" :disabled="submit.isQuerying.value || !message.originalQuestion">
                      <RefreshCw :size="14" />重新提问
                    </button>
                  </div>
                </div>
              </article>
            </section>

            <!-- 结果面板 -->
            <QueryResult
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
              @update:result-tab="submit.resultTab.value = $event"
              @switch-chart-type="submit.chartType.value = $event"
              @export-csv="exportUtil.exportCsv"
              @export-png="exportUtil.exportPng"
              @feedback="exportUtil.handleFeedback"
              @apply-example="applyExample"
              @update:table-page="exportUtil.tablePage.value = $event"
            />
          </div>
        </div>
      </section>

      <!-- 输入区域 -->
      <QueryInput
        ref="queryInputRef"
        :question="submit.question.value"
        :is-querying="submit.isQuerying.value"
        :selected-id="session.selectedId.value"
        :can-ask="session.canAskSelectedDatasource.value"
        :readiness-loading="session.readinessLoading.value"
        :selected-block-reason="session.selectedBlockReason.value"
        :selected-readiness="session.selectedReadiness.value"
        :example-questions="exampleQuestions"
        @update:question="submit.question.value = $event"
        @send="submit.sendQuestion"
        @cancel="submit.cancelCurrentQuery"
        @apply-example="applyExample"
      />
    </section>

    <!-- 用户抽屉 -->
    <el-drawer v-model="session.drawerVisible.value" direction="rtl" size="280px" :show-close="false">
      <template #header>
        <div class="drawer-profile">
          <div class="drawer-avatar">{{ displayName.slice(0, 1) }}</div>
          <div class="drawer-info">
            <strong>{{ displayName }}</strong>
            <small>{{ roleText }}</small>
          </div>
        </div>
      </template>
      <nav class="drawer-nav">
        <button class="drawer-item" @click="handleUserCommand('profile')">
          <UserRound :size="18" />
          <span>个人资料</span>
        </button>
        <button class="drawer-item" @click="handleUserCommand('password')">
          <ShieldCheck :size="18" />
          <span>修改密码</span>
        </button>
        <button v-if="canEnterAdmin" class="drawer-item" @click="handleUserCommand('admin')">
          <UserCog :size="18" />
          <span>后台管理</span>
        </button>
        <div class="drawer-divider"></div>
        <button class="drawer-item drawer-item--danger" @click="handleUserCommand('logout')">
          <LogOut :size="18" />
          <span>退出登录</span>
        </button>
      </nav>
    </el-drawer>
  </main>
</template>

<style scoped>
.guide-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  background: var(--do-primary-soft);
  border-bottom: 1px solid var(--do-line);
  font-size: 13px;
  color: var(--do-ink);
}

.guide-banner a {
  color: var(--do-primary);
  font-weight: 600;
  text-decoration: none;
}

.guide-banner a:hover { text-decoration: underline; }

.banner-close {
  margin-left: auto;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--do-muted);
  padding: 4px;
  border-radius: 4px;
}

.banner-close:hover { background: rgba(0,0,0,0.05); }

.query-workspace {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 292px minmax(0, 1fr);
  color: var(--do-ink);
  background:
    linear-gradient(180deg, rgba(238, 248, 255, 0.92) 0, rgba(248, 250, 252, 0.86) 320px, var(--do-bg) 100%),
    var(--do-bg);
}

.query-sidebar {
  height: 100vh;
  position: sticky;
  top: 0;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 16px;
  padding: 16px;
  border-right: 1px solid var(--do-line);
  background: rgba(248, 251, 255, 0.92);
  backdrop-filter: blur(18px);
}

.query-brand {
  height: 48px;
  display: grid;
  grid-template-columns: 40px 1fr;
  align-items: center;
  gap: 11px;
}

.query-brand > span {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, var(--do-primary), var(--do-accent));
  font-size: 13px;
  font-weight: 900;
}

.query-brand strong,
.query-brand small {
  display: block;
}

.query-brand strong {
  font-size: 17px;
}

.query-brand small {
  color: var(--do-muted);
  font-size: 12px;
}

.sidebar-block {
  display: grid;
  gap: 10px;
}

.history-block {
  min-height: 0;
  grid-template-rows: auto auto minmax(0, 1fr);
}

.block-title {
  min-height: 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 900;
}

.block-title button {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-primary-strong);
  background: var(--do-surface);
  cursor: pointer;
}

.block-title button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.datasource-list,
.history-list {
  min-height: 0;
  display: grid;
  align-content: start;
  gap: 6px;
  overflow-y: auto;
}

.datasource-row,
.history-row {
  width: 100%;
  min-height: 52px;
  display: grid;
  grid-template-columns: 28px 1fr;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--do-ink);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.history-row {
  grid-template-columns: 28px minmax(0, 1fr) 30px;
}

.history-delete {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 6px;
  color: var(--do-muted);
  background: transparent;
  cursor: pointer;
  opacity: 0;
}

.history-row:hover .history-delete,
.history-row:focus-within .history-delete {
  opacity: 1;
}

.history-delete:hover {
  color: #b42318;
  background: rgba(180, 35, 24, 0.1);
}

.datasource-row:hover,
.history-row:hover,
.datasource-row.active,
.history-row.active {
  border-color: var(--do-line);
  background: rgba(77, 143, 220, 0.11);
}

.datasource-row.not-askable {
  border-color: rgba(180, 83, 9, 0.16);
  background: rgba(255, 251, 235, 0.62);
}

.datasource-row svg,
.history-row svg {
  color: var(--do-primary-strong);
}

.datasource-row span,
.history-row span {
  min-width: 0;
}

.datasource-row strong,
.datasource-row small,
.history-row strong,
.history-row small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.datasource-row strong,
.history-row strong {
  margin-bottom: 3px;
  font-size: 13px;
}

.datasource-row small,
.history-row small {
  color: var(--do-muted);
  font-size: 12px;
}

.datasource-row .readiness-chip {
  width: fit-content;
  margin-top: 4px;
  padding: 1px 6px;
  border: 1px solid rgba(77, 143, 220, 0.18);
  border-radius: 999px;
  color: var(--do-primary-strong);
  background: rgba(77, 143, 220, 0.08);
  font-size: 11px;
  font-weight: 800;
}

.datasource-row.not-askable .readiness-chip {
  border-color: rgba(180, 83, 9, 0.22);
  color: #92400e;
  background: rgba(251, 191, 36, 0.15);
}

.history-search {
  height: 38px;
  display: grid;
  grid-template-columns: 22px 1fr;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
}

.history-search svg {
  color: var(--do-muted);
}

.history-search input {
  min-width: 0;
  border: 0;
  outline: 0;
  color: var(--do-ink);
  background: transparent;
  font-size: 13px;
}

.sidebar-loading,
.sidebar-empty,
.sidebar-error {
  padding: 12px;
  border: 1px dashed var(--do-line-strong);
  border-radius: 8px;
  color: var(--do-muted);
  background: rgba(255, 255, 255, 0.54);
  font-size: 13px;
  line-height: 1.6;
}

.sidebar-error {
  display: grid;
  gap: 8px;
  color: var(--do-danger);
}

.sidebar-error button {
  justify-self: start;
  border: 0;
  color: var(--do-primary);
  background: transparent;
  font-weight: 900;
  cursor: pointer;
}

.query-main {
  min-width: 0;
  min-height: 100vh;
  display: grid;
  grid-template-rows: 72px minmax(0, 1fr) auto;
}

.query-topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 0 24px;
  border-bottom: 1px solid var(--do-line);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(14px);
}

.workspace-title {
  min-width: 0;
}

.workspace-title span {
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 900;
}

.workspace-title h1 {
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--do-ink);
  font-size: 20px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-user,
.chat-composer button {
  border: 0;
  cursor: pointer;
}

.query-user {
  height: 44px;
  display: grid;
  grid-template-columns: 32px auto;
  align-items: center;
  gap: 10px;
  padding: 5px 14px 5px 6px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: var(--do-ink);
  background: var(--do-surface);
}

.query-user > span {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, var(--do-primary), var(--do-accent));
  font-weight: 900;
}

.query-user strong,
.query-user small,
.drawer-info strong,
.drawer-info small {
  display: block;
}

.query-user strong,
.query-user small {
  max-width: 150px;
  overflow: hidden;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-user strong {
  font-size: 13px;
  line-height: 1.2;
}

.query-user small {
  color: var(--do-muted);
  font-size: 11px;
}

.drawer-profile {
  display: flex;
  align-items: center;
  gap: 12px;
}

.drawer-avatar {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(135deg, var(--do-primary), var(--do-accent));
  font-weight: 900;
  font-size: 18px;
}

.drawer-info strong {
  color: var(--do-ink);
  font-size: 15px;
}

.drawer-info small {
  color: var(--do-muted);
  font-size: 12px;
}

.drawer-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
}

.drawer-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--do-ink);
  font-size: 14px;
  cursor: pointer;
  transition: background 150ms;
}

.drawer-item:hover {
  background: var(--do-bg);
}

.drawer-item--danger {
  color: #ef4444;
}

.drawer-item--danger:hover {
  background: #fef2f2;
}

.drawer-divider {
  height: 1px;
  margin: 8px 16px;
  background: var(--do-line);
}

.chat-surface {
  min-height: 0;
  overflow-y: auto;
  padding: 30px 24px 18px;
}

.message-list {
  width: min(1280px, 100%);
  display: grid;
  gap: 16px;
  margin: 0 auto;
}

.query-cockpit {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(360px, 0.72fr);
  align-items: start;
  gap: 18px;
}

.conversation-rail {
  min-width: 0;
  display: grid;
  gap: 14px;
}

.result-rail {
  position: sticky;
  top: 92px;
  align-self: start;
}

.workspace-brief {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  align-items: start;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background:
    linear-gradient(135deg, rgba(77, 143, 220, 0.1), rgba(106, 168, 79, 0.07) 42%, rgba(255, 255, 255, 0.96) 100%),
    var(--do-surface);
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.08);
}

.brief-main {
  min-width: 0;
}

.brief-kicker {
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 900;
}

.brief-main h2 {
  margin: 5px 0 6px;
  overflow: hidden;
  color: var(--do-ink);
  font-size: 18px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brief-main p {
  max-width: 620px;
  margin: 0;
  color: var(--do-muted);
  font-size: 13px;
  line-height: 1.7;
}

.brief-metrics {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.readiness-notice {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid rgba(180, 83, 9, 0.22);
  border-radius: 8px;
  color: #92400e;
  background: #fffbeb;
}

.readiness-notice svg {
  color: #b45309;
}

.readiness-notice div {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.readiness-notice strong,
.readiness-notice span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.readiness-notice strong {
  color: #78350f;
  font-size: 13px;
}

.readiness-notice span {
  font-size: 12px;
}

.readiness-notice a {
  color: var(--do-primary-strong);
  font-size: 12px;
  font-weight: 900;
  text-decoration: none;
}

.example-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.example-strip button {
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  color: #334155;
  background: #fff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.example-strip button:hover {
  border-color: var(--do-primary);
  color: var(--do-primary-strong);
  box-shadow: 0 0 0 3px rgba(77, 143, 220, 0.1);
}

.example-strip button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
  box-shadow: none;
}

.message-item {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 12px;
}

.message-item.user {
  grid-template-columns: minmax(0, 1fr) 34px;
}

.message-item.user .message-avatar {
  grid-column: 2;
}

.message-item.user .message-bubble {
  grid-column: 1;
  grid-row: 1;
  justify-self: end;
  color: #fff;
  background: var(--do-primary);
}

.message-avatar {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: var(--do-primary-strong);
  background: var(--do-primary-soft);
}

.message-bubble {
  max-width: 760px;
  padding: 13px 15px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: var(--do-surface);
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.06);
}

.result-preview {
  margin-top: 2px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: var(--do-surface);
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.1);
  overflow: hidden;
}

.result-tabs {
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  padding: 8px 10px;
  border-bottom: 1px solid var(--do-line);
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.98), rgba(255, 255, 255, 0.94));
}

.result-tabs span {
  height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border-radius: 8px;
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 800;
}

.result-tabs span.active {
  color: var(--do-primary-strong);
  background: #eaf4ff;
  box-shadow: inset 0 0 0 1px rgba(77, 143, 220, 0.14);
}

.result-empty {
  min-height: 112px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 6px;
  color: var(--do-muted);
  font-size: 13px;
}

.result-empty strong {
  color: var(--do-ink);
  font-size: 14px;
}

.message-bubble p {
  margin: 0;
  color: inherit;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.message-bubble small {
  display: block;
  margin-top: 8px;
  color: inherit;
  font-size: 11px;
  opacity: 0.62;
}

.message-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--do-line);
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  background: var(--do-surface);
  color: var(--do-ink);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 150ms;
}

.action-btn:hover:not(:disabled) {
  border-color: var(--do-primary);
  color: var(--do-primary);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-btn.retry {
  background: var(--do-primary-soft);
  border-color: var(--do-primary);
  color: var(--do-primary-strong);
}

.action-btn.wait {
  background: var(--do-surface);
}

.empty-chat {
  height: 100%;
  min-height: 440px;
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 12px;
  color: var(--do-muted);
  text-align: center;
}

.empty-chat svg {
  color: var(--do-primary);
}

.empty-chat h2 {
  margin: 0;
  color: var(--do-ink);
  font-size: 22px;
}

.empty-chat p {
  max-width: 520px;
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
}

.chat-composer {
  width: min(920px, calc(100% - 48px));
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  margin: 0 auto 22px;
  padding: 12px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--do-shadow);
}

.composer-readiness {
  grid-column: 1 / -1;
  padding: 0 4px 2px;
  color: #92400e;
  font-size: 12px;
  font-weight: 800;
}

.chat-composer textarea {
  min-height: 42px;
  max-height: 132px;
  resize: vertical;
  border: 0;
  outline: 0;
  padding: 10px 4px 8px 6px;
  color: var(--do-ink);
  background: transparent;
  font-size: 14px;
  line-height: 1.55;
}

.chat-composer textarea::placeholder { color: #94a3b8; }

.chat-composer button {
  min-width: 92px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 8px;
  color: #fff;
  background: var(--do-primary);
  font-weight: 900;
}

.chat-composer button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.chat-composer .cancel-btn {
  background: var(--do-tone-red-bg, #fef2f2);
  color: var(--do-tone-red, #dc2626);
  border: 1px solid var(--do-tone-red, #dc2626);
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}

@media (max-width: 1180px) {
  .query-workspace {
    min-width: 1120px;
  }

  .query-cockpit {
    grid-template-columns: 1fr;
  }

  .result-rail {
    position: static;
  }

}

.chart-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.chart-type-switcher {
  display: flex;
  gap: 4px;
}

.chart-type-switcher button {
  padding: 4px 12px;
  border: 1px solid var(--do-line);
  border-radius: 4px;
  background: var(--do-surface);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.chart-type-switcher button.active {
  background: var(--do-primary);
  color: #fff;
  border-color: var(--do-primary);
}

.chart-container {
  width: 100%;
  height: 280px;
}

.trust-panel {
  display: grid;
  gap: 12px;
  padding: 12px;
}

.agent-progress {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}

.agent-step {
  min-height: 48px;
  position: relative;
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  align-items: center;
  gap: 7px;
  padding: 10px 11px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #f8fafc;
  color: var(--do-muted);
  font-size: 12px;
}

.agent-step:not(:last-child)::after {
  position: absolute;
  left: 18px;
  top: calc(100% - 1px);
  width: 1px;
  height: 10px;
  background: var(--do-line-strong);
  content: "";
}

.agent-step strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #cbd5e1;
}

.agent-step.done {
  color: #166534;
  background: #f0fdf4;
  border-color: #bbf7d0;
}

.agent-step.done .step-dot {
  background: #22c55e;
}

.agent-step.active {
  color: var(--do-primary-strong);
  background: #eff6ff;
  border-color: #bfdbfe;
}

.agent-step.active .step-dot {
  background: var(--do-primary);
  box-shadow: 0 0 0 4px rgba(77, 143, 220, 0.14);
}

.agent-step.failed {
  color: #b42318;
  background: #fef2f2;
  border-color: #fecaca;
}

.agent-step.failed .step-dot {
  background: #ef4444;
}

.trust-notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  color: var(--do-primary-strong);
  background: #eff6ff;
  font-size: 12px;
  font-weight: 700;
}

.trust-notice.warning {
  color: #92400e;
  background: #fffbeb;
  border-color: #fde68a;
}

.trust-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

@media (max-width: 1180px) {
  .agent-progress {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .agent-step:not(:last-child)::after {
    display: none;
  }
}

.trust-card {
  min-height: 78px;
  display: grid;
  align-content: start;
  gap: 7px;
  padding: 11px;
  border: 1px solid var(--do-line);
  border-radius: 8px;
  background: #fff;
}

.trust-card span {
  color: var(--do-muted);
  font-size: 12px;
  font-weight: 900;
}

.trust-card strong {
  overflow-wrap: anywhere;
  color: var(--do-ink);
  font-size: 13px;
  line-height: 1.55;
}

.trust-card.muted strong {
  color: var(--do-muted);
  font-weight: 700;
}

.result-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0 0;
  border-top: 1px solid var(--do-line);
  margin-top: 12px;
}

.export-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  border: 1px solid var(--do-line);
  border-radius: 4px;
  background: var(--do-surface);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.export-btn:hover {
  border-color: var(--do-primary);
  color: var(--do-primary);
}

.export-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.feedback-btns {
  display: flex;
  gap: 8px;
}

.feedback-btn {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid var(--do-line);
  border-radius: 6px;
  background: var(--do-surface);
  cursor: pointer;
  transition: all 0.15s;
}

.feedback-btn.like:hover {
  border-color: #67c23a;
  color: #67c23a;
  background: #f0f9eb;
}

.feedback-btn.dislike:hover {
  border-color: #f56c6c;
  color: #f56c6c;
  background: #fef0f0;
}
</style>
