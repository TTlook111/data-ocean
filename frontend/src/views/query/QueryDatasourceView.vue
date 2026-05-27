<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  BarChart3,
  Database,
  Download,
  History,
  LogOut,
  ListChecks,
  MessageSquarePlus,
  MessageSquareText,
  RefreshCw,
  Search,
  SendHorizontal,
  ShieldCheck,
  ThumbsUp,
  ThumbsDown,
  UserCog,
  UserRound,
} from 'lucide-vue-next'
import { listMyDatasources, type UserDatasourceItem } from '../../api/datasource'
import { submitQuery, getTaskResult, submitQueryFeedback } from '../../api/query'
import { useGsapMotion } from '../../composables/useGsapMotion'
import { useAuthStore } from '../../stores/auth'
import { roleCodesLabel } from '../../utils/enumLabels'

interface LocalMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  taskId?: string
  status?: string
  queryResult?: import('../../api/query').QueryTaskResult
}

interface LocalSession {
  id: string
  datasourceId: number
  title: string
  updatedAt: string
  messages: LocalMessage[]
  conversationId?: number
}

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

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const errorMessage = ref('')
const datasources = ref<UserDatasourceItem[]>([])
const selectedId = ref<number>()
const activeSessionId = ref<string>()
const question = ref('')
const keyword = ref('')
const isQuerying = ref(false)
const drawerVisible = ref(false)
const sessions = reactive<LocalSession[]>([])
const questionInputRef = ref<HTMLTextAreaElement>()
const workspaceRef = ref<HTMLElement | null>(null)
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(workspaceRef)

const permissions = computed(() => auth.currentUser?.permissions || auth.user?.permissions || [])
const canEnterAdmin = computed(() => permissions.value.includes('*') || adminPermissionCodes.some((code) => permissions.value.includes(code)))
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => roleCodesLabel(auth.currentUser?.roles || auth.user?.roles, '普通用户'))
const selectedDatasource = computed(() => datasources.value.find((item) => item.id === selectedId.value))
const exampleQuestions = [
  '统计最近30天订单金额趋势',
  '找出销售额最高的10个客户',
  '查看库存低于安全线的商品',
  '按部门汇总本月费用',
]
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
const latestResult = computed(() => {
  const msgs = activeMessages.value
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role === 'assistant' && msgs[i].queryResult) {
      return msgs[i].queryResult
    }
  }
  return null
})
const resultTab = ref<'table' | 'sql' | 'chart'>('table')
const chartRef = ref<HTMLDivElement | null>(null)
const chartType = ref<'bar' | 'line' | 'pie'>('bar')
let chartInstance: echarts.ECharts | null = null

function renderChart() {
  if (!chartRef.value || !latestResult.value?.chartConfig) return
  if (chartInstance) chartInstance.dispose()
  chartInstance = echarts.init(chartRef.value)
  try {
    const option = JSON.parse(JSON.stringify(latestResult.value.chartConfig))
    if (option.series && option.series.length > 0) {
      option.series[0].type = chartType.value
    }
    chartInstance.setOption(option)
  } catch {
    chartInstance.dispose()
    chartInstance = null
  }
}

function switchChartType(type: 'bar' | 'line' | 'pie') {
  chartType.value = type
  renderChart()
}

