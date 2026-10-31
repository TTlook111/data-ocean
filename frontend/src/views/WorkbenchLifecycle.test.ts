// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
const api = vi.hoisted(() => ({
  getDashboardStats: vi.fn(),
  listSimpleDatasources: vi.fn(),
  getBatchDatasourceReadiness: vi.fn(),
  listIamS1AccessRequestQueue: vi.fn(),
}))
vi.mock('../api/admin/dashboard', () => ({ getDashboardStats: api.getDashboardStats }))
vi.mock('../api/admin/datasource', () => ({
  listSimpleDatasources: api.listSimpleDatasources,
  getBatchDatasourceReadiness: api.getBatchDatasourceReadiness,
}))
vi.mock('../api/iamS1', () => ({ listIamS1AccessRequestQueue: api.listIamS1AccessRequestQueue }))
vi.mock('../api/http', () => ({ http: {} }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
import Page from './AdminHomeView.vue'
import { useAuthStore } from '../stores/auth'
import { useIamS1Store } from '../stores/iamS1'
const deferred = () => {
  let resolve!: (v: any) => void
  const promise = new Promise((r) => (resolve = r))
  return { resolve, promise }
}
const stat = (n = 0) => ({ data: { openIssues: n, recentActivities: [] } })
function rights(codes = ['admin:workbench:view']) {
  const iam = useIamS1Store()
  iam.snapshot = {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 1,
    systemAdmin: false,
    globalFunctions: codes,
    datasourceCapabilities: [
      { datasourceId: 5, datasourceName: 'fixture', functionCodes: codes, functionNames: [] },
    ],
    queryUse: false,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
  iam.loaded = true
}
function page() {
  return mount(Page, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { props: ['to'], template: '<a><slot/></a>' },
        TaskPageHeader: true,
        TitleHelp: true,
        ReadinessPanel: true,
        ActivityTimeline: true,
      },
    },
  })
}
beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().token = 'A'
  Object.values(api).forEach((f) => f.mockReset())
  api.getDashboardStats.mockResolvedValue(stat())
  api.listSimpleDatasources.mockResolvedValue({ data: [] })
  api.getBatchDatasourceReadiness.mockResolvedValue({ data: [], failedDatasourceIds: [] })
  api.listIamS1AccessRequestQueue.mockResolvedValue({ data: { total: 0 } })
  rights()
})
describe('工作台精确读取生命周期', () => {
  it('最小角色只读合法统计，缺辅助范围不变0', async () => {
    const w = page()
    await flushPromises()
    expect(api.getDashboardStats).toHaveBeenCalledOnce()
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    expect(api.listIamS1AccessRequestQueue).not.toHaveBeenCalled()
    expect(w.text()).toContain('当前没有数据源查看范围')
    expect(w.findAll('[data-source-choice]')).toHaveLength(0)
    expect(w.text()).not.toContain('0个数据源')
    w.unmount()
  })
  it('无工作台权零读取，即使有其他域能力', async () => {
    rights(['datasource:view'])
    const w = page()
    await flushPromises()
    expect(api.getDashboardStats).not.toHaveBeenCalled()
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    w.unmount()
  })
  it('旧统计晚回不能落新账号', async () => {
    const a = deferred()
    api.getDashboardStats.mockReturnValueOnce(a.promise).mockResolvedValue(stat(7))
    rights(['admin:workbench:view', 'governance:issue:view'])
    const w = page()
    await flushPromises()
    useAuthStore().token = 'B'
    await flushPromises()
    a.resolve(stat(900))
    await flushPromises()
    expect(w.find('.board-bottom b').text()).toBe('7')
    expect(w.text()).not.toContain('900')
    w.unmount()
  })
  it('目录晚回后不得继续旧源批量请求', async () => {
    rights(['admin:workbench:view', 'datasource:view'])
    const d = deferred()
    api.listSimpleDatasources.mockReturnValueOnce(d.promise)
    const w = page()
    await flushPromises()
    useAuthStore().token = 'B'
    rights()
    await flushPromises()
    d.resolve({ data: [{ id: 5, name: 'A' }] })
    await flushPromises()
    expect(api.getBatchDatasourceReadiness).not.toHaveBeenCalled()
    expect(w.text()).not.toContain('A 需要处理')
    w.unmount()
  })
  it('源目录按同绑定功能过滤，错属准备结果不显示健康', async () => {
    rights(['admin:workbench:view', 'datasource:view'])
    api.listSimpleDatasources.mockResolvedValue({
      data: [
        { id: 5, name: 'mine' },
        { id: 9, name: 'other' },
      ],
    })
    api.getBatchDatasourceReadiness.mockResolvedValue({
      data: [{ datasourceId: 9, askable: true, blockReasons: [] }],
      failedDatasourceIds: [],
    })
    const w = page()
    await flushPromises()
    expect(api.getBatchDatasourceReadiness).toHaveBeenCalledWith([5])
    expect(w.text()).toContain('准备情况回执错属')
    expect(w.text()).not.toContain('other')
    w.unmount()
  })
  it('缺失审批总数不能变0或空成功', async () => {
    rights(['admin:workbench:view', 'security:approval:view'])
    api.listIamS1AccessRequestQueue.mockResolvedValue({ data: {} })
    const w = page()
    await flushPromises()
    expect(w.text()).toContain('审批总数未确认')
    expect(w.find('.brief-count strong').text()).toBe('—')
    expect(w.text()).not.toContain('当前没有待审批申请')
    w.unmount()
  })
  it('撤权立即清旧任务，卸载晚回不追加读取', async () => {
    rights(['admin:workbench:view', 'governance:issue:view'])
    api.getDashboardStats.mockResolvedValue(stat(2))
    const w = page()
    await flushPromises()
    expect(w.find('.board-bottom b').text()).toBe('2')
    rights([])
    await flushPromises()
    expect(w.find('.board-bottom').exists()).toBe(false)
    expect(w.find('.workbench-stats').exists()).toBe(false)
    w.unmount()
  })
})
