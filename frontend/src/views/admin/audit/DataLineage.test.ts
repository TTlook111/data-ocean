// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'

const routeState = vi.hoisted(() => ({ query: { datasourceId: '5' } as Record<string, unknown>, params: {} }))
const routerState = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }))
const graphOptions = vi.hoisted(() => ({ current: null as null | { onNodeClick?: (node: unknown) => void } }))
const api = vi.hoisted(() => ({
  getEntitiesByDatasource: vi.fn(),
  getEntityLineage: vi.fn(),
  searchCatalog: vi.fn(),
  getEnrichedLineage: vi.fn(),
  getColumnLineage: vi.fn(),
  deleteLineage: vi.fn(),
  analyzeImpact: vi.fn(),
  queryColumnLineage: vi.fn(),
  queryTableLineage: vi.fn(),
}))
const iam = vi.hoisted(() => ({ canOnDatasource: vi.fn(), loaded: true }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => routeState, useRouter: () => routerState }
})
vi.mock('../../../stores/adminContext', () => ({
  useAdminContextStore: () => ({
    datasourceId: 5,
    snapshotId: 8,
    currentDatasource: { id: 5, name: '销售库' },
    initialize: vi.fn().mockResolvedValue(undefined),
  }),
}))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => iam }))
vi.mock('../../../api/admin/catalog', () => ({
  getEntitiesByDatasource: api.getEntitiesByDatasource,
  getEntityLineage: api.getEntityLineage,
  searchCatalog: api.searchCatalog,
}))
vi.mock('../../../api/admin/lineageApi', () => ({
  getEnrichedLineage: api.getEnrichedLineage,
  getColumnLineage: api.getColumnLineage,
  deleteLineage: api.deleteLineage,
}))
vi.mock('../../../api/admin/audit', () => ({
  analyzeImpact: api.analyzeImpact,
  queryColumnLineage: api.queryColumnLineage,
  queryTableLineage: api.queryTableLineage,
}))
vi.mock('../../../composables/useD3LineageGraph', () => ({
  useD3LineageGraph: (_target: unknown, options: unknown) => {
    graphOptions.current = options as typeof graphOptions.current
    return {
      init: vi.fn(),
      render: vi.fn(),
      destroy: vi.fn(),
      resize: vi.fn(),
      exportPngAsync: vi.fn(),
    }
  },
}))

import DataLineage from './DataLineage.vue'

function entity(id: number, entityType: 'TABLE' | 'COLUMN', name: string) {
  return {
    id,
    entityType,
    entityUuid: `uuid-${id}`,
    fqn: `ds.db.${name}`,
    name,
    displayName: name,
    version: 1,
  }
}

function mountPage() {
  return mount(DataLineage, {
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        AddLineageDialog: true,
      },
    },
  })
}

async function settle() {
  for (let round = 0; round < 5; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

beforeEach(() => {
  routeState.query = { datasourceId: '5' }
  routerState.replace.mockReset()
  graphOptions.current = null
  Object.values(api).forEach((fn) => fn.mockReset())
  iam.canOnDatasource.mockReset().mockReturnValue(true)
  iam.loaded = true
  api.getEntitiesByDatasource.mockResolvedValue({
    data: [entity(1, 'TABLE', 'orders'), entity(2, 'TABLE', 'customers'), entity(3, 'COLUMN', 'amount')],
  })
  api.getEnrichedLineage.mockResolvedValue({ data: { edges: [], nodes: [], columnMappingsByEdge: {}, depth: 3 } })
  api.getColumnLineage.mockResolvedValue({ data: { upstream: [], downstream: [] } })
  api.getEntityLineage.mockResolvedValue({ data: [] })
})

describe('DataLineage 深链与状态', () => {
  it('entityId 深链到非首表时以该实体作为增强血缘起点', async () => {
    routeState.query = { datasourceId: '5', entityId: '2' }
    const wrapper = mountPage()
    await settle()

    expect(api.getEnrichedLineage).toHaveBeenCalledWith(2, 3, ['QUERY', 'ETL', 'MANUAL'])
    wrapper.unmount()
  })

  it('entityId 不存在时回退首表并提示', async () => {
    routeState.query = { datasourceId: '5', entityId: '999' }
    const wrapper = mountPage()
    await settle()

    expect(api.getEnrichedLineage).toHaveBeenCalledWith(1, 3, ['QUERY', 'ETL', 'MANUAL'])
    expect(wrapper.text()).toContain('指定对象不在当前数据源目录中')
    wrapper.unmount()
  })

  it('点击节点把焦点写回 URL 并保留数据源', async () => {
    const wrapper = mountPage()
    await settle()

    graphOptions.current?.onNodeClick?.({ entity: entity(2, 'TABLE', 'customers') })
    await nextTick()

    expect(routerState.replace).toHaveBeenCalledWith({
      query: { datasourceId: '5', entityId: '2' },
    })
    wrapper.unmount()
  })

  it('无实体时说明目录同步来源并提供元数据版本入口', async () => {
    api.getEntitiesByDatasource.mockResolvedValue({ data: [] })
    const wrapper = mountPage()
    await settle()

    expect(wrapper.text()).toContain('对象目录来自已发布快照的目录同步')
    wrapper.unmount()
  })
})
