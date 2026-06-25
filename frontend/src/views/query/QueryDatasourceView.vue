<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  BarChart3,
  BookOpen,
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
  ShieldAlert,
  ShieldCheck,
  ThumbsUp,
  ThumbsDown,
  Trash2,
  UserCog,
  UserRound,
  X,
} from 'lucide-vue-next'
import {
  getMyDatasourceReadiness,
  listMyDatasources,
  type DatasourceReadiness,
  type UserDatasourceItem,
} from '../../api/datasource'
import {
  submitQuery,
  getTaskResult,
  cancelTask,
  submitQueryFeedback,
  listConversations,
  listConversationMessages,
  deleteConversation,
  type ConversationMessageItem,
} from '../../api/query'
import { useGsapMotion } from '../../composables/useGsapMotion'
import { useAuthStore } from '../../stores/auth'
import { roleCodesLabel } from '../../utils/enumLabels'
import ChartContainer from '../../components/chart/ChartContainer.vue'

interface LocalMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  taskId?: string
  status?: string
  queryResult?: import('../../api/query').QueryTaskResult
  originalQuestion?: string  // 用于重试查询
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
const readinessLoading = ref(false)
const showGuideBanner = ref(!localStorage.getItem('do-query-guide-dismissed'))

function dismissGuideBanner() {
  showGuideBanner.value = false
  localStorage.setItem('do-query-guide-dismissed', '1')
}

const errorMessage = ref('')
const datasources = ref<UserDatasourceItem[]>([])
const readinessMap = ref<Record<number, DatasourceReadiness>>({})
const selectedId = ref<number>()
const activeSessionId = ref<string>()
const question = ref('')
const keyword = ref('')
const isQuerying = ref(false)
const currentTaskId = ref<string>()
const pollAbortController = ref<AbortController>()
const drawerVisible = ref(false)
const sessions = reactive<LocalSession[]>([])
const loadedDatasourceIds = ref<Set<number>>(new Set())
const questionInputRef = ref<HTMLTextAreaElement>()
const workspaceRef = ref<HTMLElement | null>(null)
const { lift, reveal, revealAfterTick, withContext } = useGsapMotion(workspaceRef)

const permissions = computed(() => auth.currentUser?.permissions || auth.user?.permissions || [])
const canEnterAdmin = computed(() => permissions.value.includes('*') || adminPermissionCodes.some((code) => permissions.value.includes(code)))
const displayName = computed(() => auth.currentUser?.realName || auth.user?.realName || auth.user?.username || '用户')
const roleText = computed(() => roleCodesLabel(auth.currentUser?.roles || auth.user?.roles, '普通用户'))
const selectedDatasource = computed(() => datasources.value.find((item) => item.id === selectedId.value))
const selectedReadiness = computed(() => selectedId.value ? readinessMap.value[selectedId.value] : undefined)
const canAskSelectedDatasource = computed(() => selectedReadiness.value?.askable === true)
const selectedBlockReason = computed(() => selectedReadiness.value?.blockReasons?.[0])
const askableDatasourceCount = computed(() =>
  datasources.value.filter((item) => readinessMap.value[item.id]?.askable === true).length,
)
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
const resultTab = ref<'table' | 'sql' | 'chart' | 'trust'>('table')
const chartType = ref<'bar' | 'line' | 'pie'>('bar')

const agentNodes = [
  { key: 'query_rewriter', label: '理解问题' },
  { key: 'schema_retriever', label: '召回知识' },
  { key: 'sql_generator', label: '生成 SQL' },
  { key: 'sql_validator', label: '安全校验' },
  { key: 'sql_executor', label: '执行查询' },
  { key: 'data_visualizer', label: '生成图表' },
]

const agentProgress = computed(() => {
  const result = latestResult.value
  if (!result) return []
  const currentIndex = agentNodes.findIndex((node) => node.key === result.progressNode)
  return agentNodes.map((node, index) => {
    let status: 'done' | 'active' | 'pending' | 'failed' = 'pending'
    if (result.status === 'COMPLETED') {
      status = 'done'
    } else if (result.status === 'FAILED' || result.status === 'TIMEOUT' || result.status === 'CANCELLED') {
      status = currentIndex >= 0 && index === currentIndex ? 'failed' : index < currentIndex ? 'done' : 'pending'
    } else if (currentIndex >= 0) {
      status = index < currentIndex ? 'done' : index === currentIndex ? 'active' : 'pending'
    } else if (index === 0 && result.status === 'PROCESSING') {
      status = 'active'
    }
    return { ...node, status }
  })
})

