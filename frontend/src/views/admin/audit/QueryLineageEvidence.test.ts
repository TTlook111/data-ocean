// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { reactive } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({
  auth: { token: 'A' },
  iam: { loaded: true, errorMessage: null, snapshot: {}, canOnDatasource: vi.fn() },
  impact: vi.fn(),
  table: vi.fn(),
  column: vi.fn(),
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(state.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(state.iam) }))
vi.mock('../../../api/admin/audit', () => ({
  analyzeImpact: state.impact,
  queryTableLineage: state.table,
  queryColumnLineage: state.column,
}))
import Page from './QueryLineageEvidence.vue'
function page(props: any) {
  return shallowMount(Page, { props, global: { plugins: [ElementPlus] } })
}
function deferred() {
  let resolve!: (x: any) => void, reject!: (x: any) => void
  const promise = new Promise<any>((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
beforeEach(() => {
  state.auth.token = 'A'
  state.iam.loaded = true
  state.iam.canOnDatasource.mockReset().mockReturnValue(true)
  state.impact
    .mockReset()
    .mockResolvedValue({
      data: { dependentQueryCount: 125, pendingDatasourceQueryCount: 2, recentQueryTaskIds: [701] },
    })
  state.table.mockReset().mockResolvedValue({ data: [] })
  state.column.mockReset().mockResolvedValue({ data: [] })
})
it('lineage:view足够，不借用audit/SQL权；total不等于明细长度', async () => {
  const w = page({ datasourceId: 5, table: 'orders' })
  await flushPromises()
  expect(state.iam.canOnDatasource).toHaveBeenCalledWith('lineage:view', 5)
  expect(w.text()).toContain('125')
  expect(w.text()).toContain('整个数据源待投影')
  expect(w.text()).toContain('0 条记录不能证明无人使用')
  expect(w.findAll('a')).toHaveLength(0)
  w.unmount()
})
it('摘要失败仍保留成功明细，失败不显示空记录', async () => {
  state.impact.mockRejectedValue(new Error('摘要失败'))
  state.table.mockResolvedValue({
    data: [{ queryTaskId: 701, question: '已记录问题', relationType: 'USAGE', sourceTable: 'orders' }],
  })
  const w = page({ datasourceId: 5, table: 'orders' })
  await flushPromises()
  expect((w.vm as any).rows).toHaveLength(1)
  expect((w.vm as any).impactError).toBe('摘要失败')
  w.unmount()
})
it('换源后旧成功和失败不能覆盖新结果及loading', async () => {
  const a = deferred(),
    b = deferred()
  state.impact.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
  const w = page({ datasourceId: 5, table: 'orders' })
  await w.setProps({ datasourceId: 6 })
  a.reject(new Error('旧源错误'))
  await flushPromises()
  expect((w.vm as any).loading).toBe(true)
  expect((w.vm as any).impactError).toBe('')
  b.resolve({ data: { dependentQueryCount: 8 } })
  await flushPromises()
  expect((w.vm as any).impact.dependentQueryCount).toBe(8)
  w.unmount()
})
it('卸载或账号变化后不回填上一账号来源', async () => {
  const p = deferred()
  state.impact.mockReturnValue(p.promise)
  const w = page({ datasourceId: 5, table: 'orders' })
  reactive(state.auth).token = 'B'
  state.iam.canOnDatasource.mockReturnValue(false)
  reactive(state.iam).loaded = false
  await flushPromises()
  p.resolve({ data: { dependentQueryCount: 99 } })
  await flushPromises()
  expect((w.vm as any).impact).toBeNull()
  w.unmount()
})
it('重复点击读取只发一批请求，精确字段独立API', async () => {
  const p = deferred()
  state.impact.mockReturnValue(p.promise)
  const w = page({ datasourceId: 5, table: 'orders', column: 'amount' })
  await (w.vm as any).load()
  expect(state.impact).toHaveBeenCalledTimes(1)
  expect(state.column).toHaveBeenCalledWith(5, 'orders', 'amount')
  expect(state.table).not.toHaveBeenCalled()
  w.unmount()
  p.resolve({ data: {} })
})
