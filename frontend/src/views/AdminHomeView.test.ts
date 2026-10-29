// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({
  getDashboardStats: vi.fn(),
  listSimpleDatasources: vi.fn(),
  getBatchDatasourceReadiness: vi.fn(),
  listIamS1AccessRequestQueue: vi.fn(),
}))
const iam = vi.hoisted(() => ({
  hasGlobal: vi.fn(),
  datasourcesWithFunction: vi.fn(),
}))
const routerState = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => routerState }
})
vi.mock('../stores/iamS1', () => ({ useIamS1Store: () => iam }))
vi.mock('../api/admin/dashboard', () => ({ getDashboardStats: api.getDashboardStats }))
vi.mock('../api/admin/datasource', () => ({
  listSimpleDatasources: api.listSimpleDatasources,
  getBatchDatasourceReadiness: api.getBatchDatasourceReadiness,
}))
vi.mock('../api/iamS1', () => ({ listIamS1AccessRequestQueue: api.listIamS1AccessRequestQueue }))

import AdminHomeView from './AdminHomeView.vue'

function stats(openIssues = 0) {
  return {
    totalDatasources: 1,
    askableDatasources: 0,
    pendingDatasources: 1,
    openIssues,
    resolvedIssues: 0,
    totalTables: 2,
    totalColumns: 8,
    avgQualityScore: 90,
    recentActivities: [],
  }
}

function readiness() {
  return {
    datasourceId: 5,
    datasourceName: '销售库',
    askable: false,
    stage: 'GOVERNANCE_BLOCKED',
    stageLabel: '治理阻断',
    progress: 60,
    connectionReady: true,
    metadataReady: true,
    governanceReady: false,
    knowledgeReady: false,
    permissionReady: false,
    blockReasons: [{
      code: 'BLOCKING_GOVERNANCE_ISSUES',
      message: '存在 2 条高危未解决治理问题',
      ownerRole: '治理负责人',
      actionText: '处理治理问题',
      actionPath: '/admin/governance/issues',
    }],
  }
}

function mountPage() {
  return mount(AdminHomeView, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        TaskPageHeader: { props: ['title'], template: '<header>{{ title }}</header>' },
        TitleHelp: true,
        ReadinessPanel: true,
        ActivityTimeline: true,
        BusinessStatusBadge: true,
        RouterLink: {
          props: ['to'],
          template: '<a class="router-link-stub" :data-path="to && to.path ? to.path : to"><slot /></a>',
        },
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
  Object.values(api).forEach((fn) => fn.mockReset())
  iam.hasGlobal.mockReset().mockImplementation((code: string) => code === 'admin:workbench:view')
  iam.datasourcesWithFunction.mockReset().mockReturnValue([])
  routerState.push.mockReset()
  api.getDashboardStats.mockResolvedValue({ data: stats(2) })
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: 5, name: '销售库', databaseName: 'sales' }] })
  api.getBatchDatasourceReadiness.mockResolvedValue({ data: [readiness()], failedDatasourceIds: [] })
  api.listIamS1AccessRequestQueue.mockResolvedValue({ data: { records: [], total: 3 } })
})

describe('工作台待处理事项', () => {
  it('没有工作台权限时不请求业务概览或展示业务快捷入口', async () => {
    iam.hasGlobal.mockReturnValue(false)
    iam.datasourcesWithFunction.mockImplementation((code: string) => code === 'knowledge:view' ? [2] : [])
    const wrapper = mountPage()
    try {
      await settle()
      expect(api.getDashboardStats).not.toHaveBeenCalled()
      expect(api.listSimpleDatasources).not.toHaveBeenCalled()
      expect(api.getBatchDatasourceReadiness).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('没有“查看工作台”功能')
      expect(wrapper.text()).not.toContain('管理数据源')
      expect(wrapper.text()).not.toContain('审核知识文档')
      expect(wrapper.find('.workbench-stats').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('当前工作台请求403时不继续展示业务快捷入口', async () => {
    api.getDashboardStats.mockRejectedValue(Object.assign(new Error('工作台访问被拒绝'), { response: { status: 403 } }))
    const wrapper = mountPage()
    try {
      await settle()
      expect(wrapper.text()).toContain('工作台访问被拒绝')
      expect(wrapper.text()).not.toContain('管理数据源')
      expect(wrapper.find('.workbench-stats').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('汇总数据源阻断和治理问题', async () => {
    const wrapper = mountPage()
    await settle()

    expect(wrapper.text()).toContain('待处理事项')
    expect(wrapper.text()).toContain('销售库 需要处理')
    expect(wrapper.text()).toContain('存在 2 条高危未解决治理问题')
    expect(wrapper.text()).toContain('2 个治理问题待处理')
    expect(wrapper.findAll('.workbench-pending__item')).toHaveLength(2)
  })

  it('有审批查看权时显示真实待审批数量', async () => {
    iam.hasGlobal.mockImplementation((code: string) => (
      code === 'security:approval:view' || code === 'admin:workbench:view'
    ))
    const wrapper = mountPage()
    await settle()

    expect(api.listIamS1AccessRequestQueue).toHaveBeenCalledWith('PENDING', 1, 1)
    expect(wrapper.text()).toContain('3 条访问申请待审批')
  })

  it('审批数量读取失败时显示原因而不是零', async () => {
    iam.hasGlobal.mockImplementation((code: string) => (
      code === 'security:approval:view' || code === 'admin:workbench:view'
    ))
    api.listIamS1AccessRequestQueue.mockRejectedValue(new Error('审批服务不可用'))
    const wrapper = mountPage()
    await settle()

    expect(wrapper.text()).toContain('审批服务不可用')
    expect(wrapper.text()).not.toContain('0 条访问申请待审批')
  })

  it('没有审批查看权时不请求也不显示入口', async () => {
    const wrapper = mountPage()
    await settle()

    expect(api.listIamS1AccessRequestQueue).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('处理访问申请')
  })

  it('知识审核与待发布入口按能力显示', async () => {
    iam.datasourcesWithFunction.mockImplementation((code: string) => (
      code === 'knowledge:view' || code === 'metadata:release:view' ? [5] : []
    ))
    const wrapper = mountPage()
    await settle()

    expect(wrapper.text()).toContain('审核知识文档')
    expect(wrapper.text()).toContain('检查待发布版本')
  })
})
