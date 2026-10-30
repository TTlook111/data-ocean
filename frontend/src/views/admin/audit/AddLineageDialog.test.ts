// @vitest-environment happy-dom
import ElementPlus from 'element-plus'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({
  auth: { token: 'A' },
  iam: {
    loaded: true,
    errorMessage: null,
    snapshot: {},
    systemAdmin: true,
    datasourceCapabilities: [],
    canOnDatasource: vi.fn(),
  },
  detail: vi.fn(),
  search: vi.fn(),
  create: vi.fn(),
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(state.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(state.iam) }))
vi.mock('../../../api/admin/catalog', () => ({ getEntityDetail: state.detail, searchCatalog: state.search }))
vi.mock('../../../api/admin/lineageApi', () => ({ createLineage: state.create }))
import Page from './AddLineageDialog.vue'
import { pendingLineageWrites } from '../../../composables/lineageWriteTracker'
function table(id: number) {
  return { id, name: `t${id}`, fqn: `db.t${id}`, entityType: 'TABLE', entityMetadata: '{"datasource_id":5}' }
}
function deferred() {
  let resolve!: (x: any) => void
  const promise = new Promise<any>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
function mountPage() {
  return mount(Page, {
    props: { visible: true },
    global: {
      plugins: [ElementPlus],
      stubs: { 'el-dialog': { template: '<div><slot/><slot name="footer"/></div>' } },
    },
  })
}
beforeEach(() => {
  state.auth.token = 'A'
  state.iam.canOnDatasource.mockReset().mockReturnValue(true)
  state.iam.systemAdmin = true
  state.search.mockReset().mockResolvedValue({ data: [] })
  state.create.mockReset().mockResolvedValue({ data: {} })
  pendingLineageWrites.value = []
})
it('换表立即清旧列与映射，迟到列不回填', async () => {
  const old = deferred()
  state.search.mockReturnValueOnce(old.promise).mockResolvedValue({ data: [] })
  const w = mountPage()
  ;(w.vm as any).selectedSource = table(1)
  await flushPromises()
  ;(w.vm as any).mappings = [{ id: 1, fromColumnIds: [9], toColumnId: 10 }]
  ;(w.vm as any).selectedSource = table(2)
  await flushPromises()
  old.resolve({
    data: [{ id: 9, fqn: 'db.t1.c', entityType: 'COLUMN', entityMetadata: '{"datasource_id":5}' }],
  })
  await flushPromises()
  expect((w.vm as any).sourceColumnOptions).toEqual([])
  expect((w.vm as any).mappings).toEqual([])
  w.unmount()
})
it('不完整映射阻止提交而非静默丢行', async () => {
  const w = mountPage()
  ;(w.vm as any).selectedSource = table(1)
  ;(w.vm as any).selectedTarget = table(2)
  await flushPromises()
  ;(w.vm as any).addMapping()
  await (w.vm as any).handleSubmit()
  expect(state.create).not.toHaveBeenCalled()
  expect((w.vm as any).canSubmit).toBe(false)
  w.unmount()
})
it('冻结一次提交，重复点击不发第二次，换账号后旧成功不关闭新草稿', async () => {
  const p = deferred()
  state.create.mockReturnValue(p.promise)
  const w = mountPage()
  ;(w.vm as any).selectedSource = table(1)
  ;(w.vm as any).selectedTarget = table(2)
  await flushPromises()
  const a = (w.vm as any).handleSubmit()
  await (w.vm as any).handleSubmit()
  expect(state.create).toHaveBeenCalledTimes(1)
  reactive(state.auth).token = 'B'
  await flushPromises()
  p.resolve({ data: {} })
  await a
  expect(w.emitted('created')).toBeUndefined()
  w.unmount()
})
it('无metadata:view不发辅助表搜索', async () => {
  state.iam.systemAdmin = false
  const w = mountPage()
  await (w.vm as any).searchTables('orders', 'source')
  expect(state.search).not.toHaveBeenCalled()
  w.unmount()
})

it('只提交当前两表精确列，包含点号字段名不被错误丢弃', async () => {
  const a = {
    id: 11,
    entityType: 'COLUMN',
    name: 'amount.net',
    fqn: 'db.t1.amount.net',
    entityMetadata: '{"datasource_id":5,"table_name":"t1"}',
  }
  const b = {
    id: 12,
    entityType: 'COLUMN',
    name: 'total',
    fqn: 'db.t2.total',
    entityMetadata: '{"datasource_id":5,"table_name":"t2"}',
  }
  state.search.mockImplementation(async ({ q }: any) => ({ data: q === 'db.t1' ? [a] : [b] }))
  const w = mountPage()
  ;(w.vm as any).selectedSource = table(1)
  ;(w.vm as any).selectedTarget = table(2)
  await flushPromises()
  expect((w.vm as any).sourceColumnOptions.map((x: any) => x.id)).toEqual([11])
  ;(w.vm as any).mappings = [
    {
      id: 1,
      fromColumnIds: [11],
      toColumnId: 12,
      expression: 'SUM(amount.net)',
      transformationType: 'AGGREGATION',
    },
  ]
  await (w.vm as any).handleSubmit()
  expect(state.create).toHaveBeenCalledWith(
    expect.objectContaining({
      sourceEntityId: 1,
      targetEntityId: 2,
      columnMappings: [
        { fromColumns: [11], toColumn: 12, expression: 'SUM(amount.net)', transformationType: 'AGGREGATION' },
      ],
    }),
  )
  w.unmount()
})
it('未知创建按端点LINEAGE锁定，改变ETL/MANUAL不能重写同边', async () => {
  state.create.mockRejectedValue(new Error('网络结果未知'))
  const w = mountPage()
  ;(w.vm as any).selectedSource = table(1)
  ;(w.vm as any).selectedTarget = table(2)
  await flushPromises()
  await (w.vm as any).handleSubmit()
  ;(w.vm as any).lineageType = 'MANUAL'
  await (w.vm as any).handleSubmit()
  expect(state.create).toHaveBeenCalledTimes(1)
  w.unmount()
})
