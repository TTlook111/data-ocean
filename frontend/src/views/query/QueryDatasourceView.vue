<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChevronDown,
  Database,
  History,
  LogOut,
  MessageSquarePlus,
  MessageSquareText,
  RefreshCw,
  Search,
  SendHorizontal,
  Settings,
  ShieldCheck,
  UserRound,
} from 'lucide-vue-next'
import { listMyDatasources, type UserDatasourceItem } from '../../api/datasource'
import { useAuthStore } from '../../stores/auth'

interface LocalMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

interface LocalSession {
  id: string
  datasourceId: number
  title: string
  updatedAt: string
  messages: LocalMessage[]
}

const adminPermissionCodes = [
  'admin:view',
  'datasource:manage',
  'metadata:manage',
  'skills:manage',
  'prompt:manage',
  'field:manage',
  'feedback:review',
  'audit:view',
  'user:manage',
  'role:manage',
  'role:view',
  'department:manage',
]

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const errorMessage = ref('')
const datasources = ref<UserDatasourceItem[]>([])
const selectedId = ref<number>()
const activeSessionId = ref<string>()
const question = ref('')
const keyword = ref('')
const sessions = reactive<LocalSession[]>([])
const questionInputRef = ref<HTMLTextAreaElement>()

const permissions = computed(() => auth.currentUser?.permissions || auth.user?.permissions || [])
const canEnterAdmin = computed(() => permissions.value.includes('*') || adminPermissionCodes.some((code) => permissions.value.includes(code)))
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => (auth.user?.roles?.length ? auth.user.roles.join(' / ') : '普通用户'))
const selectedDatasource = computed(() => datasources.value.find((item) => item.id === selectedId.value))
const datasourceSessions = computed(() =>
  sessions
    .filter((session) => session.datasourceId === selectedId.value)
    .filter((session) => {
      const text = keyword.value.trim()
      if (!text) return true
      return session.title.includes(text) || session.messages.some((message) => message.content.includes(text))
    })
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()),
)
const activeSession = computed(() => sessions.find((session) => session.id === activeSessionId.value))
const activeMessages = computed(() => activeSession.value?.messages || [])

