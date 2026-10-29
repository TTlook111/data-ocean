// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const s = vi.hoisted(() => ({
  auth: { token: 'A' },
  route: { query: {} as Record<string, unknown> },
  context: {
    datasourceId: 5,
    snapshotId: undefined as number | undefined,
    loading: false,
    routeSyncing: false,
    routeSyncError: '',
  },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    systemAdmin: false,
    codes: ['governance:field:view', 'governance:field:manage'],
    canOnDatasource: vi.fn(),
    hasGlobal: vi.fn(),
    datasourcesWithFunction: vi.fn(),
  },
  api: {
    listGovernanceFeedback: vi.fn(),
    reviewGovernanceFeedback: vi.fn(),
    verifyGovernanceFeedback: vi.fn(),
    listPendingReviews: vi.fn(),
    approveFeedback: vi.fn(),
    rejectFeedback: vi.fn(),
  },
}))
vi.mock('vue-router', () => ({ useRoute: () => reactive(s.route) }))
vi.mock('../../stores/adminContext', () => ({ useAdminContextStore: () => reactive(s.context) }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => reactive(s.auth) }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => reactive(s.iam) }))
vi.mock('../../api/admin/governanceWorkflow', () => ({
  ...s.api,
  governanceError: (e: any, f: string) => e?.response?.data?.message || f,
}))
vi.mock('../../api/admin/field', () => s.api)
vi.mock('../../composables/useGsapMotion', () => ({
  useGsapMotion: () => ({ reveal: vi.fn(), withContext: (f: any) => f() }),
}))
import Queue from './GovernanceFeedbackQueue.vue'
import Legacy from '../../views/admin/field/FeedbackReview.vue'
import { pendingGovernanceWrites } from '../../composables/governanceWriteTracker'
const row = {
  id: 81,
  taskId: 'query-1',
  feedbackType: 'DISLIKE',
  reasonCode: 'FIELD_MEANING',
  comment: '口径不符',
  status: 'PENDING_ATTRIBUTION',
  rowVersion: 4,
  targets: [{ columnMetaId: 101, fieldName: 'orders.amount' }],
}
const fix = {
  ...row,
  status: 'APPLIED',
  followupStatus: 'AWAITING_VERIFICATION',
  currentRevisionId: 901,
  resolution: { scenario: '原订单101，固定条件', expectedResult: '原标准100元' },
}
function deferred() {
  let resolve!: (x: any) => void, reject!: (x: any) => void
  const promise = new Promise<any>((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function page(c: any = Queue) {
  return shallowMount(c, { global: { plugins: [ElementPlus], stubs: { GovernanceHistoryDialog: true } } })
}
function fill(vm: any) {
  vm.column = 101
  vm.note = '资料v2第3节'
  vm.scenario = '原订单101，固定条件'
  vm.expectedResult = '原标准100元'
}
beforeEach(() => {
  vi.restoreAllMocks()
  s.auth.token = 'A'
  s.context.datasourceId = 5
  s.context.snapshotId = undefined
  s.route.query = {}
  s.iam.loaded = true
  s.iam.errorMessage = ''
  s.iam.snapshot = {}
  s.iam.codes = ['governance:field:view', 'governance:field:manage']
  s.iam.canOnDatasource.mockImplementation((c, id) => id === 5 && reactive(s.iam).codes.includes(c))
  s.iam.hasGlobal.mockImplementation((c) => reactive(s.iam).codes.includes(c))
  s.iam.datasourcesWithFunction.mockImplementation((c) => (reactive(s.iam).codes.includes(c) ? [5] : []))
  Object.values(s.api).forEach((x) => x.mockReset())
  s.api.listGovernanceFeedback.mockResolvedValue({ data: { records: [row], total: 1 } })
  s.api.reviewGovernanceFeedback.mockResolvedValue({ data: null })
  s.api.verifyGovernanceFeedback.mockResolvedValue({ data: null })
  s.api.listPendingReviews.mockResolvedValue({
    data: { records: [{ id: 91, columnMetaId: 101, reviewStatus: 'PENDING' }], total: 1 },
  })
  pendingGovernanceWrites.value = []
})
it('源级反馈没有公共快照仍可读，不增加快照门禁', async () => {
  const w = page()
  await settle()
  expect(s.api.listGovernanceFeedback).toHaveBeenCalledWith(5, 1)
  expect((w.vm as any).rows[0].id).toBe(81)
  w.unmount()
})
it('显式无效源不使用旧偏好源', async () => {
  s.route.query.datasourceId = 'bad'
  const w = page()
  await settle()
  expect(s.api.listGovernanceFeedback).not.toHaveBeenCalled()
  w.unmount()
})
it('同源换账号旧反馈列表不能覆盖当前结果', async () => {
  const d = deferred()
  s.api.listGovernanceFeedback
    .mockReturnValueOnce(d.promise)
    .mockResolvedValue({ data: { records: [{ ...row, id: 82 }], total: 1 } })
  const w = page()
  await settle()
  reactive(s.auth).token = 'B'
  await settle()
  d.resolve({ data: { records: [row], total: 1 } })
  await settle()
  expect((w.vm as any).rows[0].id).toBe(82)
  w.unmount()
})
it('反馈读取失败有错误，不显示为无评价', async () => {
  s.api.listGovernanceFeedback.mockRejectedValue(new Error('fail'))
  const w = page()
  await settle()
  expect((w.vm as any).error).toContain('读取失败')
  w.unmount()
})
it('归因只能选实际使用字段，越出targets零写', async () => {
  const w = page()
  await settle()
  ;(w.vm as any).open(row)
  fill(w.vm)
  ;(w.vm as any).column = 999
  await (w.vm as any).review(true)
  expect(s.api.reviewGovernanceFeedback).not.toHaveBeenCalled()
  expect((w.vm as any).dialogError).toContain('本次实际执行')
  w.unmount()
})
it('归因重复确认一次，409保留原版本/依据与固定标准', async () => {
  const d = deferred()
  s.api.reviewGovernanceFeedback.mockReturnValue(d.promise)
  const w = page()
  await settle()
  ;(w.vm as any).open(row)
  fill(w.vm)
  const a = (w.vm as any).review(true)
  await (w.vm as any).review(true)
  expect(s.api.reviewGovernanceFeedback).toHaveBeenCalledTimes(1)
  expect(s.api.reviewGovernanceFeedback).toHaveBeenCalledWith(
    81,
    expect.objectContaining({
      expectedVersion: 4,
      columnMetaId: 101,
      note: '资料v2第3节',
      expectedResult: '原标准100元',
    }),
  )
  d.reject({ response: { status: 409, data: { message: '修订冲突' } } })
  await a
  expect((w.vm as any).current.id).toBe(81)
  expect((w.vm as any).note).toBe('资料v2第3节')
  expect((w.vm as any).dialogError).toBe('修订冲突')
  w.unmount()
})
it('人工核验发送实际结果和精确修复修订，不改原情景与标准', async () => {
  s.api.listGovernanceFeedback.mockResolvedValue({ data: { records: [fix], total: 1 } })
  const d = deferred()
  s.api.verifyGovernanceFeedback.mockReturnValue(d.promise)
  const w = page()
  await settle()
  ;(w.vm as any).open(fix)
  ;(w.vm as any).actualResult = '按原条件得到100元'
  ;(w.vm as any).evidenceReference = '受保护执行attempt-2'
  ;(w.vm as any).verificationConfirmed = true
  const a = (w.vm as any).verifyFix()
  await (w.vm as any).verifyFix()
  expect(s.api.verifyGovernanceFeedback).toHaveBeenCalledTimes(1)
  expect(s.api.verifyGovernanceFeedback).toHaveBeenCalledWith(81, {
    expectedVersion: 4,
    targetRevisionId: 901,
    actualResult: '按原条件得到100元',
    evidenceReference: '受保护执行attempt-2',
    confirmed: true,
  })
  expect((w.vm as any).current.resolution).toEqual(fix.resolution)
  d.reject({ response: { status: 409, data: { message: '原问题仍待核验' } } })
  await a
  expect((w.vm as any).actualResult).toBe('按原条件得到100元')
  expect((w.vm as any).dialogError).toBe('原问题仍待核验')
  w.unmount()
})
it('审核期间撤权/换账号关闭目标，再确认零写', async () => {
  const w = page()
  await settle()
  ;(w.vm as any).open(row)
  fill(w.vm)
  reactive(s.iam).snapshot = { r: 2 }
  reactive(s.iam).codes = ['governance:field:view']
  await settle()
  await (w.vm as any).review(true)
  expect(s.api.reviewGovernanceFeedback).not.toHaveBeenCalled()
  expect((w.vm as any).current).toBeUndefined()
  w.unmount()
})
it('反馈写网络未知跨页锁，不重复审核', async () => {
  s.api.reviewGovernanceFeedback.mockRejectedValue(new Error('网络未知'))
  const w = page()
  await settle()
  ;(w.vm as any).open(row)
  fill(w.vm)
  await (w.vm as any).review(true)
  await (w.vm as any).review(true)
  expect(s.api.reviewGovernanceFeedback).toHaveBeenCalledTimes(1)
  expect(pendingGovernanceWrites.value[0].state).toBe('unknown')
  w.unmount()
})
it('旧反馈合并授权范围，GET不添加公共源过滤，403由服务端逐行拒绝', async () => {
  s.context.datasourceId = 6
  const w = page(Legacy)
  await settle()
  expect(s.api.listPendingReviews).toHaveBeenCalledWith({ page: 1, pageSize: 20 })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  s.api.approveFeedback.mockRejectedValue({ response: { status: 403, data: { message: '实际字段源无权' } } })
  await (w.vm as any).handleApprove(91)
  expect(s.api.approveFeedback).toHaveBeenCalledWith(91)
  expect((w.vm as any).error).toBe('实际字段源无权')
  w.unmount()
})
it('旧反馈重复确认只一弹窗，确认后撤权不发送', async () => {
  const d = deferred(),
    confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as never)
  const w = page(Legacy)
  await settle()
  const a = (w.vm as any).handleApprove(91)
  await (w.vm as any).handleApprove(91)
  expect(confirm).toHaveBeenCalledTimes(1)
  reactive(s.iam).snapshot = { r: 2 }
  reactive(s.iam).codes = ['governance:field:view']
  await settle()
  d.resolve('confirm')
  await a
  expect(s.api.approveFeedback).not.toHaveBeenCalled()
  w.unmount()
})
it('旧反馈网络未知跨重挂载留目标锁，同源新账号旧列表失效', async () => {
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  s.api.approveFeedback.mockRejectedValue(new Error('unknown'))
  const w = page(Legacy)
  await settle()
  await (w.vm as any).handleApprove(91)
  w.unmount()
  const next = page(Legacy)
  await settle()
  await (next.vm as any).handleApprove(91)
  expect(s.api.approveFeedback).toHaveBeenCalledTimes(1)
  expect(pendingGovernanceWrites.value[0].state).toBe('unknown')
  next.unmount()
})

it('旧反馈读取失败不渲染正常空表', async () => {
  s.api.listPendingReviews.mockRejectedValue(new Error('读取失败'))
  const w = page(Legacy)
  await settle()
  expect((w.vm as any).error).toContain('读取失败')
  expect(w.findComponent({ name: 'ElTable' }).exists()).toBe(false)
  w.unmount()
})