function exportCsv() {
  const result = latestResult.value
  if (!result?.data?.length || !result?.columns?.length) return
  const escapeCsvField = (val: string) => {
    if (val.includes(',') || val.includes('"') || val.includes('\n')) {
      return `"${val.replace(/"/g, '""')}"`
    }
    return val
  }
  const headers = result.columns.map(c => escapeCsvField(c.comment || c.name))
  const keys = result.columns.map(c => c.name)
  const rows = result.data.map(row => keys.map(k => escapeCsvField(String(row[k] ?? ''))).join(','))
  const csv = [headers.join(','), ...rows].join('\n')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `query_result_${Date.now()}.csv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('CSV 导出成功')
}

function exportPng() {
  if (!chartInstance) return
  const url = chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
  const a = document.createElement('a')
  a.href = url
  a.download = `chart_${Date.now()}.png`
  a.click()
  ElMessage.success('图表 PNG 导出成功')
}

async function handleFeedback(type: 'LIKE' | 'DISLIKE') {
  const result = latestResult.value
  if (!result?.taskId) return
  try {
    await submitQueryFeedback(result.taskId, type)
    ElMessage.success(type === 'LIKE' ? '感谢您的肯定' : '已收到反馈，我们会持续改进')
  } catch {
    ElMessage.error('反馈提交失败')
  }
}

onUnmounted(() => {
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})

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
        content: '已进入当前数据源的对话空间。你可以直接用中文描述想查的数据，例如"上月销售额最高的10个产品"。',
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
  revealAfterTick('.workspace-brief, .example-strip, .message-item, .result-preview', {
    y: 14,
    stagger: 0.04,
  })
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

function applyExample(text: string) {
  if (!selectedId.value) return
  question.value = text
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

async function sendQuestion() {
  const text = question.value.trim()
  if (!selectedId.value || !text || isQuerying.value) return
  isQuerying.value = true

  const session = activeSession.value || createSession(selectedId.value)
  const now = new Date().toISOString()

  // 添加用户消息
  session.messages.push({
    id: `user-${Date.now()}`,
    role: 'user',
    content: text,
    createdAt: now,
  })
  await animateNewMessages()

  // 添加加载中的助手消息
  const assistantMsgId = `assistant-${Date.now() + 1}`
  session.messages.push({
    id: assistantMsgId,
    role: 'assistant',
    content: '正在查询中...',
    createdAt: now,
    status: 'loading',
  })
  await animateNewMessages()

  if (session.title === '新的对话') {
    session.title = text.length > 20 ? `${text.slice(0, 20)}...` : text
  }
  session.updatedAt = now
  question.value = ''
  focusQuestionInput()

  try {
    // 提交查询到后端，传入 conversationId 串联多轮对话
    const askResult = await submitQuery({
      datasourceId: selectedId.value,
      question: text,
      conversationId: session.conversationId,
    })
    const taskId = askResult.data.taskId
    // 保存后端返回的 conversationId
    session.conversationId = askResult.data.conversationId

    // 轮询任务结果（最多 60 次 × 2 秒 = 120 秒，与后端超时对齐）
    const result = await pollTaskResult(taskId)
    const assistantMsg = session.messages.find((m) => m.id === assistantMsgId)
    if (assistantMsg) {
      assistantMsg.taskId = taskId
      assistantMsg.status = result.status
      assistantMsg.queryResult = result
      if (result.status === 'COMPLETED') {
        assistantMsg.content = result.sqlExplanation || '查询完成'
      } else if (result.status === 'TIMEOUT') {
        assistantMsg.content = '查询仍在执行中，可稍后刷新查看结果'
      } else {
        assistantMsg.content = result.errorMessage || '查询失败，请稍后重试'
      }
      await animateMessageUpdate(assistantMsgId)
    }
  } catch (error: unknown) {
    const assistantMsg = session.messages.find((m) => m.id === assistantMsgId)
    if (assistantMsg) {
      assistantMsg.status = 'error'
      assistantMsg.content = extractError(error, '查询提交失败，请检查网络连接')
      await animateMessageUpdate(assistantMsgId)
    }
  } finally {
    isQuerying.value = false
  }
}

async function animateNewMessages() {
  await nextTick()
  const lastMessage = workspaceRef.value?.querySelector('.message-item:last-of-type')
  if (lastMessage) {
    lift(lastMessage, { y: 14, duration: 0.26 })
  }
}

async function animateMessageUpdate(messageId: string) {
  await nextTick()
  const assistantBubble = workspaceRef.value?.querySelector(`[data-message-id="${messageId}"] .message-bubble`)
  if (assistantBubble) {
    lift(assistantBubble, { y: 6, scale: 1, duration: 0.22 })
  }
}

async function pollTaskResult(taskId: string, maxAttempts = 60, intervalMs = 2000) {
  for (let i = 0; i < maxAttempts; i++) {
    const res = await getTaskResult(taskId)
    const task = res.data
    if (task.status !== 'PROCESSING') {
      return task
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }
  return { status: 'TIMEOUT', errorMessage: '查询仍在执行中，可稍后从历史任务查看结果' } as any
}

function extractError(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const msg = (error as any).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  return fallback
}

function handleEnter(event: KeyboardEvent) {
  if (event.shiftKey || isQuerying.value) return
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

function handleUserCommand(command: string) {
  drawerVisible.value = false
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

onMounted(() => {
  withContext(() => {
    reveal('.query-brand, .sidebar-block, .query-topbar, .chat-surface, .chat-composer', {
      y: 16,
      stagger: 0.045,
    })
  })
  fetchDatasources()
})

watch(
  () => datasources.value.length,
  () => {
    revealAfterTick('.datasource-row', {
      y: 8,
      duration: 0.28,
      stagger: 0.025,
    })
  },
)

watch(
  () => datasourceSessions.value.length,
  () => {
    revealAfterTick('.history-row', {
      y: 8,
      duration: 0.24,
      stagger: 0.022,
    })
  },
)

watch(resultTab, () => {
  nextTick(() => {
    const resultBody = workspaceRef.value?.querySelector('.result-preview > div:not(.result-tabs)')
    if (resultBody) {
      lift(resultBody, { y: 6, duration: 0.22, scale: 1 })
    }
    if (resultTab.value === 'chart') {
      renderChart()
    }
  })
})
</script>

<template>
  <main ref="workspaceRef" class="query-workspace post-login-page">
    <aside class="query-sidebar">
      <RouterLink class="query-brand" to="/query" aria-label="DataOcean 智能问答">
        <span>DO</span>
        <div>
          <strong>DataOcean</strong>
          <small>智能问答</small>
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

        <button class="query-user" type="button" @click="drawerVisible = true">
          <span>{{ displayName.slice(0, 1) }}</span>
          <div>
            <strong>{{ displayName }}</strong>
            <small>{{ roleText }}</small>
          </div>
        </button>
      </header>

      <section class="chat-surface">
        <div v-if="!selectedId" class="empty-chat">
          <Database :size="38" />
          <h2>先选择一个数据源</h2>
          <p>每个数据源都有独立的会话历史，选择后再开始提问，避免跨库上下文污染。</p>
        </div>

        <div v-else class="message-list">
          <section class="workspace-brief">
            <div class="brief-main">
              <span class="brief-kicker">当前上下文</span>
              <h2>{{ selectedDatasource?.databaseName || selectedDatasource?.name }}</h2>
              <p>{{ selectedDatasource?.description || '当前会话限定在此数据源。' }}</p>
            </div>
            <div class="brief-metrics">
              <span class="metric-chip"><Database :size="14" />{{ datasources.length }} 个可用数据源</span>
              <span class="metric-chip"><History :size="14" />{{ datasourceSessions.length }} 个当前库会话</span>
            </div>
          </section>

          <section class="example-strip" aria-label="示例问题">
            <button
              v-for="item in exampleQuestions"
              :key="item"
              type="button"
              @click="applyExample(item)"
            >
              {{ item }}
            </button>
          </section>

          <article
            v-for="message in activeMessages"
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
            </div>
          </article>

          <section class="result-preview">
            <div class="result-tabs">
              <span :class="{ active: resultTab === 'table' }" @click="resultTab = 'table'"><ListChecks :size="14" />表格结果</span>
              <span :class="{ active: resultTab === 'sql' }" @click="resultTab = 'sql'"><MessageSquareText :size="14" />SQL</span>
              <span :class="{ active: resultTab === 'chart' }" @click="resultTab = 'chart'"><BarChart3 :size="14" />图表</span>
            </div>

            <div v-if="!latestResult" class="result-empty">
              <strong>暂无查询结果</strong>
            </div>

            <div v-else-if="resultTab === 'table'" class="result-table-wrap">
              <div v-if="latestResult.data && latestResult.data.length" class="result-meta">
                <small>共 {{ latestResult.rowCount || latestResult.data.length }} 行 · 耗时 {{ latestResult.totalTimeMs }}ms</small>
                <small v-if="latestResult.usedTables?.length">使用表：{{ latestResult.usedTables.join(', ') }}</small>
              </div>
              <el-table v-if="latestResult.data && latestResult.data.length" :data="latestResult.data" border stripe max-height="320" size="small">
                <el-table-column v-for="col in (latestResult.columns || [])" :key="col.name" :prop="col.name" :label="col.comment || col.name" min-width="120" />
              </el-table>
              <div v-else class="result-empty"><strong>查询完成但无数据返回</strong></div>
            </div>

            <div v-else-if="resultTab === 'sql'" class="result-sql-wrap">
              <pre v-if="latestResult.sql" class="sql-block">{{ latestResult.sql }}</pre>
              <p v-if="latestResult.sqlExplanation" class="sql-explanation">{{ latestResult.sqlExplanation }}</p>
              <div v-if="!latestResult.sql" class="result-empty"><strong>无 SQL</strong></div>
            </div>

            <div v-else-if="resultTab === 'chart'" class="result-chart-wrap">
              <div v-if="latestResult.chartConfig" class="chart-toolbar">
                <div class="chart-type-switcher">
                  <button :class="{ active: chartType === 'bar' }" @click="switchChartType('bar')">柱状图</button>
                  <button :class="{ active: chartType === 'line' }" @click="switchChartType('line')">折线图</button>
                  <button :class="{ active: chartType === 'pie' }" @click="switchChartType('pie')">饼图</button>
                </div>
                <button class="export-btn" @click="exportPng" :disabled="latestResult.canExport === false"><Download :size="14" />导出 PNG</button>
              </div>
              <div v-if="latestResult.chartConfig" ref="chartRef" class="chart-container"></div>
              <div v-else class="result-empty"><strong>无图表数据</strong></div>
            </div>

            <div v-if="latestResult" class="result-actions">
              <button class="export-btn" @click="exportCsv" :disabled="!latestResult.data?.length || latestResult.canExport === false">
                <Download :size="14" />导出 CSV
              </button>
              <div class="feedback-btns">
                <button class="feedback-btn like" @click="handleFeedback('LIKE')" title="结果准确">
                  <ThumbsUp :size="15" />
                </button>
                <button class="feedback-btn dislike" @click="handleFeedback('DISLIKE')" title="结果有误">
                  <ThumbsDown :size="15" />
                </button>
              </div>
            </div>

            <div v-if="latestResult?.suggestedQuestions?.length" class="suggested-questions">
              <small>推荐追问：</small>
              <button v-for="q in latestResult.suggestedQuestions" :key="q" type="button" @click="applyExample(q)">{{ q }}</button>
            </div>
          </section>
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
        <button type="button" :disabled="!selectedId || !question.trim() || isQuerying" @click="sendQuestion">
          <SendHorizontal :size="18" />
          <span>{{ isQuerying ? '查询中...' : '发送' }}</span>
        </button>
      </footer>
    </section>

    <el-drawer v-model="drawerVisible" direction="rtl" size="280px" :show-close="false">
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
  width: min(920px, 100%);
  display: grid;
  gap: 14px;
  margin: 0 auto;
}

.workspace-brief {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  align-items: start;
  padding: 18px;
  border: 1px solid var(--do-line);
  border-radius: 10px;
  background: var(--do-surface);
  box-shadow: var(--do-shadow);
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
  box-shadow: var(--do-shadow);
  overflow: hidden;
}

.result-tabs {
  height: 42px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 10px;
  border-bottom: 1px solid var(--do-line);
  background: #f8fafc;
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

@media (max-width: 1180px) {
  .query-workspace {
    min-width: 1120px;
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
