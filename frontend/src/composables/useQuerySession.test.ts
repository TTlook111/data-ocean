import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useQuerySession } from './useQuerySession'
import { iamS1ListConversationMessages } from '../api/iamS1'

vi.mock('../api/iamS1', () => ({
  iamS1DeleteConversation: vi.fn(),
  iamS1GetDatasourceReadiness: vi.fn(),
  iamS1ListConversationMessages: vi.fn(),
  iamS1ListConversations: vi.fn(),
  listIamS1QueryResourceDatasources: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

describe('useQuerySession history pagination', () => {
  beforeEach(() => vi.clearAllMocks())

  it('relinks old clarification and failure messages to their questions after loading another page', async () => {
    vi.mocked(iamS1ListConversationMessages)
      .mockResolvedValueOnce({
        data: {
          items: [
            { id: 90, role: 'user', content: '最近的问题', createdAt: '2026-09-27T00:00:00Z' },
            { id: 91, role: 'assistant', content: '完成', taskId: 'task-new',
              metadata: '{"status":"COMPLETED","question":"最近的问题"}', createdAt: '2026-09-27T00:00:01Z' },
          ],
          nextBeforeMessageId: 90,
          hasMore: true,
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          items: [
            { id: 2, role: 'user', content: '统计最近 30 天订单', createdAt: '2026-09-26T00:00:00Z' },
            { id: 3, role: 'assistant', content: '请补充状态', taskId: 'task-clarification',
              metadata: '{"status":"CLARIFICATION_REQUIRED"}', createdAt: '2026-09-26T00:00:01Z' },
            { id: 4, role: 'user', content: '按已完成状态统计订单', createdAt: '2026-09-26T00:01:00Z' },
            { id: 5, role: 'assistant', content: '查询失败', taskId: 'task-failed',
              metadata: '{"status":"FAILED"}', createdAt: '2026-09-26T00:01:01Z' },
          ],
          nextBeforeMessageId: 2,
          hasMore: false,
        },
      } as never)

    const sessionApi = useQuerySession()
    const session = sessionApi.createSession(1)
    session.conversationId = 42

    await sessionApi.hydrateSessionMessages(session)
    await sessionApi.loadOlderMessages(session)

    expect(session.messages.find((message) => message.id === 'remote-3'))
      .toMatchObject({ originalQuestion: '统计最近 30 天订单', status: 'CLARIFICATION_REQUIRED' })
    expect(session.messages.find((message) => message.id === 'remote-5'))
      .toMatchObject({ originalQuestion: '按已完成状态统计订单', status: 'FAILED' })
  })
})
