import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { iamS1GetTask, iamS1ResumeTask, iamS1StreamTask } from '../api/iamS1'
import type { LocalMessage, LocalSession } from './useQuerySession'
import { isLocallyResumable, useQuerySubmit } from './useQuerySubmit'

vi.mock('../api/iamS1', () => ({
  iamS1Ask: vi.fn(),
  iamS1CancelTask: vi.fn(),
  iamS1GetTask: vi.fn(),
  iamS1ResumeTask: vi.fn(),
  iamS1StreamTask: vi.fn(),
  iamS1ViewSql: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}))

describe('useQuerySubmit recovery state', () => {
  beforeEach(() => vi.clearAllMocks())

  it('marks only local wait exhaustion as resumable, not a server TIMEOUT', () => {
    expect(isLocallyResumable({ status: 'PROCESSING', localWaitTimedOut: true })).toBe(true)
    expect(isLocallyResumable({ status: 'TIMEOUT', localWaitTimedOut: false })).toBe(false)
    expect(isLocallyResumable({ status: 'TIMEOUT' })).toBe(false)
  })

  it('keeps server PROCESSING distinct when the local poll limit is reached', async () => {
    vi.mocked(iamS1StreamTask).mockResolvedValue(undefined as never)
    vi.mocked(iamS1GetTask).mockResolvedValue({
      data: { taskId: 'task-1', status: 'PROCESSING', progressNode: 'sql_generation' },
    } as never)
    const submit = createSubmit()

    const result = await submit.pollTaskResult('task-1', undefined, 1, 1)

    expect(result.status).toBe('PROCESSING')
    expect(result.localWaitTimedOut).toBe(true)
    expect(isLocallyResumable({ status: result.status, localWaitTimedOut: result.localWaitTimedOut })).toBe(true)
  })

  it('preserves terminal server TIMEOUT as non-resumable', async () => {
    vi.mocked(iamS1StreamTask).mockResolvedValue(undefined as never)
    vi.mocked(iamS1GetTask).mockResolvedValue({
      data: { taskId: 'task-1', status: 'TIMEOUT', errorMessage: '服务端时限到达' },
    } as never)
    const submit = createSubmit()

    const result = await submit.pollTaskResult('task-1', undefined, 1, 1)

    expect(result.status).toBe('TIMEOUT')
    expect(result.localWaitTimedOut).toBeUndefined()
    expect(isLocallyResumable({ status: result.status, localWaitTimedOut: result.localWaitTimedOut })).toBe(false)
  })

  it('shows a resume failure instead of silently polling the unchanged task', async () => {
    const message: LocalMessage = {
      id: 'assistant-1',
      role: 'assistant',
      content: '本地等待已到上限',
      createdAt: '2026-09-27T00:00:00Z',
      taskId: 'task-1',
      status: 'PROCESSING',
      originalQuestion: '统计订单',
      localWaitTimedOut: true,
    }
    vi.mocked(iamS1ResumeTask).mockRejectedValue({ response: { data: { message: '恢复请求被拒绝' } } })
    vi.mocked(iamS1GetTask).mockResolvedValue({
      data: { taskId: 'task-1', status: 'PROCESSING' },
    } as never)
    const submit = createSubmit(message)

    await submit.continueWaiting('task-1')

    expect(message.status).toBe('error')
    expect(message.localWaitTimedOut).toBe(false)
    expect(message.content).toContain('恢复请求被拒绝')
    expect(iamS1GetTask).toHaveBeenCalledTimes(1)
    expect(iamS1StreamTask).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('当前任务未能恢复，可以重新提问')
  })
})

function createSubmit(message?: LocalMessage) {
  const messages = message ? [message] : []
  const session: LocalSession = {
    id: 'session-1',
    datasourceId: 1,
    title: '历史会话',
    updatedAt: '2026-09-27T00:00:00Z',
    messages,
    conversationId: 42,
  }
  return useQuerySubmit({
    selectedId: ref(1),
    activeSession: ref(session),
    activeMessages: ref(messages),
    canAskSelectedDatasource: ref(true),
    selectedBlockReason: ref(undefined),
    createSession: () => session,
  })
}
