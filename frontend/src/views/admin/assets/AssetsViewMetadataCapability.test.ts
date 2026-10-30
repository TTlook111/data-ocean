// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AssetsView from './AssetsView.vue'

const state = vi.hoisted(() => ({ auth: null as any, context: null as any, iam: null as any, route: null as any }))
vi.mock('../../../stores/auth', async () => { const { reactive } = await import('vue'); state.auth = reactive({ token: 'synthetic-auth' }); return { useAuthStore: () => state.auth } })
vi.mock('../../../stores/iamS1', async () => { const { reactive } = await import('vue'); state.iam = reactive({ loaded: true, errorMessage: null, snapshot: { revision: 1 }, allowed: true, canOnDatasource: () => state.iam.allowed, load: async () => {} }); return { useIamS1Store: () => state.iam } })
const api = vi.hoisted(() => ({ getPublishedCatalog: vi.fn(), getDatasourceReadiness: vi.fn() }))
vi.mock('../../../api/admin/catalog', () => ({ getPublishedCatalog: api.getPublishedCatalog }))
vi.mock('../../../api/admin/datasource', () => ({ getDatasourceReadiness: api.getDatasourceReadiness }))
vi.mock('../../../stores/adminContext', async () => { const { reactive } = await import('vue'); state.context = reactive({ datasourceId: 2, initialize: async () => {} }); return { useAdminContextStore: () => state.context } })
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal<typeof import('vue-router')>(),
  useRoute: () => state.route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

const Message = defineComponent({ props: ['message'], template: '<p>{{ message }}</p>' })
const wrappers: VueWrapper[] = []
async function render(realScope = false) {
  const wrapper = mount(AssetsView, {
    global: {
      plugins: [ElementPlus],
      stubs: { ScopeBar: realScope ? false : true, TableExplorer: true, ErrorState: Message, EmptyState: Message },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  state.route = reactive({ query: { datasourceId: '2' } })
  state.auth.token = 'synthetic-auth'; state.context.datasourceId = 2; state.iam.allowed = true
  state.context.datasources = [{ id: 1, name: 'source-one' }, { id: 2, name: 'source-two' }]
  state.context.selectDatasource = vi.fn(async (id: number) => { state.context.datasourceId = id })
  api.getDatasourceReadiness.mockRejectedValue(new Error('没有“查看数据源”权限'))
  api.getPublishedCatalog.mockResolvedValue({ data: {
    datasourceId: 2, publishedSnapshotId: 3, snapshotVersion: 2,
    syncStatus: 'SYNCED', tableCount: 1, columnCount: 3,
    entities: [{ id: 20, entityType: 'TABLE', name: 'products', fqn: 'fixture.products' }],
  } })
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('资产目录沿用自身授权和正式快照身份', () => {
  it('只有 metadata:view 时仍读取已授权目录，不要求额外 datasource:view', async () => {
    const wrapper = await render()
    expect(api.getPublishedCatalog).toHaveBeenCalledWith(2)
    expect(api.getDatasourceReadiness).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已发布快照 v2')
    expect(wrapper.text()).toContain('当前范围 1 张表 · 3 个字段')
    expect(wrapper.text()).not.toContain('没有“查看数据源”权限')
  })

  it('目录没有正式快照时明确空状态，不把其他来源身份拼成已发布', async () => {
    api.getPublishedCatalog.mockResolvedValue({ data: {
      datasourceId: 2, tableCount: 0, columnCount: 0, entities: [],
    } })
    const wrapper = await render()
    expect(wrapper.text()).toContain('当前数据源没有已发布快照')
    expect(wrapper.text()).not.toContain('范围状态读取失败')
    expect(api.getDatasourceReadiness).not.toHaveBeenCalled()
  })

  it('目录接口失败仍显示错误，不能伪装成未发布或零对象', async () => {
    api.getPublishedCatalog.mockRejectedValue(new Error('目录读取失败'))
    const wrapper = await render()
    expect(wrapper.text()).toContain('目录读取失败')
    expect(wrapper.text()).not.toContain('当前数据源没有已发布快照')
  })
})

describe('目录异步与撤权', () => {
  it('错误来源身份不展示成当前正式目录', async () => {
    api.getPublishedCatalog.mockResolvedValue({ data: { datasourceId: 1, publishedSnapshotId: 3, entities: [] } })
    const wrapper = await render(); expect(wrapper.text()).toContain('目录来源与当前数据源不一致')
  })
  it('撤权立即清除正式对象并停止额外读取', async () => {
    const wrapper = await render(); const count = api.getPublishedCatalog.mock.calls.length
    state.iam.allowed = false; state.iam.snapshot = { revision: 2 }; await flushPromises()
    expect(wrapper.text()).toContain('没有指定数据源的元数据查看权限')
    expect(wrapper.text()).not.toContain('products'); expect(api.getPublishedCatalog).toHaveBeenCalledTimes(count)
  })
  it('换账号后的迟到旧目录不回填', async () => {
    let resolve!: (value: unknown) => void
    api.getPublishedCatalog.mockReturnValueOnce(new Promise(r => { resolve = r })).mockResolvedValueOnce({ data: { datasourceId: 2, publishedSnapshotId: 3, snapshotVersion: 4, syncStatus: 'SYNCED', entities: [] } })
    const wrapper = await render(); state.auth.token = 'new-identity'; await flushPromises()
    resolve({ data: { datasourceId: 2, publishedSnapshotId: 3, snapshotVersion: 99, entities: [] } }); await flushPromises()
    expect(wrapper.text()).toContain('已发布快照 v4'); expect(wrapper.text()).not.toContain('v99')
  })
})

describe('显式数据源不能使用偏好源替代', () => {
 for (const view of ['catalog','tables']) it(`URL源2与偏好源1不一致时不读取源1 ${view}`, async () => {
   state.context.datasourceId = 1; state.route.query = { datasourceId: '2', view }
   const wrapper = await render()
   expect(api.getPublishedCatalog).not.toHaveBeenCalled()
   expect(wrapper.text()).toContain('未改用其他数据源')
   expect(wrapper.text()).not.toContain('products')
   expect(wrapper.findComponent({ name: 'TableExplorer' }).exists()).toBe(false)
 })
 it('非法显式源不改用偏好源', async () => {
   state.route.query.datasourceId = 'invalid'
   const wrapper = await render(); expect(api.getPublishedCatalog).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('数据源标识无效')
 })
})

it('真实ScopeBar独立挂载，有权跨源结构深链可完成同步', async () => {
  state.context.datasourceId = 1; state.route.query = { datasourceId: '2', view: 'tables' }
  const wrapper = await render(true)
  expect(state.context.selectDatasource).toHaveBeenCalledWith(2)
  expect(state.context.datasourceId).toBe(2)
  expect(wrapper.findComponent({ name: 'TableExplorer' }).exists()).toBe(true)
  expect(api.getPublishedCatalog).toHaveBeenCalledWith(2)
})


it('raw long comment cannot replace the stable object title and remains expandable',async()=>{
 const comment='UNTRUSTED_COMMENT '.repeat(35)
 api.getPublishedCatalog.mockResolvedValue({data:{datasourceId:2,publishedSnapshotId:3,snapshotVersion:2,syncStatus:'SYNCED',entities:[{id:20,entityType:'TABLE',name:'products',displayName:comment,description:comment,fqn:'fixture.products'}]}})
 const w=await render();expect(w.get('.asset-open').text()).toBe('products')
 expect(w.get('.asset-name').text()).not.toContain('UNTRUSTED_COMMENT')
 expect(w.get('details.asset-comment').text()).toContain(comment.trim())
})
