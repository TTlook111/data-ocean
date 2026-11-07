// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AdminHomeView from '../AdminHomeView.vue'
import DataSourceDetailView from './datasource/DataSourceDetailView.vue'
import QualityChecksView from './governance/QualityChecksView.vue'

const api = vi.hoisted(() => ({
  getQueryExperience: vi.fn(),
  getDatasource: vi.fn(), getDatasourceReadiness: vi.fn(), listSnapshots: vi.fn(), listSyncTasks: vi.fn(),
  listKnowledgeDocs: vi.fn(), listQualityIssues: vi.fn(), push: vi.fn(),
  listQualityRules: vi.fn(), updateRuleEnabled: vi.fn(),
  getLatestQualityCheckBatch: vi.fn(), getQualityRuleSummary: vi.fn(), getSnapshotQualityIssueSummary: vi.fn(),
  listIamS1AccessRequestQueue: vi.fn(),
}))
vi.mock('vue-router', async importOriginal => ({
  ...await importOriginal<typeof import('vue-router')>(),
  useRoute: () => ({ path: '/admin/workbench', params: { id: '1' }, query: {}, meta: { domainKey: 'data-entry' } }),
  useRouter: () => ({ push: api.push }),
}))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => ({ hasGlobal: () => true, canOnDatasource: () => true, datasourcesWithFunction: () => [1, 2], systemAdmin: false, loaded: true, errorMessage: null, snapshot: {}, load: async () => {} }) }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ token: 'synthetic-admin-test' }) }))
vi.mock('../../stores/adminContext', () => ({ useAdminContextStore: () => ({ initialize: async () => {}, datasourceId: 1, snapshotId: 10, currentDatasource: { name: '销售库' }, selectSnapshot: vi.fn() }) }))
vi.mock('../../api/admin/dashboard', () => ({ getQueryExperience: api.getQueryExperience }))
vi.mock('../../api/admin/datasource', () => ({
  getDatasource: api.getDatasource, getDatasourceReadiness: api.getDatasourceReadiness,
  testSavedDatasourceConnection: vi.fn(), updateDatasourceStatus: vi.fn(),
}))
vi.mock('../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots, listSyncTasks: api.listSyncTasks, triggerSync: vi.fn() }))
vi.mock('../../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))
vi.mock('../../api/iamS1', () => ({ listIamS1AccessRequestQueue: api.listIamS1AccessRequestQueue }))
vi.mock('../../api/admin/governance', () => ({
  listQualityIssues: api.listQualityIssues,
  listQualityRules: api.listQualityRules,
  updateRuleEnabled: api.updateRuleEnabled,
  getLatestQualityCheckBatch: api.getLatestQualityCheckBatch,
  getQualityRuleSummary: api.getQualityRuleSummary,
  getSnapshotQualityIssueSummary: api.getSnapshotQualityIssueSummary,
  triggerQualityCheck: vi.fn(),
}))

const ready = { datasourceId: 1, datasourceName: '销售库', askable: true, stage: 'ASKABLE', stageLabel: '可询问', progress: 100, publishedSnapshotId: 10, snapshotVersion: 1, connectionReady: true, metadataReady: true, governanceReady: true, knowledgeReady: true, permissionReady: true, blockReasons: [] }
const wrappers: VueWrapper[] = []
async function render(component: typeof AdminHomeView | typeof DataSourceDetailView | typeof QualityChecksView) {
  const wrapper = mount(component, { global: { plugins: [ElementPlus], stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } } } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  api.getQueryExperience.mockResolvedValue({ period: 'today', asOf: '2026-11-07T10:00:00', start: '2026-11-07T00:00:00',
    end: '2026-11-07T10:00:00', targetSeconds: 10, usage: null, feedback: null })
  api.getDatasource.mockResolvedValue({ data: { id: 1, name: '销售库', dbType: 'MYSQL', host: 'localhost', port: 3306, databaseName: 'sales', status: 1 } })
  api.getDatasourceReadiness.mockResolvedValue({ data: ready })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 20, snapshotVersion: 2, status: 'DRAFT' }, { id: 10, snapshotVersion: 1, status: 'PUBLISHED' }] } })
  api.listSyncTasks.mockResolvedValue({ data: { records: [] } })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [] } })
  api.listQualityIssues.mockResolvedValue({ data: { records: [] } })
  api.listQualityRules.mockResolvedValue({ data: [{ id: 1, ruleName: '字段注释检查', enabled: 1 }] })
  api.getLatestQualityCheckBatch.mockResolvedValue({ data: null })
  api.getQualityRuleSummary.mockResolvedValue({ data: { enabledCount: 1, applicableCount: 1, unsupportedCount: 0, overriddenCount: 0, executedCount: 0 } })
  api.getSnapshotQualityIssueSummary.mockResolvedValue({ data: { totalUnresolved: 0, statusCounts: { OPEN: 0, CONFIRMED: 0, REOPENED: 0 }, recentIssues: [] } })
  api.listIamS1AccessRequestQueue.mockResolvedValue({ data: { records: [], total: 0 } })
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('后台任务与未知状态', () => {
  // 问数工作台的读失败和窄范围角色不能得到“零点踩”或推断出来的全样本正确率。
  it('工作台读取失败时明确错误，不展示成功结论', async () => {
    api.getQueryExperience.mockRejectedValue(new Error('统计读取失败'))
    const wrapper = await render(AdminHomeView)
    expect(wrapper.text()).toContain('统计读取失败')
    expect(wrapper.find('.speed').exists()).toBe(false)
  })
  it('服务端没有返回有权范围时不展示评价和抽检读数', async () => {
    const wrapper = await render(AdminHomeView)
    expect(wrapper.text()).toContain('没有可查看的问数审计或反馈统计')
    expect(wrapper.find('.feedback').exists()).toBe(false)
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
    api.getSnapshotQualityIssueSummary.mockRejectedValue(new Error('不可用'))
    const wrapper = await render(QualityChecksView)
    expect(wrapper.text()).toContain('待处理问题统计读取失败')
    expect(wrapper.text()).not.toContain('当前没有待处理问题')
  })
  it('检查页不显示常驻规则开关，规则管理通过摘要入口进入', async () => {
    const wrapper = await render(QualityChecksView)
    expect(wrapper.find('.el-switch').exists()).toBe(false)
    expect(wrapper.text()).toContain('规则准备')
    expect(wrapper.text()).toContain('管理规则')
    expect(wrapper.text()).toContain('当前快照可参与')
    expect(wrapper.text()).toContain('已启用')
    expect(api.updateRuleEnabled).not.toHaveBeenCalled()
  })
})
