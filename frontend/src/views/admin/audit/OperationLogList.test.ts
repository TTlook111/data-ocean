// @vitest-environment happy-dom
import ElementPlus from 'element-plus'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  auth: { token: 'A' },
  iam: {
    loaded: true,
    errorMessage: null,
    snapshot: {},
    systemAdmin: false,
    datasourceCapabilities: [{ datasourceId: 5, datasourceName: '源5', functionCodes: ['audit:view'] }],
    datasourcesWithFunction: vi.fn(),
    canOnDatasource: vi.fn(),
    hasGlobal: vi.fn(),
  },
  api: {
    listAuditLogs: vi.fn(),
    getAuditStats: vi.fn(),
    getAuditLogDetail: vi.fn(),
    listSlowQueries: vi.fn(),
    listOperationLogs: vi.fn(),
  },
  replace: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => reactive(state.route),
  useRouter: () => ({ replace: state.replace }),
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(state.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(state.iam) }))
vi.mock('../../../api/admin/audit', () => state.api)
vi.mock('../../../api/admin/operation-log', () => state.api)
import Operation from '../system/OperationLogList.vue'
function deferred() {
  let resolve!: (x: any) => void, reject!: (x: any) => void
  const promise = new Promise<any>((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
const record = {
  id: 8,
  datasourceId: 5,
  queryTaskId: 701,
  question: '问题',
  sqlText: 'SELECT protected',
  isSuccess: true,
}
async function settle() {
  await flushPromises()
  await nextTick()
}
beforeEach(() => {
  state.route.query = {}
  state.auth.token = 'A'
  state.iam.loaded = true
  state.iam.snapshot = {}
  state.iam.datasourcesWithFunction.mockReset().mockReturnValue([5])
  state.iam.canOnDatasource.mockReset().mockImplementation((_code, id) => id === 5)
  state.iam.hasGlobal.mockReset().mockReturnValue(true)
  state.replace.mockReset()
  Object.values(state.api).forEach((x) => x.mockReset())
  state.api.listAuditLogs.mockResolvedValue({ data: { records: [record], total: 42 } })
  state.api.getAuditStats.mockResolvedValue({ data: { totalQueries: 125 } })
  state.api.getAuditLogDetail.mockResolvedValue({ data: record })
  state.api.listSlowQueries.mockResolvedValue({ data: { records: [record], total: 42 } })
  state.api.listOperationLogs.mockResolvedValue({
    data: { records: [{ id: 9, isSuccess: undefined }], total: 42 },
  })
})
it('操作日志不借audit:view，未知状态与缺失耗时不算失败/0', async () => {
  const w = shallowMount(Operation, { global: { plugins: [ElementPlus] } })
  await settle()
  expect((w.vm as any).statusLabel(undefined)).toBe('未记录')
  expect((w.vm as any).failedCount).toBe(0)
  expect((w.vm as any).avgExecutionMs).toBeNull()
  reactive(state.iam).loaded = false
  await settle()
  expect((w.vm as any).logs).toEqual([])
  w.unmount()
  state.iam.loaded = true
  state.iam.hasGlobal.mockReturnValue(false)
  const denied = shallowMount(Operation, { global: { plugins: [ElementPlus] } })
  await settle()
  expect(state.api.listOperationLogs).toHaveBeenCalledTimes(1)
  denied.unmount()
})
it('操作筛选/page/size写URL且后退恢复全部条件', async () => {
  state.route.query = { page: '2', size: '50', targetId: '11', isSuccess: 'false' }
  const w = shallowMount(Operation, { global: { plugins: [ElementPlus] } })
  await settle()
  expect(state.api.listOperationLogs).toHaveBeenCalledWith(
    expect.objectContaining({ page: 2, pageSize: 50, targetId: '11', isSuccess: false }),
  )
  ;(w.vm as any).handlePageSizeChange(10)
  expect(state.replace).toHaveBeenCalledWith({
    query: expect.objectContaining({ size: '10', targetId: '11' }),
  })
  reactive(state.route).query = { page: '3', size: '20', keyword: '旧条件' }
  await settle()
  expect((w.vm as any).query.page).toBe(3)
  expect((w.vm as any).query.targetId).toBe('')
  w.unmount()
})
it('操作详情取授权列表行，换账号/筛选同步关闭原参数', async () => {
  const p = deferred()
  const w = shallowMount(Operation, { global: { plugins: [ElementPlus] } })
  await settle()
  ;(w.vm as any).openDetail({ id: 9, requestParams: 'old' })
  expect((w.vm as any).detailVisible).toBe(true)
  state.api.listOperationLogs.mockReturnValueOnce(p.promise)
  reactive(state.auth).token = 'B'
  await settle()
  expect((w.vm as any).selectedLog).toBeUndefined()
  expect((w.vm as any).detailVisible).toBe(false)
  w.unmount()
  p.resolve({ data: { records: [{ id: 99 }], total: 1 } })
  await settle()
  expect((w.vm as any).logs).toEqual([])
})
it.each([
  { start: 'bad', end: 'also-bad' },
  { start: '2026-10-30 10:00:00', end: '2026-10-29 10:00:00' },
  { start: '2026-10-29 10:00:00' },
])('操作日志无效/倒置/单边时间不扩大查询 %s', async (range) => {
  state.route.query = range
  const w = shallowMount(Operation, { global: { plugins: [ElementPlus] } })
  await settle()
  expect(state.api.listOperationLogs).not.toHaveBeenCalled()
  expect((w.vm as any).timeError).not.toBe('')
  w.unmount()
})
