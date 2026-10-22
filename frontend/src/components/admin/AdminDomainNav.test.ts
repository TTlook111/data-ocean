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
const contextState = vi.hoisted(() => ({ datasourceId: 5 as number | undefined, snapshotId: 8 as number | undefined }))

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
          template: '<a class="nav-link" :data-path="to.path"><slot /></a>',
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
  routeState.meta = { domainKey: 'governance', workspaceKey: 'governance' }
})

describe('AdminDomainNav 能力过滤与分组', () => {
  it('只显示当前用户具备查看能力的二级项，一级入口落到首个可见工作区', () => {
    iam.canOnDatasource.mockImplementation((code: string) => code === 'governance:issue:view')
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
    // 数据接入、数据资产、查询分析没有查看能力码，保持可见。
    expect(wrapper.text()).toContain('数据接入')
    expect(wrapper.text()).toContain('数据资产')
    expect(wrapper.text()).toContain('运营与平台')
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
