// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const f = vi.hoisted(() => ({
  auth: { token: 'A', currentUser: { id: 1 } },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    queryUse: true,
    hasGlobal: vi.fn(),
    canOnDatasource: vi.fn(),
  },
  api: {
    listIamS1AccessRequestQueue: vi.fn(),
    listIamS1MyAccessRequests: vi.fn(),
    listIamS1QueryResourceColumns: vi.fn(),
    listIamS1QueryResourceDatasources: vi.fn(),
    listIamS1QueryResourceSnapshots: vi.fn(),
    listIamS1QueryResourceTables: vi.fn(),
    reviewIamS1AccessRequest: vi.fn(),
    submitIamS1AccessRequest: vi.fn(),
    withdrawIamS1AccessRequest: vi.fn(),
  },
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(f.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(f.iam) }))
vi.mock('../../../api/iamS1', () => f.api)
import Panel from './IamS1ApprovalView.vue'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const state = (w: any) => w.vm.$.setupState as any,
  deferred = () => {
    let resolve!: (v: any) => void
    const promise = new Promise<any>((r) => (resolve = r))
    return { resolve, promise }
  }
const row = {
  id: 42,
  requesterId: 8,
  datasourceId: 1,
  metadataSnapshotId: 11,
  tableName: 'orders',
  requestedColumns: ['amount', 'status'],
  rowScope: 'ALL',
  purpose: '原申请用途',
  status: 'PENDING',
}
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function page() {
  return shallowMount(Panel, { global: { plugins: [ElementPlus] } })
}
async function form(w: any) {
  await settle()
  await state(w).openEditor()
  state(w).form.tableName = 'orders'
  await state(w).loadColumns()
  state(w).form.columns = ['amount']
  state(w).form.purpose = '人工申请依据'
}
async function queue(w: any) {
  await settle()
  state(w).activeTab = 'pending'
  await state(w).loadQueue('pending')
}
async function review(w: any, decision = 'APPROVE') {
  await queue(w)
  state(w).review(state(w).queue.pending.rows[0], decision)
  state(w).reviewDraft.reason = '人工审批依据'
}
beforeEach(() => {
  vi.restoreAllMocks()
  Object.values(f.api).forEach((x) => x.mockReset())
  f.auth.token = 'A'
  f.auth.currentUser.id = 1
  f.iam.snapshot = {}
  f.iam.loaded = true
  f.iam.errorMessage = ''
  f.iam.queryUse = true
  f.iam.hasGlobal.mockReset().mockImplementation((c) => c === 'security:approval:view')
  f.iam.canOnDatasource
    .mockReset()
    .mockImplementation((c, ds) => c === 'security:approval:review' && ds === 1)
  pendingGovernanceWrites.value = []
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
  f.api.listIamS1MyAccessRequests.mockResolvedValue({ data: [{ ...row, id: 41, requesterId: 1 }] })
  f.api.listIamS1AccessRequestQueue.mockResolvedValue({ data: { records: [row], total: 21 } })
  f.api.listIamS1QueryResourceDatasources.mockResolvedValue({ data: [{ id: 1, name: '申请源' }] })
  f.api.listIamS1QueryResourceSnapshots.mockImplementation(async (ds) => ({
    data: [{ id: 11, datasourceId: ds, status: 'PUBLISHED' }],
  }))
  f.api.listIamS1QueryResourceTables.mockImplementation(async (ds, snap) => ({
    data: [{ datasourceId: ds, metadataSnapshotId: snap, tableName: 'orders', selectable: true }],
  }))
  f.api.listIamS1QueryResourceColumns.mockResolvedValue({
    data: [
      { columnMetaId: 204, columnName: 'amount', selectable: true },
      { columnMetaId: 205, columnName: 'status', selectable: true },
    ],
  })
  f.api.submitIamS1AccessRequest.mockResolvedValue({ data: 43 })
  f.api.reviewIamS1AccessRequest.mockImplementation(async (id, payload) => ({
    data: { requestId: id, generatedGrantId: payload.decision === 'APPROVE' ? 71 : '' },
  }))
  f.api.withdrawIamS1AccessRequest.mockResolvedValue({})
})
it('只读审批角色独立读队列，不因缺queryUse调用APPLY或被申请目录卡住', async () => {
  f.iam.queryUse = false
  f.iam.canOnDatasource.mockReturnValue(false)
  const w = page()
  await settle()
  expect(state(w).activeTab).toBe('pending')
  expect(f.api.listIamS1AccessRequestQueue).toHaveBeenCalledWith('PENDING', 1, 20)
  expect(f.api.listIamS1QueryResourceDatasources).not.toHaveBeenCalled()
  expect(state(w).currentRows).toHaveLength(1)
  expect(state(w).canReviewOn(row)).toBe(false)
  w.unmount()
})
it('本人申请列表先读，APPLY仅在发起时读取，不需后台源', async () => {
  const w = page()
  await settle()
  expect(f.api.listIamS1MyAccessRequests).toHaveBeenCalledTimes(1)
  expect(f.api.listIamS1QueryResourceDatasources).not.toHaveBeenCalled()
  await form(w)
  expect(f.api.listIamS1QueryResourceDatasources).toHaveBeenCalledWith('APPLY')
  expect(f.api.listIamS1QueryResourceColumns).toHaveBeenCalledWith(1, 11, 'orders', 'APPLY')
  w.unmount()
})
it('pending/handled页码与失败分别独立，不把一个失败伪装成全队列空', async () => {
  const w = page()
  await queue(w)
  f.api.listIamS1AccessRequestQueue.mockRejectedValueOnce(new Error('待审503'))
  await state(w).loadQueue('pending')
  expect(state(w).queue.pending.error).toContain('503')
  state(w).queue.handled.page = 2
  await state(w).loadQueue('handled')
  expect(f.api.listIamS1AccessRequestQueue).toHaveBeenLastCalledWith('HANDLED', 2, 20)
  expect(state(w).queue.handled.rows).toHaveLength(1)
  expect(state(w).queue.pending.page).toBe(1)
  w.unmount()
})
it('分页晚回不覆新页，账号变化清旧申请', async () => {
  const w = page()
  await queue(w)
  const d = deferred()
  f.api.listIamS1AccessRequestQueue.mockReturnValueOnce(d.promise)
  const a = state(w).loadQueue('pending')
  state(w).queue.pending.page = 2
  await state(w).loadQueue('pending')
  d.resolve({ data: { records: [{ ...row, id: 99 }], total: 21 } })
  await a
  expect(state(w).queue.pending.rows[0].id).toBe(42)
  reactive(f.auth).token = 'B'
  f.api.listIamS1MyAccessRequests.mockResolvedValue({ data: [] })
  await settle()
  expect(state(w).reviewTarget).toBeUndefined()
  w.unmount()
})
it('提交冻结发布快照/可选字段，本地时间不带Z，双击一次', async () => {
  const w = page()
  await form(w)
  state(w).form.validUntil = '2026-11-01 12:00:00'
  const d = deferred()
  f.api.submitIamS1AccessRequest.mockReturnValueOnce(d.promise)
  const a = state(w).submitRequest()
  await state(w).submitRequest()
  expect(f.api.submitIamS1AccessRequest).toHaveBeenCalledTimes(1)
  expect(f.api.submitIamS1AccessRequest).toHaveBeenCalledWith(
    expect.objectContaining({
      datasourceId: 1,
      metadataSnapshotId: 11,
      columns: ['amount'],
      rowScope: 'ALL',
      purpose: '人工申请依据',
      requestedValidUntil: '2026-11-01T12:00:00',
    }),
  )
  d.resolve({ data: 43 })
  await a
  w.unmount()
})
it('409保申请依据和字段，不自动重发；不可选字段零写', async () => {
  const w = page()
  await form(w)
  f.api.submitIamS1AccessRequest.mockRejectedValueOnce(
    Object.assign(new Error('409冲突'), { response: { status: 409 } }),
  )
  await state(w).submitRequest()
  expect(state(w).form.purpose).toBe('人工申请依据')
  expect(state(w).form.columns).toEqual(['amount'])
  expect(pendingGovernanceWrites.value).toEqual([])
  state(w).form.columns = ['hidden']
  await state(w).submitRequest()
  expect(f.api.submitIamS1AccessRequest).toHaveBeenCalledTimes(1)
  w.unmount()
})
it('保存期间新的人工用途不被旧成功清空或关闭', async () => {
  const w = page()
  await form(w)
  const d = deferred()
  f.api.submitIamS1AccessRequest.mockReturnValueOnce(d.promise)
  const a = state(w).submitRequest()
  state(w).form.purpose = '新的人工用途'
  d.resolve({ data: 43 })
  await a
  expect(state(w).form.purpose).toBe('新的人工用途')
  expect(state(w).editorOpen).toBe(true)
  w.unmount()
})
it('残缺提交回执跨挂载留精确源锁，未知不自动补申请', async () => {
  const w = page()
  await form(w)
  f.api.submitIamS1AccessRequest.mockResolvedValueOnce({ data: null })
  await state(w).submitRequest()
  w.unmount()
  const w2 = page()
  await form(w2)
  expect(state(w2).locked(state(w2).submitLock)).toBe(true)
  await state(w2).submitRequest()
  expect(f.api.submitIamS1AccessRequest).toHaveBeenCalledTimes(1)
  w2.unmount()
})
it('本人撤回精确申请确认，等待期间账号变化不得发写', async () => {
  const w = page()
  await settle()
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).withdraw(state(w).mine[0])
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('申请 #41 / 申请人 #1 / 源 #1 / 快照 #11'),
    expect.any(String),
    expect.any(Object),
  )
  reactive(f.auth).token = 'B'
  d.resolve('confirm')
  await a
  expect(f.api.withdrawIamS1AccessRequest).not.toHaveBeenCalled()
  w.unmount()
})
it('不允许自审、非负责源审批、非当前pending行或已处理申请', async () => {
  const w = page()
  await queue(w)
  expect(state(w).canReviewOn({ ...row, requesterId: 1 })).toBe(false)
  expect(state(w).canReviewOn({ ...row, datasourceId: 2 })).toBe(false)
  expect(state(w).canReviewOn({ ...row, status: 'APPROVED' })).toBe(false)
  state(w).review({ ...row }, 'APPROVE')
  expect(state(w).reviewTarget).toBeUndefined()
  expect(f.api.reviewIamS1AccessRequest).not.toHaveBeenCalled()
  w.unmount()
})
it('批准冻结原申请子集与有期限，二次确认明确对象，双击只审批一次', async () => {
  const w = page()
  await review(w)
  state(w).reviewDraft.columns = ['amount']
  const d = deferred()
  f.api.reviewIamS1AccessRequest.mockReturnValueOnce(d.promise)
  const a = state(w).confirmReview()
  await settle()
  await state(w).confirmReview()
  expect(f.api.reviewIamS1AccessRequest).toHaveBeenCalledTimes(1)
  expect(f.api.reviewIamS1AccessRequest).toHaveBeenCalledWith(
    42,
    expect.objectContaining({ decision: 'APPROVE', approvedColumns: ['amount'], reason: '人工审批依据' }),
  )
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('申请 #42 / 申请人 #8 / 源 #1 / 快照 #11'),
    expect.any(String),
    expect.any(Object),
  )
  d.resolve({ data: { requestId: 42, generatedGrantId: 71 } })
  await a
  w.unmount()
})
it('批准非申请字段、无期限或超过30天/原申请期限均零写', async () => {
  const w = page()
  await review(w)
  state(w).reviewDraft.columns = ['secret']
  await state(w).confirmReview()
  state(w).reviewDraft.columns = ['amount']
  state(w).reviewDraft.validUntil = ''
  await state(w).confirmReview()
  state(w).reviewDraft.validUntil = state(w).formatLocal(new Date(Date.now() + 31 * 86400000))
  await state(w).confirmReview()
  expect(f.api.reviewIamS1AccessRequest).not.toHaveBeenCalled()
  w.unmount()
})
it('审批409保留原依据、字段、期限；拒绝也须依据与精确确认', async () => {
  const w = page()
  await review(w)
  const until = state(w).reviewDraft.validUntil
  f.api.reviewIamS1AccessRequest.mockRejectedValueOnce(
    Object.assign(new Error('409已处理'), { response: { status: 409 } }),
  )
  await state(w).confirmReview()
  expect(state(w).reviewDraft.reason).toBe('人工审批依据')
  expect(state(w).reviewDraft.validUntil).toBe(until)
  expect(state(w).reviewTarget.id).toBe(42)
  state(w).reviewDraft.decision = 'REJECT'
  await state(w).confirmReview()
  expect(f.api.reviewIamS1AccessRequest).toHaveBeenLastCalledWith(42, {
    decision: 'REJECT',
    approvedColumns: [],
    approvedValidUntil: null,
    reason: '人工审批依据',
  })
  w.unmount()
})
it('审批确认期间撤权或切Tab，旧申请不得写', async () => {
  const w = page()
  await review(w)
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).confirmReview()
  expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
  reactive(f.iam).snapshot = { changed: true } as any
  f.iam.canOnDatasource.mockReturnValue(false)
  d.resolve('confirm')
  await a
  expect(f.api.reviewIamS1AccessRequest).not.toHaveBeenCalled()
  w.unmount()
})
it('申请资源晚回不覆盖新源、辅助失败不阻本人列表且禁止用旧列提交', async () => {
  const w = page()
  await form(w)
  f.api.listIamS1QueryResourceColumns.mockRejectedValue(new Error('APPLY503'))
  await state(w).loadColumns()
  expect(state(w).resourceError).toContain('503')
  expect(state(w).mine).toHaveLength(1)
  await state(w).submitRequest()
  expect(f.api.submitIamS1AccessRequest).not.toHaveBeenCalled()
  w.unmount()
})

