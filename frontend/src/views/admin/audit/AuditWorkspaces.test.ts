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
import Audit from './AuditLogList.vue'
import Slow from './SlowQueryList.vue'
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
it('最小audit:view仅使用能力摘要源选项，不依赖datasource:view', async () => {
  const w = shallowMount(Audit, { global: { plugins: [ElementPlus] } })
  await settle()
  expect((w.vm as any).datasources).toEqual([{ id: 5, name: '源5' }])
  expect(state.api.listAuditLogs).toHaveBeenCalledWith(expect.objectContaining({ datasourceId: undefined }))
  w.unmount()
})
it.each(['bad', '0', ['5', '6'], '6'])('无效或无权源%s不回退全源', async (source) => {
  state.route.query = { datasourceId: source }
  const w = shallowMount(Audit, { global: { plugins: [ElementPlus] } })
  await settle()
  expect(state.api.listAuditLogs).not.toHaveBeenCalled()
  expect(state.api.getAuditStats).not.toHaveBeenCalled()
  w.unmount()
})
it('精确源和分页恢复，统计不发送关键词状态过滤', async () => {
  state.route.query = { datasourceId: '5', page: '2', keyword: '订单', isSuccess: 'false' }
  const w = shallowMount(Audit, { global: { plugins: [ElementPlus] } })
  await settle()
  expect(state.api.listAuditLogs).toHaveBeenCalledWith(
    expect.objectContaining({ datasourceId: 5, pageNo: 2, keyword: '订单', isSuccess: false }),
  )
  expect(state.api.getAuditStats).toHaveBeenCalledWith({ datasourceId: 5, days: 30 })
  reactive(state.route).query = { datasourceId: '5', page: '1' }
  await settle()
  expect((w.vm as any).query.pageNo).toBe(1)
  expect((w.vm as any).query.keyword).toBe('')
  w.unmount()
})
it('倒序列表和统计回包不能覆盖新范围', async () => {
  const a = deferred(),
    b = deferred(),
    stats = deferred()
  state.api.listAuditLogs.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
  state.api.getAuditStats.mockReturnValueOnce(stats.promise)
  const w = shallowMount(Audit, { global: { plugins: [ElementPlus] } })
  await settle()
  ;(w.vm as any).query.keyword = 'new'
  ;(w.vm as any).handleSearch()
  await settle()
  a.resolve({ data: { records: [{ id: 1 }], total: 100 } })
  await settle()
  expect((w.vm as any).loading).toBe(true)
  b.resolve({ data: { records: [{ id: 2 }], total: 2 } })
  await settle()
  expect((w.vm as any).logs[0].id).toBe(2)
  reactive(state.auth).token = 'B'
  reactive(state.iam).loaded = false
  stats.resolve({ data: { totalQueries: 999 } })
  await settle()
  expect((w.vm as any).stats).toBeNull()
  expect((w.vm as any).logs).toEqual([])
  w.unmount()
})
it('详情失败按冻结原id重试，SQL默认剥离且再次服务端读取', async () => {
  state.api.getAuditLogDetail.mockRejectedValueOnce(new Error('详情失败'))
  const w = shallowMount(Audit, { global: { plugins: [ElementPlus] } })
  await settle()
  await (w.vm as any).openDetail(record)
  expect((w.vm as any).detailError).toBe('详情失败')
  ;(w.vm as any).retryDetail()
  await settle()
  expect((w.vm as any).detail.id).toBe(8)
  expect((w.vm as any).detail.sqlText).toBeUndefined()
  expect((w.vm as any).sqlText).toBeNull()
  await (w.vm as any).readAuditSql()
  expect((w.vm as any).sqlText).toBe('SELECT protected')
  expect(state.api.getAuditLogDetail).toHaveBeenCalledTimes(3)
  w.unmount()
})
it('SQL迟到/关闭/撤权不回填，详情返回错源拒绝', async () => {
  const p = deferred()
  const w = shallowMount(Audit, { global: { plugins: [ElementPlus] } })
  await settle()
  await (w.vm as any).openDetail(record)
  state.api.getAuditLogDetail.mockReturnValueOnce(p.promise)
  const request = (w.vm as any).readAuditSql()
  ;(w.vm as any).closeDetail()
  p.resolve({ data: record })
  await request
  expect((w.vm as any).sqlText).toBeNull()
  state.api.getAuditLogDetail.mockResolvedValue({ data: { ...record, datasourceId: 6 } })
  await (w.vm as any).openDetail(record)
  expect((w.vm as any).detailError).toContain('不一致')
  expect((w.vm as any).detail).toBeNull()
  w.unmount()
})
it('慢查询仅传page/pageSize、URL分页恢复、去掉默认SQL', async () => {
  state.route.query = { datasourceId: '5', slowPage: '2' }
  const w = shallowMount(Slow, { global: { plugins: [ElementPlus] } })
  await settle()
  expect(state.api.listSlowQueries).toHaveBeenCalledWith({ page: 2, pageSize: 20 })
  expect((w.vm as any).logs[0].sqlText).toBeUndefined()
  reactive(state.route).query = { slowPage: '3' }
  await settle()
  expect(state.api.listSlowQueries).toHaveBeenLastCalledWith({ page: 3, pageSize: 20 })
  w.unmount()
})
it('慢查询账号与卸载隔离，失败不冒充空', async () => {
  const p = deferred()
  state.api.listSlowQueries.mockReturnValueOnce(p.promise).mockRejectedValue(new Error('新账号失败'))
  const w = shallowMount(Slow, { global: { plugins: [ElementPlus] } })
  reactive(state.auth).token = 'B'
  await settle()
  p.resolve({ data: { records: [record], total: 42 } })
  await settle()
  expect((w.vm as any).logs).toEqual([])
  expect((w.vm as any).error).toBe('新账号失败')
  w.unmount()
})

it('普通关键词筛选不解除非法源范围', async () => {
  state.route.query = { datasourceId: 'invalid' }
  const w = shallowMount(Audit, { global: { plugins: [ElementPlus] } })
  ;(w.vm as any).query.keyword = '订单'
  ;(w.vm as any).handleSearch()
  await settle()
  expect(state.api.listAuditLogs).not.toHaveBeenCalled()
  expect(state.replace).not.toHaveBeenCalled()
  w.unmount()
})
