// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'
const state = vi.hoisted(() => ({
  route: { query: { datasourceId: '5' } as Record<string, unknown> },
  auth: { token: 'A' },
  iam: {
    loaded: true,
    errorMessage: null as string | null,
    snapshot: {} as unknown,
    canOnDatasource: vi.fn(),
  },
  graph: { options: null as any, render: vi.fn() },
  api: {
    getEntitiesByDatasource: vi.fn(),
    searchCatalog: vi.fn(),
    getEnrichedLineage: vi.fn(),
    getColumnLineage: vi.fn(),
    deleteLineage: vi.fn(),
    batchCreateLineage: vi.fn(),
  },
  replace: vi.fn(),
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => reactive(state.route),
  useRouter: () => ({ replace: state.replace }),
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(state.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(state.iam) }))
vi.mock('../../../stores/adminContext', () => ({
  useAdminContextStore: () => ({ datasourceId: 5, initialize: vi.fn().mockResolvedValue(undefined) }),
}))
vi.mock('../../../api/admin/catalog', () => ({
  getEntitiesByDatasource: state.api.getEntitiesByDatasource,
  searchCatalog: state.api.searchCatalog,
}))
vi.mock('../../../api/admin/lineageApi', () => state.api)
vi.mock('../../../composables/useD3LineageGraph', () => ({
  useD3LineageGraph: (_target: any, options: any) => {
    state.graph.options = options
    return {
      init: vi.fn(),
      render: state.graph.render,
      destroy: vi.fn(),
      resize: vi.fn(),
      exportPngAsync: vi.fn(),
    }
  },
}))
import DataLineage from './DataLineage.vue'
import { pendingLineageWrites } from '../../../composables/lineageWriteTracker'
function entity(id: number, source = 5, type = 'TABLE') {
  return {
    id,
    entityType: type,
    name: `table${id}`,
    displayName: `对象${id}`,
    fqn: `ds.db.table${id}`,
    entityMetadata: JSON.stringify({ datasource_id: source, table_name: 'orders' }),
    entityUuid: String(id),
    version: 1,
  }
}
const nodes = [entity(1), entity(2), entity(3, 5, 'COLUMN')],
  edge = {
    id: 50,
    sourceId: 1,
    targetId: 2,
    relationType: 'LINEAGE',
    relationMetadata: '{"lineage_type":"ETL"}',
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
  for (let i = 0; i < 4; i++) {
    await flushPromises()
    await nextTick()
  }
}
function mountPage() {
  return mount(DataLineage, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { template: '<a><slot/></a>' },
        AddLineageDialog: true,
        QueryLineageEvidence: true,
        Teleport: true,
      },
    },
  })
}
beforeEach(() => {
  state.route.query = { datasourceId: '5' }
  state.auth.token = 'A'
  state.iam.loaded = true
  state.iam.errorMessage = null
  state.iam.snapshot = {}
  state.iam.canOnDatasource.mockReset().mockReturnValue(true)
  Object.values(state.api).forEach((x) => x.mockReset())
  state.replace.mockReset()
  state.graph.render.mockReset()
  pendingLineageWrites.value = []
  state.api.getEntitiesByDatasource.mockResolvedValue({ data: nodes })
  state.api.getEnrichedLineage.mockResolvedValue({ data: { nodes, edges: [edge] } })
  state.api.getColumnLineage.mockResolvedValue({ data: { upstream: [], downstream: [] } })
  state.api.deleteLineage.mockResolvedValue({ data: { deleted: true } })
})
describe('血缘精确对象与作用域', () => {
  it('非首表深链按该实体读取，点击保留源写回URL', async () => {
    state.route.query = { datasourceId: '5', entityId: '2' }
    const w = mountPage()
    await settle()
    expect(state.api.getEnrichedLineage).toHaveBeenCalledWith(2, 3, ['QUERY', 'ETL', 'MANUAL'])
    state.graph.options.onNodeClick({ entity: entity(1) })
    expect(state.replace).toHaveBeenCalledWith({ query: { datasourceId: '5', entityId: '1' } })
    w.unmount()
  })
  it('不存在的精确实体不回退首表（修正旧隐式替换断言）', async () => {
    state.route.query = { datasourceId: '5', entityId: '999' }
    const w = mountPage()
    await settle()
    expect(state.api.getEnrichedLineage).not.toHaveBeenCalled()
    expect(w.text()).toContain('未替换为其他对象')
    w.unmount()
  })
  it.each(['abc', '0', ['5', '6']])('非法显式源 %s 不替换偏好源', async (id) => {
    state.route.query = { datasourceId: id }
    const w = mountPage()
    await settle()
    expect(state.api.getEntitiesByDatasource).not.toHaveBeenCalled()
    expect(w.text()).toContain('无效数据源')
    w.unmount()
  })
  it('无目录权限仍按精确entity直接读取', async () => {
    state.iam.canOnDatasource.mockImplementation((code) => code === 'lineage:view')
    state.route.query = { datasourceId: '5', entityId: '2' }
    const w = mountPage()
    await settle()
    expect(state.api.getEntitiesByDatasource).not.toHaveBeenCalled()
    expect(state.api.getEnrichedLineage).toHaveBeenCalledWith(2, 3, ['QUERY', 'ETL', 'MANUAL'])
    expect(w.text()).toContain('没有 metadata:view')
    w.unmount()
  })
  it('空目录说明发布同步并保留版本入口', async () => {
    state.api.getEntitiesByDatasource.mockResolvedValue({ data: [] })
    const w = mountPage()
    await settle()
    expect(w.text()).toContain('对象目录来自已发布快照的目录同步')
    w.unmount()
  })
  it('全不选类型不触发无过滤兼容API', async () => {
    const w = mountPage()
    await settle()
    state.api.getEnrichedLineage.mockClear()
    ;(w.vm as any).lineageTypeFilter = []
    await settle()
    expect(state.api.getEnrichedLineage).not.toHaveBeenCalled()
    expect(w.text()).toContain('未选择关系来源类型')
    w.unmount()
  })
  it('主图加载期间新展开完成后旧目录不能覆盖或卡loading', async () => {
    const old = deferred()
    state.api.getEntitiesByDatasource.mockReturnValueOnce(old.promise)
    const w = mountPage()
    await settle()
    state.api.getEntitiesByDatasource.mockResolvedValue({ data: nodes })
    await (w.vm as any).loadEntityLineage(2)
    old.resolve({ data: [] })
    await settle()
    expect((w.vm as any).loading).toBe(false)
    expect((w.vm as any).focusEntityId).toBe('2')
    await (w.vm as any).loadGraph()
    expect(state.api.getEntitiesByDatasource).toHaveBeenCalledTimes(3)
    w.unmount()
  })
  it.each(['success', 'failure'])('旧增强读取%s不能覆盖新范围或解除新loading', async (outcome) => {
    const old = deferred(),
      latest = deferred()
    state.api.getEnrichedLineage.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const w = mountPage()
    await settle()
    const request = (w.vm as any).loadEntityLineage(2)
    await nextTick()
    if (outcome === 'success') old.resolve({ data: { nodes, edges: [{ ...edge, id: 99 }] } })
    else old.reject(new Error('旧请求失败'))
    await settle()
    expect((w.vm as any).loading).toBe(true)
    latest.resolve({ data: { nodes, edges: [{ ...edge, id: 60 }] } })
    await request
    await settle()
    expect((w.vm as any).relationships.map((x: any) => x.id)).toEqual([60])
    expect((w.vm as any).loading).toBe(false)
    w.unmount()
  })
  it('换账号撤权清关系与抽屉，旧回包不回填', async () => {
    const old = deferred()
    state.api.getEnrichedLineage.mockReturnValue(old.promise)
    const w = mountPage()
    await settle()
    reactive(state.auth).token = 'B'
    state.iam.canOnDatasource.mockReturnValue(false)
    reactive(state.iam).loaded = false
    old.resolve({ data: { nodes, edges: [edge] } })
    await settle()
    expect((w.vm as any).entities).toEqual([])
    expect((w.vm as any).drawerVisible).toBe(false)
    w.unmount()
  })
  it('跨源查询使用实体实际source', async () => {
    const w = mountPage()
    await settle()
    ;(w.vm as any).handleAnalyzeImpact(entity(20, 7))
    expect((w.vm as any).evidenceTarget.source).toBe(7)
    w.unmount()
  })
  it('平行关系按id选择，确认前零DELETE，重复确认一次', async () => {
    const pending = deferred()
    state.api.deleteLineage.mockReturnValue(pending.promise)
    const w = mountPage()
    await settle()
    state.graph.options.onEdgeContextMenu({ relationship: { ...edge, id: 52 } }, { clientX: 2, clientY: 2 })
    expect((w.vm as any).contextMenu.edge.id).toBe(52)
    ;(w.vm as any).requestDelete({ ...edge, id: 52 })
    expect(state.api.deleteLineage).not.toHaveBeenCalled()
    const a = (w.vm as any).confirmDelete()
    await (w.vm as any).confirmDelete()
    expect(state.api.deleteLineage).toHaveBeenCalledTimes(1)
    expect(state.api.deleteLineage).toHaveBeenCalledWith(52, false)
    pending.resolve({ data: { deleted: true } })
    await a
    w.unmount()
  })
  it('FOREIGN_KEY不能误用LINEAGE删除接口', async () => {
    const w = mountPage()
    await settle()
    ;(w.vm as any).requestDelete({ ...edge, relationType: 'FOREIGN_KEY' })
    expect((w.vm as any).deleteTarget).toBeNull()
    expect(state.api.deleteLineage).not.toHaveBeenCalled()
    w.unmount()
  })
})