it.each(['snapshotId', 'tableName'])('提交期间换%s旧回包不得卡住新对象busy', async (field) => {
  const w = page()
  await form(w)
  const d = deferred()
  f.api.submitIamS1AccessRequest.mockReturnValueOnce(d.promise)
  const a = state(w).submitRequest()
  expect(state(w).submitting).toBe(true)
  state(w).form[field] = field === 'snapshotId' ? 12 : 'other'
  expect(state(w).submitting).toBe(false)
  d.resolve({ data: 43 })
  await a
  expect(state(w).submitting).toBe(false)
  expect(state(w).form.purpose).toBe('人工申请依据')
  w.unmount()
})
it('申请提交与撤回不同申请的op互不污染，提交正常释放busy', async () => {
  const w = page()
  await form(w)
  const d = deferred()
  f.api.submitIamS1AccessRequest.mockReturnValueOnce(d.promise)
  const a = state(w).submitRequest()
  await state(w).withdraw(state(w).mine[0])
  expect(f.api.withdrawIamS1AccessRequest).toHaveBeenCalledTimes(1)
  d.resolve({ data: 43 })
  await a
  expect(state(w).submitting).toBe(false)
  expect(state(w).editorOpen).toBe(false)
  w.unmount()
})

it.each([
  { data: {} },
  { data: { requestId: 99, generatedGrantId: 71 } },
  { data: { requestId: 42, generatedGrantId: null } },
])('残缺/错属批准回执%j保留审批依据及未知锁', async (receipt) => {
  const w = page()
  await review(w)
  f.api.reviewIamS1AccessRequest.mockResolvedValue(receipt)
  await state(w).confirmReview()
  expect(state(w).reviewDraft.reason).toBe('人工审批依据')
  expect(state(w).reviewTarget.id).toBe(42)
  expect(pendingGovernanceWrites.value[0]?.state).toBe('unknown')
  await state(w).confirmReview()
  expect(f.api.reviewIamS1AccessRequest).toHaveBeenCalledTimes(1)
  w.unmount()
})
it('批准期限未超30天但超过原申请较短期限仍零写', async () => {
  const w = page()
  await queue(w)
  state(w).queue.pending.rows[0].requestedValidUntil = state(w).formatLocal(new Date(Date.now() + 86400000))
  state(w).review(state(w).queue.pending.rows[0], 'APPROVE')
  state(w).reviewDraft.reason = '原申请短期限核对'
  state(w).reviewDraft.validUntil = state(w).formatLocal(new Date(Date.now() + 2 * 86400000))
  await state(w).confirmReview()
  expect(f.api.reviewIamS1AccessRequest).not.toHaveBeenCalled()
  expect(state(w).writeError).toContain('不超过申请期限')
  w.unmount()
})

it('旧源目录收尾不能清掉新源读取loading，实际延迟回包不混入新源', async () => {
  const w = page()
  await settle()
  f.api.listIamS1QueryResourceDatasources.mockResolvedValue({
    data: [
      { id: 1, name: '一源' },
      { id: 2, name: '二源' },
    ],
  })
  const old = deferred(),
    fresh = deferred()
  f.api.listIamS1QueryResourceSnapshots.mockImplementation((ds) => (ds === 1 ? old.promise : fresh.promise))
  const a = state(w).openEditor()
  await settle()
  state(w).form.datasourceId = 2
  const b = state(w).loadSnapshots()
  expect(state(w).loadingResources).toBe(true)
  old.resolve({ data: [{ id: 11, datasourceId: 1, status: 'PUBLISHED' }] })
  await a
  expect(state(w).loadingResources).toBe(true)
  expect(state(w).snapshots).toEqual([])
  fresh.resolve({ data: [{ id: 22, datasourceId: 2, status: 'PUBLISHED' }] })
  await b
  expect(state(w).loadingResources).toBe(false)
  expect(state(w).form.snapshotId).toBe(22)
  w.unmount()
})
