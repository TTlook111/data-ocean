// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AssetsView from './AssetsView.vue'

const api = vi.hoisted(() => ({ getPublishedCatalog: vi.fn(), getDatasourceReadiness: vi.fn() }))
vi.mock('../../../api/admin/catalog', () => ({ getPublishedCatalog: api.getPublishedCatalog }))
vi.mock('../../../api/admin/datasource', () => ({ getDatasourceReadiness: api.getDatasourceReadiness }))
vi.mock('../../../stores/adminContext', () => ({
  useAdminContextStore: () => ({ datasourceId: 2, initialize: async () => {} }),
}))
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal<typeof import('vue-router')>(),
  useRoute: () => ({ query: { datasourceId: '2' } }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

const Message = defineComponent({ props: ['message'], template: '<p>{{ message }}</p>' })
const wrappers: VueWrapper[] = []
async function render() {
  const wrapper = mount(AssetsView, {
    global: {
      plugins: [ElementPlus],
      stubs: { ScopeBar: true, TableExplorer: true, ErrorState: Message, EmptyState: Message },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
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