it('增强根明确归属其他源时不能被目录同ID证明覆盖', async () => {
  state.api.getEnrichedLineage.mockResolvedValue({ data: { nodes: [entity(1, 6)], edges: [] } })
  const w = mountPage()
  await settle()
  expect((w.vm as any).graphError).toContain('无法确认指定实体')
  expect((w.vm as any).entities).toEqual([])
  w.unmount()
})
it('A到B到A旧删除不能关闭新目标或解除新操作loading', async () => {
  const old = deferred(),
    latest = deferred()
  state.api.deleteLineage.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
  const w = mountPage()
  await settle()
  ;(w.vm as any).requestDelete(edge)
  const a = (w.vm as any).confirmDelete()
  reactive(state.route).query = { datasourceId: '6' }
  await settle()
  reactive(state.route).query = { datasourceId: '5' }
  await settle()
  ;(w.vm as any).requestDelete({ ...edge, id: 53 })
  const b = (w.vm as any).confirmDelete()
  old.resolve({ data: { deleted: true } })
  await a
  expect((w.vm as any).deleteTarget.id).toBe(53)
  expect((w.vm as any).deleting).toBe(true)
  latest.resolve({ data: { deleted: true } })
  await b
  w.unmount()
})
it('根仅view而实际跨源端点manage可打开预填创建', async () => {
  state.api.getEnrichedLineage.mockResolvedValue({ data: { nodes: [...nodes, entity(20, 7)], edges: [] } })
  state.iam.canOnDatasource.mockImplementation((code, id) => code !== 'lineage:manage' || id === 7)
  const w = mountPage()
  await settle()
  ;(w.vm as any).openCreate(20)
  expect((w.vm as any).addDialogVisible).toBe(true)
  expect((w.vm as any).prefilledSourceId).toBe(20)
  w.unmount()
})
it('网络未知删除保留可见真实目标并阻止重复', async () => {
  state.api.deleteLineage.mockRejectedValue(new Error('断网'))
  const w = mountPage()
  await settle()
  ;(w.vm as any).requestDelete(edge)
  await (w.vm as any).confirmDelete()
  await settle()
  expect(w.text()).toContain('delete:50')
  await (w.vm as any).confirmDelete()
  expect(state.api.deleteLineage).toHaveBeenCalledTimes(1)
  w.unmount()
})
