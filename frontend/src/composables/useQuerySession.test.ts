import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useQuerySession } from './useQuerySession'
import { ElMessageBox } from 'element-plus'
import { iamS1DeleteConversation, iamS1GetDatasourceReadiness, iamS1ListConversationMessages, iamS1ListConversations, listIamS1QueryResourceDatasources } from '../api/iamS1'

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

  it('切源后旧会话列表不得接管活动会话', async () => {
    let resolveOld!: (value: unknown) => void
    vi.mocked(iamS1ListConversations).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }) as never)
      .mockResolvedValueOnce({ data: [] } as never)
    const api = useQuerySession()
    const old = api.selectDatasource(1)
    await api.selectDatasource(2)
    resolveOld({ data: [{ id: 10, datasourceId: 1, title: '旧源', createdAt: '', updatedAt: '' }] })
    await old
    expect(api.selectedId.value).toBe(2)
    expect(api.activeSession.value?.datasourceId).toBe(2)
    expect(api.sessions.some(session => session.conversationId === 10)).toBe(false)
  })
  it('历史首屏读取期间同时阻止 UI 就绪与实际发送条件', async () => {
    let resolveHistory!: (value: unknown) => void
    vi.mocked(iamS1ListConversations).mockReturnValueOnce(new Promise(resolve => { resolveHistory = resolve }) as never)
    const api = useQuerySession()
    api.readinessMap.value = { 1: { askable: true } } as never
    const selecting = api.selectDatasource(1)
    expect(api.historyLoading.value).toBe(true)
    expect(api.canAskSelectedDatasource.value).toBe(false)
    api.startNewSession()
    expect(api.sessions).toHaveLength(0)
    resolveHistory({ data: [] }); await selecting
    expect(api.canAskSelectedDatasource.value).toBe(true)
  })
  it('账号 reset 后旧数据源请求不能恢复上一账号范围', async () => {
    let resolveOld!: (value: unknown) => void
    vi.mocked(listIamS1QueryResourceDatasources).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }) as never)
    const api = useQuerySession()
    const old = api.fetchDatasources()
    api.reset()
    resolveOld({ data: [{ id: 1, name: '旧账号数据源' }] }); await old
    expect(api.datasources.value).toEqual([])
    expect(api.selectedId.value).toBeUndefined()
  })

  it('历史列表失败后同源重试会重新请求并恢复列表', async () => {
    vi.mocked(iamS1ListConversations).mockRejectedValueOnce(new Error('断网')).mockResolvedValueOnce({ data: [{ id: 10, datasourceId: 1, title: '历史', createdAt: '', updatedAt: '' }] } as never)
    vi.mocked(iamS1ListConversationMessages).mockResolvedValue({ data: { items: [], hasMore: false } } as never)
    const api = useQuerySession()
    await api.selectDatasource(1)
    expect(api.historyError.value).not.toBe('')
    await api.retryHistory()
    expect(iamS1ListConversations).toHaveBeenCalledTimes(2)
    expect(api.historyError.value).toBe('')
    expect(api.datasourceSessions.value.some(item => item.conversationId === 10)).toBe(true)
  })

  it('首屏消息失败后同源重试不会被已加载源缓存挡住', async () => {
    vi.mocked(iamS1ListConversations).mockResolvedValue({ data: [{ id: 10, datasourceId: 1, title: '历史', createdAt: '', updatedAt: '' }] } as never)
    vi.mocked(iamS1ListConversationMessages).mockRejectedValueOnce(new Error('断网')).mockResolvedValueOnce({ data: { items: [{ id: 1, role: 'user', content: '恢复的问题', createdAt: '' }], hasMore: false } } as never)
    const api = useQuerySession()
    await api.selectDatasource(1)
    expect(api.historyError.value).not.toBe('')
    await api.retryHistory()
    expect(iamS1ListConversationMessages).toHaveBeenCalledTimes(2)
    expect(api.historyError.value).toBe('')
    expect(api.activeMessages.value[0]?.content).toBe('恢复的问题')
  })

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

describe('readiness failure and recovery', () => {
  beforeEach(() => vi.resetAllMocks())
  it('500 is unknown and blocked, retry recovers independently from another ready source', async () => {
    vi.mocked(iamS1GetDatasourceReadiness).mockImplementation(async id => {
      if(id===1) throw {response:{status:500}}
      return {data:{askable:true}} as never
    })
    const api=useQuerySession(); api.selectedId.value=1
    await api.fetchDatasourceReadiness([{id:1},{id:2}] as never)
    expect(api.selectedBlockReason.value?.message).toContain('读取失败')
    expect(api.canAskSelectedDatasource.value).toBe(false)
    api.selectedId.value=2; expect(api.canAskSelectedDatasource.value).toBe(true)
    vi.mocked(iamS1GetDatasourceReadiness).mockResolvedValue({data:{askable:true}} as never)
    api.selectedId.value=1; await api.fetchDatasourceReadiness([{id:1}] as never)
    expect(api.selectedBlockReason.value).toBeUndefined(); expect(api.canAskSelectedDatasource.value).toBe(true)
  })
  it('403 describes access, while a late failed request cannot replace the latest ready result', async () => {
    const api=useQuerySession(); api.selectedId.value=1
    vi.mocked(iamS1GetDatasourceReadiness).mockRejectedValueOnce({response:{status:403}})
    await api.fetchDatasourceReadiness([{id:1}] as never)
    expect(api.selectedBlockReason.value?.message).toContain('无权')
    let reject!:(error:unknown)=>void
    vi.mocked(iamS1GetDatasourceReadiness).mockReturnValueOnce(new Promise((_r,j)=>{reject=j}) as never).mockResolvedValueOnce({data:{askable:true}} as never)
    const old=api.fetchDatasourceReadiness([{id:1}] as never)
    await api.fetchDatasourceReadiness([{id:1}] as never); reject(new Error('old')); await old
    expect(api.canAskSelectedDatasource.value).toBe(true)
    expect(api.selectedBlockReason.value).toBeUndefined()
  })
})

