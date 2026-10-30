// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const routeState = vi.hoisted(() => ({
  meta: { domainKey: 'governance', workspaceKey: 'governance' } as Record<string, unknown>,
  query: {} as Record<string, unknown>,
  path: '/admin/governance',
}))
const iam = vi.hoisted(() => ({
  loaded: true,
  errorMessage: '',
  hasGlobal: vi.fn(),
  canOnDatasource: vi.fn(),
  datasourcesWithFunction: vi.fn(),
}))
const contextState = vi.hoisted(() => ({ datasourceId: 5 as number | undefined, snapshotId: 8 as number | undefined,
  currentDatasource: { id: 5 } as { id: number } | undefined,
  currentSnapshot: { id: 8 } as { id: number } | undefined }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => routeState }
})
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => iam }))
vi.mock('../../stores/adminContext', () => ({ useAdminContextStore: () => contextState }))

import AdminDomainNav from './AdminDomainNav.vue'

function mountNav(collapsed = false) {
  return mount(AdminDomainNav, {
    props: { collapsed },
    global: {
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a class="nav-link" :data-path="to.path" :data-query="JSON.stringify(to.query || {})"><slot /></a>',
        },
      },
    },
  })
}

function navPaths(wrapper: ReturnType<typeof mountNav>) {
  return wrapper.findAll('.nav-link').map((link) => link.attributes('data-path'))
}

beforeEach(() => {
  iam.loaded = true
  iam.errorMessage = ''
  iam.hasGlobal.mockReset().mockReturnValue(false)
  iam.canOnDatasource.mockReset().mockReturnValue(false)
  iam.datasourcesWithFunction.mockReset().mockReturnValue([])
  contextState.datasourceId = 5
  contextState.snapshotId = 8
  contextState.currentDatasource = { id: 5 }
  contextState.currentSnapshot = { id: 8 }
  routeState.query = {}
  routeState.meta = { domainKey: 'governance', workspaceKey: 'governance' }
})