async function focusQuestionInput() {
  await nextTick()
  questionInputRef.value?.focus()
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function createSession(datasourceId: number, title = '新的对话') {
  const now = new Date().toISOString()
  const session: LocalSession = {
    id: `local-${datasourceId}-${Date.now()}`,
    datasourceId,
    title,
    updatedAt: now,
    messages: [
      {
        id: `assistant-${Date.now()}`,
        role: 'assistant',
        content: '已进入当前数据源的对话空间。你可以直接问业务问题，我会在后续 NL2SQL 查询接口接入后返回 SQL、表格和图表。',
        createdAt: now,
      },
    ],
  }
  sessions.unshift(session)
  activeSessionId.value = session.id
  return session
}

function ensureSession(datasourceId: number) {
  const existing = sessions.find((session) => session.datasourceId === datasourceId)
  if (existing) {
    activeSessionId.value = existing.id
    return existing
  }
  return createSession(datasourceId)
}

function selectDatasource(id: number) {
  if (selectedId.value !== id) {
    question.value = ''
    keyword.value = ''
  }
  selectedId.value = id
  ensureSession(id)
  focusQuestionInput()
}

function startNewSession() {
  if (!selectedId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  question.value = ''
  createSession(selectedId.value)
  focusQuestionInput()
}

function selectSession(sessionId: string) {
  const session = sessions.find((item) => item.id === sessionId)
  if (!session) return
  selectedId.value = session.datasourceId
  activeSessionId.value = session.id
  question.value = ''
  focusQuestionInput()
}

function sendQuestion() {
  const text = question.value.trim()
  if (!selectedId.value || !text) return

  const session = activeSession.value || createSession(selectedId.value)
  const now = new Date().toISOString()
  session.messages.push({
    id: `user-${Date.now()}`,
    role: 'user',
    content: text,
    createdAt: now,
  })
  session.messages.push({
    id: `assistant-${Date.now() + 1}`,
    role: 'assistant',
    content: '查询链路正在按模块接入中。当前已固定为“数据源作用域会话”：这条问题会留在当前数据源的历史里，后续会携带 datasourceId、conversationId 和最近上下文提交给 NL2SQL 接口。',
    createdAt: now,
  })
  if (session.title === '新的对话') {
    session.title = text.length > 20 ? `${text.slice(0, 20)}...` : text
  }
  session.updatedAt = now
  question.value = ''
  ElMessage.info('已记录到当前数据源会话，等待查询接口接入')
  focusQuestionInput()
}

function handleEnter(event: KeyboardEvent) {
  if (event.shiftKey) return
  event.preventDefault()
  sendQuestion()
}

async function fetchDatasources() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listMyDatasources()
    datasources.value = result.data
    if (result.data.length && (!selectedId.value || !result.data.some((item) => item.id === selectedId.value))) {
      selectDatasource(result.data[0].id)
    }
    if (!result.data.length) {
      selectedId.value = undefined
      activeSessionId.value = undefined
      question.value = ''
    }
  } catch (error: unknown) {
    datasources.value = []
    selectedId.value = undefined
    activeSessionId.value = undefined
    question.value = ''
    errorMessage.value =
      typeof error === 'object' &&
      error !== null &&
      'response' in error &&
      typeof (error as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
        ? (error as { response: { data: { message: string } } }).response.data.message
        : '数据源加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function logout() {
  await auth.logout()
  await router.replace('/login')
}

function handleUserCommand(command: string) {
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
    logout()
  }
}

onMounted(fetchDatasources)
</script>

<template>
  <main class="query-workspace post-login-page">
    <aside class="query-sidebar">
      <RouterLink class="query-brand" to="/query" aria-label="DataOcean 智能问答">
        <span>DO</span>
        <div>
          <strong>DataOcean</strong>
          <small>AI Query</small>
        </div>
      </RouterLink>

      <section class="sidebar-block">
        <div class="block-title">
          <span>数据源</span>
          <button type="button" :disabled="loading" aria-label="刷新数据源" @click="fetchDatasources">
            <RefreshCw :size="15" />
          </button>
        </div>

        <div v-if="loading && !datasources.length" class="sidebar-loading">正在加载数据源...</div>
        <div v-else-if="errorMessage" class="sidebar-error">
          <span>{{ errorMessage }}</span>
          <button type="button" @click="fetchDatasources">重试</button>
        </div>
        <div v-else-if="!datasources.length" class="sidebar-empty">暂无可用数据源，请联系管理员开通权限。</div>
        <div v-else class="datasource-list">
          <button
            v-for="datasource in datasources"
            :key="datasource.id"
            type="button"
            class="datasource-row"
            :class="{ active: datasource.id === selectedId }"
            @click="selectDatasource(datasource.id)"
          >
            <Database :size="16" />
            <span>
              <strong>{{ datasource.name }}</strong>
              <small>{{ datasource.databaseName }}</small>
            </span>
          </button>
        </div>
      </section>

      <section class="sidebar-block history-block">
        <div class="block-title">
          <span>当前数据源历史</span>
          <button type="button" :disabled="!selectedId" aria-label="新建对话" @click="startNewSession">
            <MessageSquarePlus :size="15" />
          </button>
        </div>

        <label class="history-search">
          <Search :size="15" />
          <input v-model="keyword" type="search" placeholder="搜索当前数据源会话" :disabled="!selectedId" />
        </label>

        <div v-if="!selectedId" class="sidebar-empty">选择数据源后显示对应历史。</div>
        <div v-else-if="!datasourceSessions.length" class="sidebar-empty">当前数据源暂无历史会话。</div>
        <div v-else class="history-list">
          <button
            v-for="session in datasourceSessions"
            :key="session.id"
            type="button"
            class="history-row"
            :class="{ active: session.id === activeSessionId }"
            @click="selectSession(session.id)"
          >
            <History :size="15" />
            <span>
              <strong>{{ session.title }}</strong>
              <small>{{ session.messages.length }} 条消息 · {{ formatTime(session.updatedAt) }}</small>
            </span>
          </button>
        </div>
      </section>
    </aside>

    <section class="query-main">
      <header class="query-topbar">
        <div class="workspace-title">
          <span>智能问答</span>
          <h1>{{ selectedDatasource ? selectedDatasource.name : '请选择数据源' }}</h1>
        </div>

        <el-dropdown trigger="click" @command="handleUserCommand">
          <button class="query-user" type="button">
            <span>{{ displayName.slice(0, 1) }}</span>
            <div>
              <strong>{{ displayName }}</strong>
              <small>{{ roleText }}</small>
            </div>
            <ChevronDown :size="15" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="canEnterAdmin" command="admin">
                <Settings :size="15" />
                后台管理
              </el-dropdown-item>
              <el-dropdown-item command="profile">
                <UserRound :size="15" />
                个人资料
              </el-dropdown-item>
              <el-dropdown-item command="password">
                <ShieldCheck :size="15" />
                修改密码
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <LogOut :size="15" />
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <section class="chat-surface">
        <div v-if="!selectedId" class="empty-chat">
          <Database :size="38" />
          <h2>先选择一个数据源</h2>
          <p>每个数据源都有独立的会话历史，选择后再开始提问，避免跨库上下文污染。</p>
        </div>

        <div v-else class="message-list">
          <article
            v-for="message in activeMessages"
            :key="message.id"
            class="message-item"
            :class="message.role"
          >
            <span class="message-avatar">
              <UserRound v-if="message.role === 'user'" :size="16" />
              <MessageSquareText v-else :size="16" />
            </span>
            <div class="message-bubble">
              <p>{{ message.content }}</p>
              <small>{{ formatTime(message.createdAt) }}</small>
            </div>
          </article>
        </div>
      </section>

      <footer class="chat-composer">
        <textarea
          ref="questionInputRef"
          v-model="question"
          :disabled="!selectedId"
          rows="1"
          :placeholder="selectedId ? '向当前数据源提问，例如：上个月销售额最高的10个产品' : '请先选择左侧数据源'"
          @keydown.enter="handleEnter"
        ></textarea>
        <button type="button" :disabled="!selectedId || !question.trim()" @click="sendQuestion">
          <SendHorizontal :size="18" />
          <span>发送</span>
        </button>
      </footer>
    </section>
  </main>
</template>

<style scoped>
.query-workspace {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 292px minmax(0, 1fr);
  color: var(--do-ink);
  background:
    linear-gradient(180deg, rgba(189, 232, 248, 0.42) 0, rgba(245, 251, 239, 0.76) 320px, var(--do-bg) 100%),
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
  background: rgba(255, 253, 246, 0.86);
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

.datasource-row:hover,
.history-row:hover,
.datasource-row.active,
.history-row.active {
  border-color: var(--do-line);
  background: rgba(77, 143, 220, 0.11);
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
  border-bottom: 1px solid rgba(190, 210, 176, 0.72);
  background: rgba(255, 253, 246, 0.84);
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
  grid-template-columns: 32px auto 16px;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 6px;
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
.query-user small {
  display: block;
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

.chat-surface {
  min-height: 0;
  overflow-y: auto;
  padding: 30px 24px 18px;
}

.message-list {
  width: min(920px, 100%);
  display: grid;
  gap: 18px;
  margin: 0 auto;
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
  box-shadow: 0 8px 18px rgba(77, 143, 220, 0.08);
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
  background: rgba(255, 253, 246, 0.94);
  box-shadow: var(--do-shadow);
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

.chat-composer textarea::placeholder {
  color: #8da083;
}

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

:deep(.el-dropdown-menu__item) {
  gap: 8px;
}

@media (max-width: 1180px) {
  .query-workspace {
    min-width: 1120px;
  }
}
</style>
