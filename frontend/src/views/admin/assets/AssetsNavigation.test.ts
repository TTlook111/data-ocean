// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AssetsView from './AssetsView.vue'

const state = vi.hoisted(() => ({
  auth: null as any,
  context: null as any,
  iam: null as any,
  route: null as any,
}))
vi.mock('../../../stores/auth', async () => {
  const { reactive } = await import('vue')
  state.auth = reactive({ token: 'synthetic-auth' })
  return { useAuthStore: () => state.auth }
})
vi.mock('../../../stores/iamS1', async () => {
  const { reactive } = await import('vue')
  state.iam = reactive({
    loaded: true,
    errorMessage: null,
    snapshot: { revision: 1 },
    allowed: true,
    canOnDatasource: () => state.iam.allowed,
    load: async () => {},
  })
  return { useIamS1Store: () => state.iam }
})
const api = vi.hoisted(() => ({ getPublishedCatalog: vi.fn(), getDatasourceReadiness: vi.fn() }))
vi.mock('../../../api/admin/catalog', () => ({ getPublishedCatalog: api.getPublishedCatalog }))
vi.mock('../../../api/admin/datasource', () => ({ getDatasourceReadiness: api.getDatasourceReadiness }))
vi.mock('../../../stores/adminContext', async () => {
  const { reactive } = await import('vue')
  state.context = reactive({ datasourceId: 2, initialize: async () => {} })
  return { useAdminContextStore: () => state.context }
})
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => state.route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))

const wrappers: VueWrapper[] = []

beforeEach(() => {
  vi.clearAllMocks()
  state.route = reactive({ query: { datasourceId: '2' } })
  state.auth.token = 'synthetic-auth'
  state.context.datasourceId = 2
  state.iam.allowed = true
  state.context.datasources = [
    { id: 1, name: 'source-one' },
    { id: 2, name: 'source-two' },
  ]
  state.context.selectDatasource = vi.fn(async (id: number) => {
    state.context.datasourceId = id
  })
  api.getDatasourceReadiness.mockRejectedValue(new Error('没有“查看数据源”权限'))
  api.getPublishedCatalog.mockResolvedValue({
    data: {
      datasourceId: 2,
      publishedSnapshotId: 3,
      snapshotVersion: 2,
      syncStatus: 'SYNCED',
      tableCount: 1,
      columnCount: 3,
      entities: [{ id: 20, entityType: 'TABLE', name: 'products', fqn: 'fixture.products' }],
    },
  })
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))

it.each([
  { view: 'catalog', allowed: false },
  { view: 'catalog', allowed: true },
  { view: 'tables', allowed: false },
  { view: 'tables', allowed: true },
])('无源目录$view接入入口按目标查看权(allowed=$allowed)', async ({ view, allowed }) => {
  state.route.query = { view }
  state.context.datasourceId = undefined
  state.iam.hasGlobal = () => false
  state.iam.datasourcesWithFunction = (code: string) => (code === 'datasource:view' && allowed ? [2] : [])
  const w = mount(AssetsView, {
    global: { plugins: [ElementPlus], stubs: { ScopeBar: true, TableExplorer: true } },
  })
  try {
    await flushPromises()
    expect(w.findAll('button').some((x) => x.text() === '去数据源接入')).toBe(allowed)
    expect(api.getPublishedCatalog).not.toHaveBeenCalled()
  } finally {
    w.unmount()
  }
})