describe('AdminDomainNav 能力过滤与分组', () => {
  it('同域二级工作区逐项选择有权源，跨源不携带旧快照', () => {
    iam.canOnDatasource.mockImplementation((code: string, id?: number) => (
      code === 'governance:view' && id === 1 || code === 'governance:issue:view' && id === 2
    ))
    iam.datasourcesWithFunction.mockImplementation((code: string) => (
      code === 'governance:view' ? [1] : code === 'governance:issue:view' ? [2] : []
    ))
    contextState.datasourceId = 1
    contextState.snapshotId = 11
    contextState.currentDatasource = { id: 1 }
    contextState.currentSnapshot = { id: 11 }
    routeState.query = { datasourceId: '1', snapshotId: '11' }
    const wrapper = mountNav()
    try {
      const overview = wrapper.findAll('.nav-link').find((link) => link.text() === '治理总览')
      const issues = wrapper.findAll('.nav-link').find((link) => link.text() === '问题中心')
      expect(JSON.parse(overview!.attributes('data-query')!)).toEqual({ datasourceId: '1', snapshotId: '11' })
      expect(JSON.parse(issues!.attributes('data-query')!)).toEqual({ datasourceId: '2' })
    } finally { wrapper.unmount() }
  })

  it('跨职责角色可从资产源2直达知识源1，返回资产源2时不携带旧源快照', () => {
    iam.canOnDatasource.mockImplementation((code: string, id?: number) => (
      code === 'knowledge:view' && id === 1 || code === 'metadata:view' && id === 2
    ))
    iam.datasourcesWithFunction.mockImplementation((code: string) => (
      code === 'knowledge:view' ? [1] : code === 'metadata:view' ? [2] : []
    ))
    contextState.datasourceId = 2
    contextState.snapshotId = 22
    contextState.currentDatasource = { id: 2 }
    contextState.currentSnapshot = { id: 22 }
    routeState.query = { datasourceId: '2', snapshotId: '22' }
    const assets = mountNav()
    try {
      const semantics = assets.findAll('.nav-link').find((link) => link.text() === '语义中心')
      expect(semantics).toBeDefined()
      expect(semantics!.attributes('data-path')).toBe('/admin/semantics/knowledge')
      expect(JSON.parse(semantics!.attributes('data-query')!)).toEqual({ datasourceId: '1' })
      const governance = assets.findAll('.nav-link').find((link) => link.text() === '数据治理')
      expect(JSON.parse(governance!.attributes('data-query')!)).toEqual({ datasourceId: '2', snapshotId: '22' })
    } finally { assets.unmount() }

    contextState.datasourceId = 1
    contextState.snapshotId = 11
    contextState.currentDatasource = { id: 1 }
    contextState.currentSnapshot = { id: 11 }
    routeState.query = { datasourceId: '1', snapshotId: '11' }
    const knowledge = mountNav()
    try {
      const assetLink = knowledge.findAll('.nav-link').find((link) => link.text() === '数据资产')
      expect(assetLink).toBeDefined()
      expect(assetLink!.attributes('data-path')).toBe('/admin/assets')
      expect(JSON.parse(assetLink!.attributes('data-query')!)).toEqual({ datasourceId: '2' })
      const governance = knowledge.findAll('.nav-link').find((link) => link.text() === '数据治理')
      expect(JSON.parse(governance!.attributes('data-query')!)).toEqual({ datasourceId: '2' })
    } finally { knowledge.unmount() }
  })

  it('待重验的旧源偏好不隐藏manager合法域，入口使用该功能当前可用源', () => {
    contextState.datasourceId = 1
    contextState.currentDatasource = undefined
    contextState.currentSnapshot = undefined
    iam.canOnDatasource.mockImplementation((code: string, id?: number) => code === 'knowledge:view' && id === 2)
    iam.datasourcesWithFunction.mockImplementation((code: string) => code === 'knowledge:view' ? [2] : [])
    const wrapper = mountNav()
    try {
      const semantics = wrapper.findAll('.nav-link').find((link) => link.text() === '语义中心')
      expect(semantics).toBeDefined()
      expect(semantics!.attributes('data-path')).toBe('/admin/semantics/knowledge')
      expect(JSON.parse(semantics!.attributes('data-query')!)).toEqual({ datasourceId: '2' })
      expect(wrapper.text()).not.toContain('工作台')
      expect(wrapper.text()).not.toContain('数据接入')
    } finally { wrapper.unmount() }
  })

  it('只显示当前用户具备查看能力的二级项，一级入口落到首个可见工作区', () => {
    iam.canOnDatasource.mockImplementation((code: string) => code === 'governance:issue:view')
    iam.datasourcesWithFunction.mockImplementation((code: string) => code === 'governance:issue:view' ? [5] : [])
    const wrapper = mountNav()

    const domainLink = wrapper.findAll('.nav-link').find((link) => link.text() === '数据治理')
    expect(domainLink!.attributes('data-path')).toBe('/admin/governance/issues')
    expect(wrapper.text()).toContain('问题中心')
    expect(wrapper.text()).not.toContain('治理总览')
    expect(wrapper.text()).not.toContain('质量规则')
  })

  it('治理域按结果与问题、规则与状态、字段治理分组', () => {
    iam.canOnDatasource.mockReturnValue(true)
    iam.hasGlobal.mockReturnValue(true)
    iam.datasourcesWithFunction.mockReturnValue([5])
    const wrapper = mountNav()

    expect(wrapper.text()).toContain('结果与问题')
    expect(wrapper.text()).toContain('规则与状态')
    expect(wrapper.text()).toContain('字段治理')
    expect(wrapper.findAll('.admin-domain-nav__workspace-group-label').map((item) => item.text()))
      .toEqual(['结果与问题', '规则与状态', '字段治理'])
  })

  it('全部二级项不可见时隐藏一级域', () => {
    const wrapper = mountNav()

    expect(navPaths(wrapper)).not.toContain('/admin/semantics/glossaries')
    expect(wrapper.text()).not.toContain('语义中心')
    expect(wrapper.text()).not.toContain('数据接入')
    expect(wrapper.text()).not.toContain('数据资产')
    expect(wrapper.text()).not.toContain('运营与平台')
  })

  it('能力摘要读取失败时保持菜单可见（fail-open）', () => {
    iam.errorMessage = '能力读取失败'
    const wrapper = mountNav()

    expect(wrapper.text()).toContain('语义中心')
    expect(wrapper.text()).toContain('治理总览')
  })

  it('折叠时不渲染二级分组标题', () => {
    const wrapper = mountNav(true)

    expect(wrapper.findAll('.admin-domain-nav__workspace-group-label')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('结果与问题')
  })
})
