/**
 * useQuerySession — 会话状态管理 composable
 * 管理会话列表、数据源选择、历史会话加载等核心状态
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  iamS1DeleteConversation,
  iamS1GetDatasourceReadiness,
  iamS1ListConversationMessages,
  iamS1ListConversations,
  listIamS1QueryResourceDatasources,
  type IamS1ConversationMessageItem,
  type IamS1DatasourceRef,
  type IamS1QueryTaskResult,
} from '../api/iamS1'
import type { DatasourceReadiness } from '../api/admin/datasource'
import { parseStoredQueryResult } from '../utils/queryResult'

/** 单条消息 */
export interface LocalMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  taskId?: string
  status?: string
  queryResult?: IamS1QueryTaskResult
  originalQuestion?: string
}

/** 会话 */
export interface LocalSession {
  id: string
  datasourceId: number
  title: string
  updatedAt: string
  messages: LocalMessage[]
  conversationId?: number
}

export function useQuerySession() {
  // ---- 状态 ----
  const loading = ref(false)
  const readinessLoading = ref(false)
  const errorMessage = ref('')
  const datasources = ref<IamS1DatasourceRef[]>([])
  const readinessMap = ref<Record<number, DatasourceReadiness>>({})
  const selectedId = ref<number>()
  const activeSessionId = ref<string>()
  const keyword = ref('')
  const drawerVisible = ref(false)
  const sessions = reactive<LocalSession[]>([])
  const loadedDatasourceIds = ref<Set<number>>(new Set())
  let datasourceSelectionRequest = 0

  // ---- Computed ----
  const selectedDatasource = computed(() => datasources.value.find((item) => item.id === selectedId.value))
  const selectedReadiness = computed(() => selectedId.value ? readinessMap.value[selectedId.value] : undefined)
  const canAskSelectedDatasource = computed(() => selectedReadiness.value?.askable === true)
  const selectedBlockReason = computed(() => selectedReadiness.value?.blockReasons?.[0])
  const askableDatasourceCount = computed(() =>
    datasources.value.filter((item) => readinessMap.value[item.id]?.askable === true).length,
  )
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

  // ---- 辅助函数 ----
  function createSession(datasourceId: number, title = '新的对话') {
    const now = new Date().toISOString()
    const session: LocalSession = {
      id: `local-${datasourceId}-${Date.now()}`,
      datasourceId,
      title,
      updatedAt: now,
      messages: [],
    }
    sessions.unshift(session)
    activeSessionId.value = session.id
    return session
  }

  function toLocalMessage(message: IamS1ConversationMessageItem): LocalMessage {
    const result = message.role === 'assistant' ? parseStoredQueryResult(message) : undefined
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
    const res = await iamS1ListConversationMessages(session.conversationId, { page: 1, pageSize: 80 })
    session.messages = res.data.map(toLocalMessage)
    let latestQuestion = ''
    session.messages.forEach((message) => {
      if (message.role === 'user') {
        latestQuestion = message.content
      } else if (!message.originalQuestion) {
        message.originalQuestion = message.queryResult?.question || latestQuestion || undefined
      }
    })
  }

  async function loadRemoteSessions(datasourceId: number, activateFirst = true) {
    if (loadedDatasourceIds.value.has(datasourceId)) return
    const res = await iamS1ListConversations(datasourceId)
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

  async function selectDatasource(id: number, callbacks?: {
    afterSelect?: () => void
  }) {
    const requestId = ++datasourceSelectionRequest
    const changed = selectedId.value !== id
    if (changed) {
      keyword.value = ''
      // 先解除旧数据源的活动会话，避免异步加载期间短暂显示上一数据源的消息。
      activeSessionId.value = undefined
    }
    selectedId.value = id
    try {
      await loadRemoteSessions(id)
    } catch {
      if (requestId !== datasourceSelectionRequest) return
      ensureSession(id)
      ElMessage.warning('历史会话加载失败，已创建本地临时会话')
    }
    if (requestId !== datasourceSelectionRequest) return
    if (!activeSession.value || activeSession.value.datasourceId !== id) {
      ensureSession(id)
    }
    callbacks?.afterSelect?.()
  }

  function startNewSession(callbacks?: {
    focusQuestionInput?: () => void
  }) {
    if (!selectedId.value) {
      ElMessage.warning('请先选择数据源')
      return
    }
    if (!canAskSelectedDatasource.value) {
      ElMessage.warning(selectedBlockReason.value?.message || '当前数据源暂未达到可询问状态')
      return
    }
    createSession(selectedId.value)
    callbacks?.focusQuestionInput?.()
  }

  async function selectSession(sessionId: string, callbacks?: {
    focusQuestionInput?: () => void
  }) {
    const session = sessions.find((item) => item.id === sessionId)
    if (!session) return
    selectedId.value = session.datasourceId
    activeSessionId.value = session.id
    if (!session.messages.length) {
      try {
        await hydrateSessionMessages(session)
      } catch {
        ElMessage.error('会话消息加载失败')
      }
    }
    callbacks?.focusQuestionInput?.()
  }

  async function removeSession(session: LocalSession) {
    try {
      await ElMessageBox.confirm(`确定删除会话「${session.title}」吗？`, '删除会话', {
        type: 'warning',
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
      })
      if (session.conversationId) {
        await iamS1DeleteConversation(session.conversationId)
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

  async function fetchDatasources(callbacks?: {
    afterSelect?: () => void
    focusQuestionInput?: () => void
    autoSelect?: boolean
  }) {
    loading.value = true
    errorMessage.value = ''
    try {
      const result = await listIamS1QueryResourceDatasources('QUERY')
      datasources.value = result.data
      await fetchDatasourceReadiness(result.data)
      const selectedStillAccessible = Boolean(selectedId.value && result.data.some((item) => item.id === selectedId.value))
      if (!selectedStillAccessible) {
        selectedId.value = undefined
        activeSessionId.value = undefined
      }
      if (callbacks?.autoSelect !== false && result.data.length && !selectedStillAccessible) {
        const firstAskable = result.data.find((item) => readinessMap.value[item.id]?.askable === true)
        await selectDatasource((firstAskable || result.data[0]).id, {
          afterSelect: callbacks?.afterSelect,
        })
        callbacks?.focusQuestionInput?.()
      }
      if (!result.data.length) {
        selectedId.value = undefined
        activeSessionId.value = undefined
      }
    } catch (error: unknown) {
      datasources.value = []
      readinessMap.value = {}
      selectedId.value = undefined
      activeSessionId.value = undefined
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

  async function fetchDatasourceReadiness(items: IamS1DatasourceRef[]) {
    readinessLoading.value = true
    try {
      const entries = await Promise.all(
        items.map(async (item) => {
          try {
            const result = await iamS1GetDatasourceReadiness(item.id)
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

  return {
    // 状态
    loading,
    readinessLoading,
    errorMessage,
    datasources,
    readinessMap,
    selectedId,
    activeSessionId,
    keyword,
    drawerVisible,
    sessions,
    loadedDatasourceIds,
    // Computed
    selectedDatasource,
    selectedReadiness,
    canAskSelectedDatasource,
    selectedBlockReason,
    askableDatasourceCount,
    datasourceSessions,
    activeSession,
    activeMessages,
    // 方法
    createSession,
    loadRemoteSessions,
    ensureSession,
    selectDatasource,
    startNewSession,
    selectSession,
    removeSession,
    fetchDatasources,
    fetchDatasourceReadiness,
    hydrateSessionMessages,
  }
}
