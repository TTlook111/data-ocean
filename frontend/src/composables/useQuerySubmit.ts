/**
 * useQuerySubmit — 查询提交和轮询 composable
 * 管理提问提交、任务轮询、取消、重试等流程
 */
import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  submitQuery,
  getTaskResult,
  cancelTask,
  type QueryTaskResult,
} from '../api/query'
import type { LocalMessage, LocalSession } from './useQuerySession'

/** 轮询配置常量 */
const POLL_MAX_CONSECUTIVE_ERRORS = 3

/** Agent 节点定义 */
const agentNodes = [
  { key: 'query_rewriter', label: '理解问题' },
  { key: 'schema_retriever', label: '召回知识' },
  { key: 'sql_generator', label: '生成 SQL' },
  { key: 'sql_validator', label: '安全校验' },
  { key: 'sql_executor', label: '执行查询' },
  { key: 'data_visualizer', label: '生成图表' },
]

export function useQuerySubmit(options: {
  selectedId: Ref<number | undefined>
  activeSession: Ref<LocalSession | undefined>
  activeMessages: Ref<LocalMessage[]>
  canAskSelectedDatasource: Ref<boolean>
  selectedBlockReason: Ref<{ message?: string } | undefined>
  createSession: (datasourceId: number) => LocalSession
  animateNewMessages?: () => Promise<void>
  animateMessageUpdate?: (messageId: string) => Promise<void>
  focusQuestionInput?: () => Promise<void>
}) {
  const {
    selectedId,
    activeSession,
    activeMessages,
    canAskSelectedDatasource,
    selectedBlockReason,
    createSession,
    animateNewMessages,
    animateMessageUpdate,
    focusQuestionInput,
  } = options

  // ---- 状态 ----
  const question = ref('')
  const isQuerying = ref(false)
  const currentTaskId = ref<string>()
  const pollAbortController = ref<AbortController>()
  const resultTab = ref<'table' | 'sql' | 'chart' | 'trust'>('table')
  const chartType = ref<'bar' | 'line' | 'pie'>('bar')

  // ---- Computed ----
  const latestResult = computed(() => {
    const msgs = activeMessages.value
    let result: QueryTaskResult | null = null
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'assistant' && msgs[i].queryResult) {
        result = msgs[i].queryResult!
        break
      }
    }
    return result
  })

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

  // ---- 辅助函数 ----
  function extractError(error: unknown, fallback: string): string {
    if (typeof error === 'object' && error !== null && 'response' in error) {
      const response = (error as { response?: { data?: { message?: string } } }).response
      const msg = response?.data?.message
      if (typeof msg === 'string') return msg
    }
    return fallback
  }

  /**
   * 构建查询完成消息，处理降级状态提示
   */
  function buildCompletionMessage(result: QueryTaskResult): string {
    const degradeNotice = result.degraded
      ? '\n⚠️ 知识库暂时不可用，召回精度可能降低'
      : ''
    return (result.sqlExplanation || '查询完成') + degradeNotice
  }

  /**
   * 轮询任务结果
   * 最多 60 次 × 2 秒 = 120 秒，与后端超时对齐
   */
  async function pollTaskResult(
    taskId: string,
    signal?: AbortSignal,
    maxAttempts = 60,
    intervalMs = 2000,
    onProgress?: (task: QueryTaskResult) => void,
  ): Promise<QueryTaskResult> {
    let consecutiveErrors = 0

    for (let i = 0; i < maxAttempts; i++) {
      if (signal?.aborted) {
        return { status: 'CANCELLED', errorMessage: '查询已取消' } as any
      }

      try {
        const res = await getTaskResult(taskId)
        const task = res.data
        consecutiveErrors = 0

        if (task.status !== 'PROCESSING') {
          return task
        }
        if (onProgress) {
          onProgress(task)
        }
      } catch (error) {
        consecutiveErrors++
        console.warn(`轮询任务结果失败 (连续第 ${consecutiveErrors} 次) taskId=${taskId}`, error)
        if (consecutiveErrors >= POLL_MAX_CONSECUTIVE_ERRORS) {
          return { status: 'FAILED', errorMessage: `网络连接异常，连续 ${POLL_MAX_CONSECUTIVE_ERRORS} 次请求失败` } as any
        }
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

  /**
   * 提交问题并轮询结果
   */
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
    await animateNewMessages?.()

    // 添加加载中的助手消息
    const assistantMsgId = `assistant-${Date.now() + 1}`
    session.messages.push({
      id: assistantMsgId,
      role: 'assistant',
      content: '正在查询中...',
      createdAt: now,
      status: 'loading',
      originalQuestion: text,
    })
    await animateNewMessages?.()

    if (session.title === '新的对话') {
      session.title = text.length > 20 ? `${text.slice(0, 20)}...` : text
    }
    session.updatedAt = now
    question.value = ''
    await focusQuestionInput?.()

    try {
      const askResult = await submitQuery({
        datasourceId: selectedId.value,
        question: text,
        conversationId: session.conversationId,
      })
      const taskId = askResult.data.taskId
      session.conversationId = askResult.data.conversationId

      currentTaskId.value = taskId
      const abortCtrl = new AbortController()
      pollAbortController.value = abortCtrl

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
        await animateMessageUpdate?.(assistantMsgId)
      }
    } catch (error: unknown) {
      const assistantMsg = session.messages.find((m) => m.id === assistantMsgId)
      if (assistantMsg) {
        assistantMsg.status = 'error'
        assistantMsg.content = extractError(error, '查询提交失败，请检查网络连接')
        await animateMessageUpdate?.(assistantMsgId)
      }
    } finally {
      isQuerying.value = false
      currentTaskId.value = undefined
      pollAbortController.value = undefined
    }
  }

  /**
   * 取消当前查询
   */
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
    currentTaskId.value = taskId

    const session = activeSession.value
    if (!session) {
      isQuerying.value = false
      currentTaskId.value = undefined
      return
    }

    const assistantMsg = session.messages.find((m) => m.taskId === taskId)
    if (!assistantMsg) {
      isQuerying.value = false
      return
    }

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
      await animateMessageUpdate?.(assistantMsg.id)
    } catch (error) {
      assistantMsg.status = 'error'
      assistantMsg.content = extractError(error, '查询失败，请稍后重试')
      await animateMessageUpdate?.(assistantMsg.id)
    } finally {
      isQuerying.value = false
      pollAbortController.value = undefined
    }
  }

  return {
    // 状态
    question,
    isQuerying,
    currentTaskId,
    pollAbortController,
    resultTab,
    chartType,
    // Computed
    latestResult,
    agentProgress,
    isLatestProcessing,
    trustSummary,
    // 方法
    sendQuestion,
    cancelCurrentQuery,
    retryQuery,
    continueWaiting,
    buildCompletionMessage,
    pollTaskResult,
    extractError,
  }
}
