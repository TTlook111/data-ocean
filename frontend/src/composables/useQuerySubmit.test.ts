import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { iamS1Ask, iamS1CancelTask, iamS1ExportCsv, iamS1GetTask, iamS1ResumeTask, iamS1StreamTask, iamS1SubmitFeedback, iamS1ViewSql } from '../api/iamS1'
import type { LocalMessage, LocalSession } from './useQuerySession'
import { isLocallyResumable, buildAgentProgress, useQuerySubmit } from './useQuerySubmit'
import { useQueryExport } from './useQueryExport'

vi.mock('../api/iamS1', () => ({
  iamS1Ask: vi.fn(),
  iamS1CancelTask: vi.fn(),
  iamS1GetTask: vi.fn(),
  iamS1ResumeTask: vi.fn(),
  iamS1StreamTask: vi.fn(),
  iamS1ViewSql: vi.fn(),
  iamS1ExportCsv: vi.fn(),
  iamS1SubmitFeedback: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}))

describe('useQuerySubmit recovery state', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.unstubAllGlobals())

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

  it('only marks reported live stages and never marks every terminal branch complete', () => {
    expect(buildAgentProgress({ taskId: 'done', status: 'COMPLETED' })).toEqual([])
    expect(buildAgentProgress({ taskId: 'clarify', status: 'CLARIFICATION_REQUIRED' })).toEqual([])
    expect(buildAgentProgress({ taskId: 'failed', status: 'FAILED', progressNode: 'sql_generation' })).toEqual([])
    expect(buildAgentProgress({ taskId: 'unknown', status: 'PROCESSING' })).toEqual([])
    expect(buildAgentProgress({ taskId: 'live', status: 'PROCESSING', progressNode: 'sql_generation' }).slice(0, 4)).toMatchObject([
      { key: 'rag_retrieval', status: 'done' },
      { key: 'schema_linking', status: 'done' },
      { key: 'sql_generation', status: 'active' },
      { key: 'sql_semantic_check', status: 'pending' },
    ])
  })

  it('keeps display, SQL, CSV, feedback, and table rows bound to one selected task', async () => {
    const latest: LocalMessage = {
      id: 'assistant-latest',
      role: 'assistant',
      content: 'latest',
      createdAt: '2026-09-27T00:00:00Z',
      taskId: 'task-latest',
      status: 'COMPLETED',
      queryResult: { taskId: 'task-latest', status: 'COMPLETED', data: [{ amount: 999 }], canViewSql: true },
    }
    const selected = { taskId: 'task-old', status: 'COMPLETED', data: [{ amount: 50 }], canViewSql: true, canExport: true }
    vi.mocked(iamS1GetTask).mockResolvedValue({ data: selected } as never)
    vi.mocked(iamS1ViewSql).mockResolvedValue({ data: { sql: 'SELECT 50' } } as never)
    vi.mocked(iamS1ExportCsv).mockResolvedValue(new Blob(['csv']))
    vi.mocked(iamS1SubmitFeedback).mockResolvedValue(undefined as never)
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:task-old'), revokeObjectURL: vi.fn() })

    const submit = createSubmit(latest)
    await submit.selectResultForTask('task-old')
    const exportUtil = useQueryExport({ latestResult: submit.displayResult, canExport: ref(true) })

    expect(submit.latestResult.value?.taskId).toBe('task-latest')
    expect(submit.displayTaskId.value).toBe('task-old')
    expect(exportUtil.pagedTableData.value).toEqual([{ amount: 50 }])

    await submit.refreshSql(submit.displayResult.value)
    await exportUtil.exportCsv()
    await exportUtil.handleFeedback('LIKE')

    expect(iamS1ViewSql).toHaveBeenCalledWith('task-old')
    expect(submit.displayResult.value?.sql).toBe('SELECT 50')
    expect(iamS1ExportCsv).toHaveBeenCalledWith('task-old')
    expect(iamS1SubmitFeedback).toHaveBeenCalledWith('task-old', 'LIKE')
  })

  it('ignores a late result read after returning to follow the current session', async () => {
    const latest: LocalMessage = {
      id: 'assistant-latest',
      role: 'assistant',
      content: 'latest',
      createdAt: '2026-09-27T00:00:00Z',
      taskId: 'task-latest',
      status: 'COMPLETED',
      queryResult: { taskId: 'task-latest', status: 'COMPLETED', data: [{ amount: 999 }] },
    }
    let resolveOld!: (value: unknown) => void
    vi.mocked(iamS1GetTask).mockReturnValue(new Promise((resolve) => { resolveOld = resolve }) as never)
    const submit = createSubmit(latest)
    const oldRead = submit.selectResultForTask('task-old')
    submit.followLatestResult()
    resolveOld({ data: { taskId: 'task-old', status: 'COMPLETED', data: [{ amount: 50 }] } })
    await oldRead

    expect(submit.displayTaskId.value).toBe('task-latest')
    expect(submit.displayResult.value?.data).toEqual([{ amount: 999 }])
    expect(submit.resultSelectionError.value).toBe('')
  })

  it('does not retain another task SQL when reading SQL for the selected task fails', async () => {
    const latest: LocalMessage = {
      id: 'assistant-latest',
      role: 'assistant',
      content: 'latest',
      createdAt: '2026-09-27T00:00:00Z',
      taskId: 'task-latest',
      status: 'COMPLETED',
      queryResult: { taskId: 'task-latest', status: 'COMPLETED', data: [{ amount: 999 }], sql: 'SELECT latest' },
    }
    vi.mocked(iamS1GetTask).mockResolvedValue({
      data: { taskId: 'task-old', status: 'COMPLETED', data: [{ amount: 50 }], canViewSql: true },
    } as never)
    vi.mocked(iamS1ViewSql).mockRejectedValue(new Error('sql read failed'))
    const submit = createSubmit(latest)

    await submit.selectResultForTask('task-old')
    await submit.refreshSql(submit.displayResult.value)

    expect(submit.displayTaskId.value).toBe('task-old')
    expect(submit.displayResult.value?.sql).toBeUndefined()
    expect(submit.sqlErrorTaskId.value).toBe('task-old')
    expect(submit.sqlErrorMessage.value).toContain('当前权限不允许查看 SQL')
    expect(submit.latestResult.value?.sql).toBe('SELECT latest')
    expect(iamS1ViewSql).toHaveBeenCalledWith('task-old')
  })

  it('does not call server export when either task or capability summary denies it', async () => {
    const result = { taskId: 'task-no-export', status: 'COMPLETED', data: [{ amount: 1 }], canExport: true }
    vi.mocked(iamS1ExportCsv).mockResolvedValue(new Blob(['csv']))
    const exportUtil = useQueryExport({ latestResult: ref(result), canExport: ref(false) })

    await exportUtil.exportCsv()

    expect(iamS1ExportCsv).not.toHaveBeenCalled()

    const taskDeniedExport = useQueryExport({
      latestResult: ref({ ...result, canExport: false }),
      canExport: ref(true),
    })
    await taskDeniedExport.exportCsv()
    expect(iamS1ExportCsv).not.toHaveBeenCalled()
  })

  it('sends a confirmed cancellation when Stop is clicked before task submission returns', async () => {
    let resolveAsk!: (value: unknown) => void
    vi.mocked(iamS1Ask).mockReturnValue(new Promise((resolve) => { resolveAsk = resolve }) as never)
    vi.mocked(iamS1CancelTask).mockResolvedValue(undefined as never)
    const submit = createSubmit()
    submit.question.value = '统计订单'

    const sending = submit.sendQuestion()
    expect(submit.isQuerying.value).toBe(true)
    await submit.cancelCurrentQuery()
    resolveAsk({ data: { taskId: 'task-stop', conversationId: 42 } })
    await sending

    expect(iamS1CancelTask).toHaveBeenCalledWith('task-stop')
    expect(iamS1GetTask).not.toHaveBeenCalled()
    expect(submit.latestAssistantMessage.value).toMatchObject({ status: 'CANCELLED', taskId: 'task-stop' })
    expect(submit.isQuerying.value).toBe(false)
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
