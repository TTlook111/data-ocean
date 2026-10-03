// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AdminHomeView from '../AdminHomeView.vue'
import DataSourceDetailView from './datasource/DataSourceDetailView.vue'
import QualityDashboard from './governance/QualityDashboard.vue'

const api = vi.hoisted(() => ({
  listSimpleDatasources: vi.fn(), getBatchDatasourceReadiness: vi.fn(), getDashboardStats: vi.fn(),
  getDatasource: vi.fn(), getDatasourceReadiness: vi.fn(), listSnapshots: vi.fn(), listSyncTasks: vi.fn(),
  listKnowledgeDocs: vi.fn(), listQualityIssues: vi.fn(), push: vi.fn(),
  listQualityRules: vi.fn(), updateRuleEnabled: vi.fn(),
}))
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal<typeof import('vue-router')>(),
  useRoute: () => ({ params: { id: '1' }, query: {}, meta: { domainKey: 'data-entry' } }),
  useRouter: () => ({ push: api.push }),
}))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => ({ hasGlobal: () => true, canOnDatasource: () => true, systemAdmin: false }) }))
vi.mock('../../stores/adminContext', () => ({ useAdminContextStore: () => ({ initialize: async () => {}, datasourceId: 1, snapshotId: 10, currentDatasource: { name: '销售库' }, selectSnapshot: vi.fn() }) }))
vi.mock('../../api/admin/dashboard', () => ({ getDashboardStats: api.getDashboardStats }))
vi.mock('../../api/admin/datasource', () => ({
  listSimpleDatasources: api.listSimpleDatasources, getBatchDatasourceReadiness: api.getBatchDatasourceReadiness,
  getDatasource: api.getDatasource, getDatasourceReadiness: api.getDatasourceReadiness,
  testSavedDatasourceConnection: vi.fn(), updateDatasourceStatus: vi.fn(),
}))
vi.mock('../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots, listSyncTasks: api.listSyncTasks, triggerSync: vi.fn() }))
vi.mock('../../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))
vi.mock('../../api/admin/governance', () => ({ listQualityIssues: api.listQualityIssues, listQualityRules: api.listQualityRules, updateRuleEnabled: api.updateRuleEnabled, triggerQualityCheck: vi.fn() }))

const ready = { datasourceId: 1, datasourceName: '销售库', askable: true, stage: 'ASKABLE', stageLabel: '可询问', progress: 100, publishedSnapshotId: 10, snapshotVersion: 1, connectionReady: true, metadataReady: true, governanceReady: true, knowledgeReady: true, permissionReady: true, blockReasons: [] }
const wrappers: VueWrapper[] = []
async function render(component: typeof AdminHomeView | typeof DataSourceDetailView | typeof QualityDashboard) {
  const wrapper = mount(component, { global: { plugins: [ElementPlus], stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } } } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: 1, name: '销售库', databaseName: 'sales' }] })
  api.getBatchDatasourceReadiness.mockResolvedValue({ data: [ready], failedDatasourceIds: [] })
  api.getDashboardStats.mockResolvedValue({ data: { openIssues: 0, recentActivities: [] } })
  api.getDatasource.mockResolvedValue({ data: { id: 1, name: '销售库', dbType: 'MYSQL', host: 'localhost', port: 3306, databaseName: 'sales', status: 1 } })
  api.getDatasourceReadiness.mockResolvedValue({ data: ready })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 20, snapshotVersion: 2, status: 'DRAFT' }, { id: 10, snapshotVersion: 1, status: 'PUBLISHED' }] } })
  api.listSyncTasks.mockResolvedValue({ data: { records: [] } })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [] } })
  api.listQualityIssues.mockResolvedValue({ data: { records: [] } })
  api.listQualityRules.mockResolvedValue({ data: [{ id: 1, ruleName: '字段注释检查', enabled: 1 }] })
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('后台任务与未知状态', () => {
  it('准备情况失败时明确错误，不展示全部就绪或零阻断的结论', async () => {
    api.getBatchDatasourceReadiness.mockRejectedValue(new Error('读取失败'))
    const wrapper = await render(AdminHomeView)
    expect(wrapper.text()).toContain('准备情况暂不可用')
    expect(wrapper.text()).not.toContain('当前没有阻断项')
    expect(wrapper.findAll('dd').slice(0, 3).every(item => item.text() === '—')).toBe(true)
  })
  it('部分数据源读取失败时保留未知对象，不把已知结果当作全部成功', async () => {
    api.listSimpleDatasources.mockResolvedValue({ data: [{ id: 1, name: '销售库' }, { id: 2, name: '未确认库' }] })
    api.getBatchDatasourceReadiness.mockResolvedValue({ data: [ready], failedDatasourceIds: [2] })
    const wrapper = await render(AdminHomeView)
    expect(wrapper.text()).toContain('部分数据源的准备情况尚未确认')
    expect(wrapper.text()).toContain('未确认库')
    expect(wrapper.text()).not.toContain('当前没有阻断项')
  })
  it('详情读取已发布快照的问题，避免被最新草稿快照误导', async () => {
    await render(DataSourceDetailView)
    expect(api.listQualityIssues).toHaveBeenCalledWith(10, expect.objectContaining({ status: 'OPEN' }))
  })
  it('详情的辅助请求失败不能显示成没有快照、没有文档或没有问题', async () => {
    api.listSnapshots.mockRejectedValue(new Error('不可用'))
    api.listKnowledgeDocs.mockRejectedValue(new Error('不可用'))
    api.listQualityIssues.mockRejectedValue(new Error('治理读取失败'))
    const wrapper = await render(DataSourceDetailView)
    expect(wrapper.text()).toContain('快照读取失败')
    expect(wrapper.text()).toContain('知识文档读取失败')
    expect(wrapper.text()).toContain('治理读取失败')
    expect(wrapper.text()).not.toContain('当前发布快照没有读取到待处理问题')
  })
  it('未知的非就绪状态和空阻断不能生成问数跳转', async () => {
    api.getDatasourceReadiness.mockResolvedValue({ data: { ...ready, askable: false, stage: 'UNKNOWN', stageLabel: '状态待确认' } })
    const wrapper = await render(DataSourceDetailView)
    expect(wrapper.text()).not.toContain('已具备问数条件')
    expect(wrapper.text()).not.toContain('进入智能问数')
    expect(wrapper.find('.task-page-header__actions').text()).not.toContain('查看数据资产')
    await wrapper.find('.task-page-header__actions button').trigger('click')
    await flushPromises()
    expect(api.getDatasourceReadiness).toHaveBeenCalledTimes(2)
    expect(api.push).not.toHaveBeenCalledWith('/query')
  })
  it('治理问题读取失败时不显示无高危或暂无待处理项', async () => {
    api.listQualityIssues.mockRejectedValue(new Error('不可用'))
    const wrapper = await render(QualityDashboard)
    expect(wrapper.text()).toContain('问题状态暂不可用')
    expect(wrapper.text()).not.toContain('暂无待处理项')
    expect(wrapper.text()).not.toContain('无高危')
  })
  it('治理总览的普通管理员不能启停全局规则', async () => {
    const wrapper = await render(QualityDashboard)
    expect(wrapper.find('.el-switch').classes()).toContain('is-disabled')
    await wrapper.find('.el-switch').trigger('click')
    expect(api.updateRuleEnabled).not.toHaveBeenCalled()
  })
})