const isLatestProcessing = computed(() => latestResult.value?.status === 'PROCESSING')

const trustSummary = computed(() => {
  const result = latestResult.value
  if (!result) return []
  const maskedCount = result.maskedFields ? Object.keys(result.maskedFields).length : 0
  return [
    {
      label: '改写问题',
      value: result.rewrittenQuery || result.question || '未返回改写结果',
      muted: !result.rewrittenQuery,
    },
    {
      label: '召回表',
      value: result.usedTables?.length ? result.usedTables.join(', ') : '未返回表级依据',
      muted: !result.usedTables?.length,
    },
    {
      label: '使用字段',
      value: result.usedColumns?.length ? result.usedColumns.join(', ') : '未返回字段级依据',
      muted: !result.usedColumns?.length,
    },
    {
      label: '权限与脱敏',
      value: maskedCount ? `已标记 ${maskedCount} 个脱敏字段` : '当前结果未标记脱敏字段',
      muted: !maskedCount,
    },
    {
      label: 'Prompt 版本',
      value: result.promptVersions?.length ? `${result.promptVersions.length} 个模板参与生成` : '未返回版本追踪',
      muted: !result.promptVersions?.length,
    },
    {
      label: '重试次数',
      value: `${result.retryCount ?? 0} 次`,
      muted: false,
    },
  ]
})

/** 结果表格分页（前端分页） */
const tablePage = ref(1)
const tablePageSize = 50
const pagedTableData = computed(() => {
  const data = latestResult.value?.data
  if (!data || !data.length) return []
  const start = (tablePage.value - 1) * tablePageSize
  return data.slice(start, start + tablePageSize)
})
// 结果变化时重置分页到第一页
watch(latestResult, () => { tablePage.value = 1 })

/** 根据当前图表类型计算 ECharts option */
const chartOption = computed(() => {
  if (!latestResult.value?.chartConfig) return null
  try {
    const option = JSON.parse(JSON.stringify(latestResult.value.chartConfig))
    if (option.series && option.series.length > 0) {
      option.series[0].type = chartType.value
    }
    return option
  } catch {
    return null
  }
})