describe('confirmed deletion retains task and identity ownership', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as never)
    vi.mocked(iamS1DeleteConversation).mockResolvedValue({data:null} as never)
  })
  it('an unconfirmed stop keeps the pending local session without archiving anything', async () => {
    const api=useQuerySession(),target=api.createSession(1)
    const beforeDelete=vi.fn().mockResolvedValue(false)
    await api.removeSession(target,{beforeDelete})
    expect(beforeDelete).toHaveBeenCalledOnce()
    expect(api.activeSessionId.value).toBe(target.id)
    expect(api.sessions).toHaveLength(1)
    expect(iamS1DeleteConversation).not.toHaveBeenCalled()
  })
  it('rejecting the deletion dialog never stops a query', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    const api=useQuerySession(),target=api.createSession(1),beforeDelete=vi.fn()
    await api.removeSession(target,{beforeDelete})
    expect(beforeDelete).not.toHaveBeenCalled();expect(api.sessions).toHaveLength(1)
  })
  it('reset while a stop is pending prevents a late delete or restoration', async () => {
    const api=useQuerySession(),target=api.createSession(1);target.conversationId=42
    let confirmStop!:(ok:boolean)=>void
    const beforeDelete=vi.fn(()=>new Promise<boolean>(resolve=>{confirmStop=resolve}))
    const deleting=api.removeSession(target,{beforeDelete})
    await Promise.resolve();expect(beforeDelete).toHaveBeenCalledOnce()
    api.reset();confirmStop(true);await deleting
    expect(iamS1DeleteConversation).not.toHaveBeenCalled();expect(api.sessions).toHaveLength(0)
  })
  it('an identity change during confirmation rejects before the stop hook or delete', async () => {
    let confirmed!:(value:never)=>void
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(new Promise(resolve=>{confirmed=resolve}))
    const api=useQuerySession(),target=api.createSession(1),beforeDelete=vi.fn()
    let sameIdentity=true
    const deleting=api.removeSession(target,{beforeDelete,accepts:()=>sameIdentity})
    sameIdentity=false;confirmed('confirm' as never);await deleting
    expect(beforeDelete).not.toHaveBeenCalled();expect(api.sessions).toHaveLength(1)
  })
  it('confirmed stop precedes the exact remote delete and duplicate clicks do not duplicate it', async () => {
    let confirmed!:(value:never)=>void
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(new Promise(resolve=>{confirmed=resolve}))
    const api=useQuerySession(),target=api.createSession(1);target.conversationId=42
    const order:string[]=[]
    vi.mocked(iamS1DeleteConversation).mockImplementationOnce(async id=>{order.push('delete:'+id);return {data:null} as never})
    const beforeDelete=vi.fn(async()=>{order.push('stop');return true})
    const first=api.removeSession(target,{beforeDelete}),second=api.removeSession(target,{beforeDelete})
    expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
    confirmed('confirm' as never);await Promise.all([first,second])
    expect(order).toEqual(['stop','delete:42']);expect(api.sessions).toHaveLength(0)
  })
  it('a stale or foreign session object cannot reach confirmation or delete', async()=>{
    const api=useQuerySession(),owned=api.createSession(1)
    await api.removeSession({...owned,conversationId:99})
    expect(ElMessageBox.confirm).not.toHaveBeenCalled();expect(iamS1DeleteConversation).not.toHaveBeenCalled()
  })
})

it('a confirmed dialog cannot delete a different server conversation after its target drifts',async()=>{
 vi.resetAllMocks()
 let confirmed!:(value:never)=>void
 vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(new Promise(resolve=>{confirmed=resolve}))
 const api=useQuerySession(),target=api.createSession(1);target.conversationId=42
 const beforeDelete=vi.fn().mockResolvedValue(true)
 const deleting=api.removeSession(target,{beforeDelete})
 target.conversationId=99;confirmed('confirm' as never);await deleting
 expect(beforeDelete).not.toHaveBeenCalled();expect(iamS1DeleteConversation).not.toHaveBeenCalled()
 expect(api.sessions).toHaveLength(1)
})