function switchChartType(type: 'bar' | 'line' | 'pie') {
  chartType.value = type
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
  // PNG 导出暂不可用（图表由 ChartContainer 组件管理）
  ElMessage.info('PNG 导出功能开发中')
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

function parseStoredResult(message: ConversationMessageItem) {
  if (!message.metadata) return undefined
  try {
    const result = JSON.parse(message.metadata)
    if (message.taskId && !result.taskId) {
      result.taskId = message.taskId
    }
    return result
  } catch {
    return undefined
  }
}

function toLocalMessage(message: ConversationMessageItem): LocalMessage {
  const result = message.role === 'assistant' ? parseStoredResult(message) : undefined
  return {
    id: `remote-${message.id}`,
    role: message.role,
    content: message.content,
    createdAt: message.createdAt,
    taskId: message.taskId,
    status: result?.status,
    queryResult: result,
  }
}

async function hydrateSessionMessages(session: LocalSession) {
  if (!session.conversationId) return
  const res = await listConversationMessages(session.conversationId, { page: 1, pageSize: 80 })
  session.messages = res.data.map(toLocalMessage)
}

async function loadRemoteSessions(datasourceId: number, activateFirst = true) {
  if (loadedDatasourceIds.value.has(datasourceId)) return
  const res = await listConversations(datasourceId)
  const remoteSessions: LocalSession[] = res.data.map((item) => ({
    id: `remote-${item.id}`,
    datasourceId: item.datasourceId,
    title: item.title || '历史会话',
    updatedAt: item.updatedAt || item.createdAt,
    messages: [],
    conversationId: item.id,
  }))
  sessions.push(...remoteSessions.filter((remote) => !sessions.some((local) => local.conversationId === remote.conversationId)))
  loadedDatasourceIds.value = new Set([...loadedDatasourceIds.value, datasourceId])

  const first = datasourceSessions.value[0]
  if (activateFirst && first) {
    activeSessionId.value = first.id
    if (!first.messages.length) {
      await hydrateSessionMessages(first)
    }
  }
}

function ensureSession(datasourceId: number) {
  const existing = sessions.find((session) => session.datasourceId === datasourceId)
  if (existing) {
    activeSessionId.value = existing.id
    return existing
  }
  return createSession(datasourceId)
}

async function selectDatasource(id: number) {
  if (selectedId.value !== id) {
    question.value = ''
    keyword.value = ''
  }
  selectedId.value = id
  try {
    await loadRemoteSessions(id)
  } catch {
    ensureSession(id)
    ElMessage.warning('历史会话加载失败，已创建本地临时会话')
  }
  if (!activeSession.value || activeSession.value.datasourceId !== id) {
    ensureSession(id)
  }
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
  if (!canAskSelectedDatasource.value) {
    ElMessage.warning(selectedBlockReason.value?.message || '当前数据源暂未达到可询问状态')
    return
  }
  question.value = ''
  createSession(selectedId.value)
  focusQuestionInput()
}

function applyExample(text: string) {
  if (!selectedId.value || !canAskSelectedDatasource.value) return
  question.value = text
  focusQuestionInput()
}

async function selectSession(sessionId: string) {
  const session = sessions.find((item) => item.id === sessionId)
  if (!session) return
  selectedId.value = session.datasourceId
  activeSessionId.value = session.id
  question.value = ''
  if (!session.messages.length) {
    try {
      await hydrateSessionMessages(session)
    } catch {
      ElMessage.error('会话消息加载失败')
    }
  }
  focusQuestionInput()
}

async function removeSession(session: LocalSession) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${session.title}」吗？`, '删除会话', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
    })
    if (session.conversationId) {
      await deleteConversation(session.conversationId)
    }
    const index = sessions.findIndex((item) => item.id === session.id)
    if (index >= 0) sessions.splice(index, 1)
    if (activeSessionId.value === session.id) {
      const nextSession = datasourceSessions.value[0]
      activeSessionId.value = nextSession?.id
      if (nextSession && !nextSession.messages.length) {
        await hydrateSessionMessages(nextSession)
      }
    }
    ElMessage.success('会话已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    const msg = typeof error === 'object' && error !== null && 'response' in error
      ? (error as { response?: { data?: { message?: string } } }).response?.data?.message
      : undefined
    ElMessage.error(msg || '会话删除失败')
  }
}

async function sendQuestion() {
  const text = question.value.trim()
  if (!selectedId.value || !text || isQuerying.value) return
  if (!canAskSelectedDatasource.value) {
    ElMessage.warning(selectedBlockReason.value?.message || '当前数据源暂未达到可询问状态')
    return
  }
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
    originalQuestion: text,  // 保存原始问题，用于重试查询
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

    // 设置取消控制器和当前 taskId
    currentTaskId.value = taskId
    const abortCtrl = new AbortController()
    pollAbortController.value = abortCtrl

    // 轮询任务结果（最多 60 次 × 2 秒 = 120 秒，与后端超时对齐）
    // onProgress 回调把后端实时回写的阶段进度展示到加载中的助手消息上
    const result = await pollTaskResult(taskId, abortCtrl.signal, 60, 2000, (task) => {
      const loadingMsg = session.messages.find((m) => m.id === assistantMsgId)
      if (loadingMsg && loadingMsg.status === 'loading') {
        loadingMsg.queryResult = task
        loadingMsg.taskId = taskId
        if (task.progressMessage) {
          loadingMsg.content = `${task.progressMessage}...`
        }
      }
    })
    const assistantMsg = session.messages.find((m) => m.id === assistantMsgId)
    if (assistantMsg) {
      assistantMsg.taskId = taskId
      assistantMsg.status = result.status
      assistantMsg.queryResult = result
      if (result.status === 'COMPLETED') {
        assistantMsg.content = buildCompletionMessage(result)
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
    currentTaskId.value = undefined
    pollAbortController.value = undefined
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

// 轮询配置常量
const POLL_MAX_CONSECUTIVE_ERRORS = 3  // 连续失败阈值，单次网络抖动不应终止整个轮询

async function pollTaskResult(
  taskId: string,
  signal?: AbortSignal,
  maxAttempts = 60,
  intervalMs = 2000,
  onProgress?: (task: import('../../api/query').QueryTaskResult) => void,
) {
  let consecutiveErrors = 0

  for (let i = 0; i < maxAttempts; i++) {
    if (signal?.aborted) {
      return { status: 'CANCELLED', errorMessage: '查询已取消' } as any
    }

    try {
      const res = await getTaskResult(taskId)
      const task = res.data
      // 请求成功，重置连续失败计数
      consecutiveErrors = 0

      if (task.status !== 'PROCESSING') {
        return task
      }
      // 仍在处理中：把后端实时回写的阶段进度回调出去展示
      if (onProgress) {
        onProgress(task)
      }
    } catch (error) {
      // 安全修复：单次异常不终止轮询，连续失败达到阈值后再失败
      consecutiveErrors++
      console.warn(`轮询任务结果失败 (连续第 ${consecutiveErrors} 次) taskId=${taskId}`, error)
      if (consecutiveErrors >= POLL_MAX_CONSECUTIVE_ERRORS) {
        return { status: 'FAILED', errorMessage: `网络连接异常，连续 ${POLL_MAX_CONSECUTIVE_ERRORS} 次请求失败` } as any
      }
      // 未达到阈值，继续下一轮轮询
    }

    await new Promise((resolve, reject) => {
      const timer = setTimeout(resolve, intervalMs)
      signal?.addEventListener('abort', () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }, { once: true })
    }).catch(() => null)
    if (signal?.aborted) {
      return { status: 'CANCELLED', errorMessage: '查询已取消' } as any
    }
  }
  return { status: 'TIMEOUT', errorMessage: '查询仍在执行中，可稍后从历史任务查看结果' } as any
}

async function cancelCurrentQuery() {
  if (!currentTaskId.value || !isQuerying.value) return
  pollAbortController.value?.abort()
  try {
    await cancelTask(currentTaskId.value)
  } catch {
    // 取消请求失败不影响 UI 状态恢复
  }
}

/**
 * 构建查询完成消息
 * 处理降级状态提示
 */
function buildCompletionMessage(result: any): string {
  const degradeNotice = result.degraded
    ? '\n⚠️ 知识库暂时不可用，召回精度可能降低'
    : ''
  return (result.sqlExplanation || '查询完成') + degradeNotice
}

/**
 * 重试查询：使用原始问题重新提交
 */
function retryQuery(originalQuestion: string) {
  if (!originalQuestion || isQuerying.value) return
  question.value = originalQuestion
  sendQuestion()
}

/**
 * 继续等待：重新轮询当前任务
 */
async function continueWaiting(taskId: string) {
  if (isQuerying.value) return
  isQuerying.value = true
  currentTaskId.value = taskId  // 设置当前任务 ID，支持取消功能

  const session = activeSession.value
  if (!session) {
    isQuerying.value = false
    currentTaskId.value = undefined
    return
  }

  // 找到对应的助手消息
  const assistantMsg = session.messages.find((m) => m.taskId === taskId)
  if (!assistantMsg) {
    isQuerying.value = false
    return
  }

  // 更新状态为 loading
  assistantMsg.status = 'loading'
  assistantMsg.content = '继续等待查询结果...'

  const abortCtrl = new AbortController()
  pollAbortController.value = abortCtrl

  try {
    const result = await pollTaskResult(taskId, abortCtrl.signal, 60, 2000, (task) => {
      if (assistantMsg.status === 'loading') {
        assistantMsg.queryResult = task
        if (task.progressMessage) {
          assistantMsg.content = `${task.progressMessage}...`
        }
      }
    })

    assistantMsg.status = result.status
    assistantMsg.queryResult = result
    if (result.status === 'COMPLETED') {
      assistantMsg.content = buildCompletionMessage(result)
    } else if (result.status === 'TIMEOUT') {
      assistantMsg.content = '查询仍在执行中，可稍后刷新查看结果'
    } else {
      assistantMsg.content = result.errorMessage || '查询失败，请稍后重试'
    }
    await animateMessageUpdate(assistantMsg.id)
  } catch (error) {
    assistantMsg.status = 'error'
    assistantMsg.content = extractError(error, '查询失败，请稍后重试')
    await animateMessageUpdate(assistantMsg.id)
  } finally {
    isQuerying.value = false
    pollAbortController.value = undefined
  }
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
    await fetchDatasourceReadiness(result.data)
    if (result.data.length && (!selectedId.value || !result.data.some((item) => item.id === selectedId.value))) {
      const firstAskable = result.data.find((item) => readinessMap.value[item.id]?.askable === true)
      await selectDatasource((firstAskable || result.data[0]).id)
    }
    if (!result.data.length) {
      selectedId.value = undefined
      activeSessionId.value = undefined
      question.value = ''
    }
  } catch (error: unknown) {
    datasources.value = []
    readinessMap.value = {}
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

async function fetchDatasourceReadiness(items: UserDatasourceItem[]) {
  readinessLoading.value = true
  try {
    const entries = await Promise.all(
      items.map(async (item) => {
        try {
          const result = await getMyDatasourceReadiness(item.id)
          return [item.id, result.data] as const
        } catch {
          return null
        }
      }),
    )
    readinessMap.value = Object.fromEntries(entries.filter(Boolean) as Array<readonly [number, DatasourceReadiness]>)
  } finally {
    readinessLoading.value = false
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
    // 图表由 ChartContainer 组件自身的 onMounted/watch(option) 负责渲染，此处无需手动触发
  })
})
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
          <button type="button" :disabled="loading || readinessLoading" aria-label="刷新数据源" @click="fetchDatasources">
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
            :class="{ active: datasource.id === selectedId, 'not-askable': readinessMap[datasource.id]?.askable === false }"
            @click="selectDatasource(datasource.id)"
          >
            <Database :size="16" />
            <span>
              <strong>{{ datasource.name }}</strong>
              <small>{{ datasource.databaseName }}</small>
              <small v-if="readinessMap[datasource.id]" class="readiness-chip">
                {{ readinessMap[datasource.id].askable ? '可询问' : readinessMap[datasource.id].stageLabel }}
              </small>
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
          <div
            v-for="session in datasourceSessions"
            :key="session.id"
            role="button"
            tabindex="0"
            class="history-row"
            :class="{ active: session.id === activeSessionId }"
            @click="selectSession(session.id)"
            @keydown.enter="selectSession(session.id)"
          >
            <History :size="15" />
            <span>
              <strong>{{ session.title }}</strong>
              <small>{{ session.messages.length }} 条消息 · {{ formatTime(session.updatedAt) }}</small>
            </span>
            <button type="button" class="history-delete" aria-label="删除会话" @click.stop="removeSession(session)">
              <Trash2 :size="14" />
            </button>
          </div>
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
              <span class="metric-chip"><Database :size="14" />{{ askableDatasourceCount }} / {{ datasources.length }} 个可询问</span>
              <span class="metric-chip"><History :size="14" />{{ datasourceSessions.length }} 个当前库会话</span>
            </div>
          </section>

          <section v-if="selectedId && !canAskSelectedDatasource" class="readiness-notice">
            <ShieldAlert :size="16" />
            <div>
              <strong>{{ selectedReadiness?.stageLabel || (readinessLoading ? '正在确认状态' : '状态确认失败') }}</strong>
              <span>{{ selectedBlockReason?.message || (readinessLoading ? '正在确认该数据源是否可询问。' : '未能确认该数据源上线状态，请刷新后重试。') }}</span>
            </div>
            <RouterLink v-if="selectedBlockReason?.actionPath && canEnterAdmin" :to="selectedBlockReason.actionPath">
              {{ selectedBlockReason.actionText || '去处理' }}
            </RouterLink>
          </section>

          <section class="example-strip" aria-label="示例问题">
            <button
              v-for="item in exampleQuestions"
              :key="item"
              type="button"
              :disabled="!canAskSelectedDatasource"
              @click="applyExample(item)"
            >
              {{ item }}
            </button>
          </section>

          <div class="query-cockpit">
            <section class="conversation-rail" aria-label="对话">
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
              <!-- TIMEOUT 状态操作按钮 -->
              <div v-if="message.role === 'assistant' && message.status === 'TIMEOUT'" class="message-actions">
                <button class="action-btn retry" @click="retryQuery(message.originalQuestion || '')" :disabled="isQuerying">
                  <RefreshCw :size="14" />重试查询
                </button>
                <button class="action-btn wait" @click="continueWaiting(message.taskId || '')" :disabled="isQuerying">
                  <History :size="14" />继续等待
                </button>
              </div>
              <!-- 失败状态操作按钮 -->
              <div v-if="message.role === 'assistant' && (message.status === 'FAILED' || message.status === 'error')" class="message-actions">
                <button class="action-btn retry" @click="retryQuery(message.originalQuestion || '')" :disabled="isQuerying || !message.originalQuestion">
                  <RefreshCw :size="14" />重新提问
                </button>
              </div>
            </div>
          </article>
            </section>

          <aside class="result-preview result-rail" aria-label="查询结果与可信依据">
            <div class="result-tabs">
              <span :class="{ active: resultTab === 'table' }" @click="resultTab = 'table'"><ListChecks :size="14" />表格结果</span>
              <span :class="{ active: resultTab === 'sql' }" @click="resultTab = 'sql'"><MessageSquareText :size="14" />SQL</span>
              <span :class="{ active: resultTab === 'chart' }" @click="resultTab = 'chart'"><BarChart3 :size="14" />图表</span>
              <span :class="{ active: resultTab === 'trust' }" @click="resultTab = 'trust'"><ShieldCheck :size="14" />可信依据</span>
            </div>

            <div v-if="!latestResult" class="result-empty">
              <strong>暂无查询结果</strong>
            </div>

            <div v-else-if="resultTab === 'table'" class="result-table-wrap">
              <div v-if="isLatestProcessing" class="result-empty">
                <strong>{{ latestResult.progressMessage || '查询正在执行中' }}</strong>
                <span>可以切换到“可信依据”查看 Agent 当前进度。</span>
              </div>
              <div v-if="latestResult.data && latestResult.data.length" class="result-meta">
                <small>共 {{ latestResult.rowCount || latestResult.data.length }} 行 · 耗时 {{ latestResult.totalTimeMs }}ms</small>
                <small v-if="latestResult.usedTables?.length">使用表：{{ latestResult.usedTables.join(', ') }}</small>
              </div>
              <el-table v-if="!isLatestProcessing && latestResult.data && latestResult.data.length" :data="pagedTableData" border stripe max-height="320" size="small">
                <el-table-column v-for="col in (latestResult.columns || [])" :key="col.name" :prop="col.name" :label="col.comment || col.name" min-width="120" show-overflow-tooltip />
              </el-table>
              <el-pagination
                v-if="!isLatestProcessing && latestResult.data && latestResult.data.length > tablePageSize"
                v-model:current-page="tablePage"
                :page-size="tablePageSize"
                :total="latestResult.data.length"
                layout="total, prev, pager, next"
                size="small"
                style="margin-top: 8px; justify-content: flex-end;"
              />
              <div v-else-if="!isLatestProcessing" class="result-empty"><strong>查询完成但无数据返回</strong></div>
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
              <ChartContainer v-if="latestResult.chartConfig" :option="chartOption" />
              <div v-else class="result-empty"><strong>无图表数据</strong></div>
            </div>

            <div v-else-if="resultTab === 'trust'" class="trust-panel">
              <div class="agent-progress">
                <div
                  v-for="step in agentProgress"
                  :key="step.key"
                  class="agent-step"
                  :class="step.status"
                >
                  <span class="step-dot"></span>
                  <strong>{{ step.label }}</strong>
                </div>
              </div>

              <div v-if="latestResult.progressMessage" class="trust-notice">
                <ShieldCheck :size="15" />
                <span>{{ latestResult.progressMessage }}</span>
              </div>
              <div v-if="latestResult.degraded" class="trust-notice warning">
                <ShieldAlert :size="15" />
                <span>{{ latestResult.degradeNotice || '知识库暂时不可用，当前结果已按降级策略返回。' }}</span>
              </div>

              <div class="trust-grid">
                <div
                  v-for="item in trustSummary"
                  :key="item.label"
                  class="trust-card"
                  :class="{ muted: item.muted }"
                >
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </div>
              </div>
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
          </aside>
          </div>
        </div>
      </section>

      <footer class="chat-composer">
        <div v-if="selectedId && !canAskSelectedDatasource" class="composer-readiness">
          {{ selectedBlockReason?.message || (readinessLoading ? '正在确认该数据源是否可询问' : '未能确认该数据源上线状态，请刷新后重试') }}
        </div>
        <textarea
          ref="questionInputRef"
          v-model="question"
          :disabled="!selectedId || !canAskSelectedDatasource"
          rows="1"
          :placeholder="!selectedId ? '请先选择左侧数据源' : canAskSelectedDatasource ? '向当前数据源提问，例如：上个月销售额最高的10个产品' : '当前数据源暂未完成上线流程'"
          @keydown.enter="handleEnter"
        ></textarea>
        <button type="button" :disabled="!selectedId || !canAskSelectedDatasource || !question.trim() || isQuerying" @click="sendQuestion">
          <SendHorizontal :size="18" />
          <span>{{ isQuerying ? '查询中...' : '发送' }}</span>
        </button>
        <button v-if="isQuerying" type="button" class="cancel-btn" @click="cancelCurrentQuery">
          取消
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
