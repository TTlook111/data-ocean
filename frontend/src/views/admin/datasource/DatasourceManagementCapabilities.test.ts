// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElButton, ElMessageBox } from 'element-plus'
import { useIamS1Store } from '../../../stores/iamS1'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'

const route = vi.hoisted(() => ({ params: { id: '1' }, query: {} as Record<string, unknown> }))
const api = vi.hoisted(() => ({
  push: vi.fn(), replace: vi.fn(), listDatasources: vi.fn(), getDatasource: vi.fn(), getDatasourceReadiness: vi.fn(),
  createDatasource: vi.fn(), updateDatasource: vi.fn(), updateDatasourceStatus: vi.fn(), deleteDatasource: vi.fn(),
  testDatasourceConnection: vi.fn(), testSavedDatasourceConnection: vi.fn(), listSnapshots: vi.fn(),
  listSyncTasks: vi.fn(), triggerSync: vi.fn(), listQualityIssues: vi.fn(), listKnowledgeDocs: vi.fn(),
}))
vi.mock('vue-router', async (original) => ({ ...await original<typeof import('vue-router')>(), useRoute: () => route, useRouter: () => ({ push: api.push, replace: api.replace }) }))
vi.mock('../../../api/iamS1', () => ({ getIamS1Capabilities: vi.fn() }))
vi.mock('../../../api/admin/datasource', () => ({ ...api }))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots, listSyncTasks: api.listSyncTasks, triggerSync: api.triggerSync }))
vi.mock('../../../api/admin/governance', () => ({ listQualityIssues: api.listQualityIssues }))
vi.mock('../../../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))

import DataSourcesView from './DataSourcesView.vue'
import DataSourceDetailView from './DataSourceDetailView.vue'

function capability(globalFunctions: string[], scopes: Record<number, string[]>): IamS1CapabilitySnapshot {
  return { protocolVersion: 'IAM-SIMPLE-1', userId: 7, systemAdmin: false, globalFunctions,
    datasourceCapabilities: Object.entries(scopes).map(([id, functionCodes]) => ({ datasourceId: Number(id), datasourceName: `测试源${id}`, functionCodes, functionNames: functionCodes })),
    queryUse: false, viewSql: false, export: false, permissionRevision: 1 }
}
const viewOnly = () => capability(['datasource:view'], { 1: ['datasource:view'] })
const otherManager = () => capability(['datasource:view', 'datasource:manage'], { 1: ['datasource:view'], 2: ['datasource:manage'] })
const currentManager = () => capability(['datasource:view', 'datasource:manage'], { 1: ['datasource:view', 'datasource:manage'] })
const withoutView = () => capability(['datasource:manage'], { 1: ['datasource:manage'] })
const source = { id: 1, name: '测试数据源', dbType: 'MYSQL', host: '127.0.0.1', port: 3306, databaseName: 'synthetic', charset: 'utf8mb4', username: 'fixture_reader', status: 1, healthStatus: 'HEALTHY' }
const connectionReason = { code: 'CONNECTION_NOT_HEALTHY', message: '需要检查连接', ownerRole: '数据源维护者', actionText: '测试连接' }
const ready = { datasourceId: 1, datasourceName: source.name, askable: false, stage: 'CONNECTION_REQUIRED', stageLabel: '连接待检查', connectionReady: false, metadataReady: false, governanceReady: false, knowledgeReady: false, permissionReady: false, blockReasons: [connectionReason] }
const wrappers: VueWrapper[] = []

async function settle() { for (let i = 0; i < 5; i++) { await flushPromises(); await nextTick() } }
async function render(component: typeof DataSourcesView | typeof DataSourceDetailView, snapshot: IamS1CapabilitySnapshot) {
  const iam = useIamS1Store(); iam.snapshot = snapshot; iam.loaded = true
  const wrapper = mount(component, { global: { plugins: [ElementPlus], stubs: {
    RouterLink: { template: '<a><slot /></a>' },
    TaskPageHeader: { props: ['title'], template: '<header><h1>{{ title }}</h1><div class="test-actions"><slot name="actions" /></div></header>' },
    ElDropdown: { template: '<section><slot /><slot name="dropdown" /></section>' },
    ElDropdownMenu: { template: '<div><slot /></div>' },
    ElDropdownItem: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
    ElDialog: { props: ['modelValue', 'title'], template: '<section v-if="modelValue" class="test-dialog"><h2>{{ title }}</h2><slot /><slot name="footer" /></section>' },
    ReadinessPanel: true, NextActionCard: true, ActivityTimeline: true,
  } } })
  wrappers.push(wrapper); await settle(); return wrapper
}
const button = (wrapper: VueWrapper, text: string) => wrapper.findAll('button').find(item => item.text() === text)

beforeEach(() => {
  setActivePinia(createPinia()); route.query = reactive({}); route.params = reactive({ id: '1' }); localStorage.clear()
  Object.values(api).forEach(fn => fn.mockReset())
  api.replace.mockResolvedValue(undefined)
  api.listDatasources.mockResolvedValue({ data: { records: [{ ...source }], total: 1 } })
  api.getDatasource.mockResolvedValue({ data: { ...source } })
  api.getDatasourceReadiness.mockResolvedValue({ data: ready })
  api.listSnapshots.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listSyncTasks.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listQualityIssues.mockResolvedValue({ data: { records: [], total: 0 } })
  api.testSavedDatasourceConnection.mockResolvedValue({ data: { success: true, responseTimeMs: 3, message: '连接成功' } })
  api.updateDatasourceStatus.mockResolvedValue({ data: { ...source, status: 1 } })
  api.triggerSync.mockResolvedValue({ data: { taskId: 1 } })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

describe('数据源既有 S1 功能与负责源能力', () => {
  it.each(['new-draft', 'new-filter'])('创建后的能力刷新迟到，不覆盖后来的用户意图：%s', async (nextIntent) => {
    api.testDatasourceConnection.mockResolvedValue({ data: { success: true } })
    api.createDatasource.mockResolvedValue({ data: { ...source, id: 21 } })
    const wrapper = await render(DataSourcesView, currentManager())
    let finishCapabilities!: () => void
    const iam = useIamS1Store()
    vi.spyOn(iam, 'load').mockImplementation(force => force ? new Promise<void>(resolve => { finishCapabilities = () => {
      iam.snapshot = capability(['datasource:view','datasource:manage'], { 1: ['datasource:view','datasource:manage'], 21: ['datasource:view','datasource:manage'] }); resolve()
    } }) : Promise.resolve())
    await button(wrapper, '新增数据源')!.trigger('click'); await settle()
    const setField = async (label: string, value: string) => {
      const item = wrapper.findAll('.test-dialog .el-form-item').find(item => item.find('label').text() === label)!
      await item.find('input').setValue(value)
    }
    for (const [label,value] of [['名称','第一份连接'],['主机','fixture.internal'],['数据库','synthetic'],['只读账号','fixture_reader'],['密码','synthetic-secret']]) await setField(label!,value!)
    await wrapper.find('.test-dialog').findAll('button').find(item => item.text()==='测试连接')!.trigger('click'); await settle()
    await wrapper.findAllComponents(ElButton).find(item => item.text()==='创建数据源')!.trigger('click'); await settle()
    expect(api.createDatasource).toHaveBeenCalledOnce()
    if (nextIntent==='new-draft') {
      await button(wrapper, '新增数据源')!.trigger('click'); await settle(); await setField('名称','第二份未发送的草稿')
    } else {
      await wrapper.get('input[aria-label="搜索数据源名称"]').setValue('新的筛选')
      await wrapper.get('input[aria-label="搜索数据源名称"]').trigger('keyup.enter'); await settle()
    }
    finishCapabilities(); await settle()
    expect(api.push).not.toHaveBeenCalledWith('/admin/data-sources/21')
    if (nextIntent==='new-draft') expect((wrapper.find('.test-dialog input').element as HTMLInputElement).value).toBe('第二份未发送的草稿')
  })
  it('编辑深链目标不在首屏时精确读取该源，不编辑当前页其他源', async () => {
    route.query.edit = '21'
    api.getDatasource.mockResolvedValue({ data: { ...source, id: 21, name: '非首屏目标' } })
    const wrapper = await render(DataSourcesView, capability(['datasource:view', 'datasource:manage'], { 1: ['datasource:view'], 21: ['datasource:view', 'datasource:manage'] }))
    expect(api.getDatasource).toHaveBeenCalledWith(21)
    expect((wrapper.find('.test-dialog input').element as HTMLInputElement).value).toBe('非首屏目标')
  })
  it('精确编辑深链不借管理权读取没有查看权的源', async () => {
    route.query.edit = '1'
    const wrapper = await render(DataSourcesView, capability(['datasource:view', 'datasource:manage'], { 1: ['datasource:manage'] }))
    expect(api.getDatasource).not.toHaveBeenCalled()
    expect(wrapper.find('.test-dialog').exists()).toBe(false)
  })
  it('列表卸载时只取消自己持有的确认框，确认迟到不写入', async () => {
    let rejectConfirmation!: (value: unknown) => void
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise((_resolve, reject) => { rejectConfirmation = reject }))
    const wrapper = await render(DataSourcesView, currentManager())
    await button(wrapper, '禁用数据源')!.trigger('click'); await settle()
    const cancel = document.createElement('button')
    cancel.className = confirm.mock.calls[0]![2]!.cancelButtonClass || ''
    const click = vi.fn(() => rejectConfirmation('cancel')); cancel.onclick = click; document.body.append(cancel)
    wrapper.unmount(); wrappers.splice(wrappers.indexOf(wrapper), 1); await settle()
    expect(click).toHaveBeenCalledOnce()
    expect(api.updateDatasourceStatus).not.toHaveBeenCalled()
    cancel.remove()
  })
  it('仅有数据源查看权时不读取其他业务域，也不把无权显示成请求失败', async () => {
    const wrapper = await render(DataSourceDetailView, viewOnly())
    expect(api.listSnapshots).not.toHaveBeenCalled()
    expect(api.listSyncTasks).not.toHaveBeenCalled()
    expect(api.listKnowledgeDocs).not.toHaveBeenCalled()
    expect(api.listQualityIssues).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有快照详情查看权限')
    expect(wrapper.text()).not.toContain('快照读取失败')
  })

  it('没有 RAG 也按后端 askable 展示，不新增索引准入门禁', async () => {
    api.getDatasourceReadiness.mockResolvedValue({ data: { ...ready, askable: true, connectionReady: true, metadataReady: true, governanceReady: true, permissionReady: true, knowledgeReady: false, blockReasons: [] } })
    const wrapper = await render(DataSourceDetailView, viewOnly())
    expect(wrapper.text()).toContain('后台判定可问数')
    expect(wrapper.text()).toContain('尚无活动索引')
    expect(wrapper.text()).toContain('不代表当前查看账号拥有查询权')
  })

  it('同组件切到源2后，迟到的源1详情不回填', async () => {
    let resolveOld!: (value: unknown) => void
    api.getDatasource.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve })).mockResolvedValueOnce({ data: { ...source, id: 2, name: '当前源二' } })
    api.getDatasourceReadiness.mockImplementation(async (id: number) => ({ data: { ...ready, datasourceId: id } }))
    const wrapper = await render(DataSourceDetailView, capability(['datasource:view'], { 1: ['datasource:view'], 2: ['datasource:view'] }))
    route.params.id = '2'; await settle()
    expect(wrapper.text()).toContain('当前源二')
    resolveOld({ data: source }); await settle()
    expect(wrapper.text()).toContain('当前源二')
    expect(wrapper.find('h1').text()).not.toBe(source.name)
  })

  it('连接配置改变后，旧测试成功不能使新配置变成可保存', async () => {
    let finishTest!: (value: unknown) => void
    api.testDatasourceConnection.mockReturnValue(new Promise(resolve => { finishTest = resolve }))
    const wrapper = await render(DataSourcesView, currentManager())
    await button(wrapper, '编辑数据源')!.trigger('click'); await settle()
    const host = wrapper.findAll('.el-form-item').find(item => item.text().startsWith('主机'))!.find('input')
    await host.setValue('first.synthetic')
    await wrapper.find('.test-dialog input[type="password"]').setValue('synthetic-test-secret')
    const testButton = wrapper.find('.test-dialog').findAll('button').find(item => item.text() === '测试连接')!
    await testButton.trigger('click'); await settle()
    expect(api.testDatasourceConnection).toHaveBeenCalledTimes(1)
    await host.setValue('second.synthetic')
    finishTest({ data: { success: true } }); await settle()
    const saveButton = wrapper.findAllComponents(ElButton).find(item => item.text() === '保存')!
    expect(saveButton.props('disabled')).toBe(true)
    expect(api.updateDatasource).not.toHaveBeenCalled()
  })

  it('重复保存不会在异步表单校验后发送两次更新', async () => {
    let finishSave!: (value: unknown) => void
    api.updateDatasource.mockReturnValue(new Promise(resolve => { finishSave = resolve }))
    const wrapper = await render(DataSourcesView, currentManager())
    await button(wrapper, '编辑数据源')!.trigger('click'); await settle()
    const save = wrapper.findAllComponents(ElButton).find(item => item.text() === '保存')!
    save.vm.$emit('click', new MouseEvent('click')); save.vm.$emit('click', new MouseEvent('click')); await settle()
    expect(api.updateDatasource).toHaveBeenCalledTimes(1)
    expect(api.updateDatasource.mock.calls[0][1]).not.toHaveProperty('password')
    finishSave({ data: source }); await settle()
  })
  it.each([{ name: '只读', profile: viewOnly, create: false }, { name: '管理别源但只读当前源', profile: otherManager, create: true }])('$name 不展示当前行管理入口，新建仍按全局功能准入', async ({ profile, create }) => {
    const wrapper = await render(DataSourcesView, profile())
    expect(api.listDatasources).toHaveBeenCalledOnce()
    expect(wrapper.find('.row-actions__more').exists()).toBe(false)
    expect(button(wrapper, '新增数据源') !== undefined).toBe(create)
  })

  it('本源维护者保留编辑和已保存连接测试入口', async () => {
    const wrapper = await render(DataSourcesView, currentManager())
    expect(wrapper.find('.row-actions__more').exists()).toBe(true)
    await button(wrapper, '测试连接')!.trigger('click')
    expect(api.push).toHaveBeenCalledWith('/admin/data-sources/1?action=test')
    await button(wrapper, '编辑数据源')!.trigger('click'); await settle()
    expect(wrapper.find('.test-dialog').text()).toContain('编辑数据源')
  })

  it('没有查看功能时不读列表，新建功能独立保留', async () => {
    const wrapper = await render(DataSourcesView, withoutView())
    expect(api.listDatasources).not.toHaveBeenCalled()
    expect(api.getDatasourceReadiness).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有查看数据源')
    expect(button(wrapper, '新增数据源')).toBeDefined()
  })

  it('只读空列表不提供新建动作', async () => {
    api.listDatasources.mockResolvedValue({ data: { records: [], total: 0 } })
    const wrapper = await render(DataSourcesView, viewOnly())
    expect(button(wrapper, '新增数据源')).toBeUndefined()
  })

  it.each([{ name: '只读', profile: viewOnly }, { name: '管理别源', profile: otherManager }])('$name 详情的测试路由不发送维护 POST', async ({ profile }) => {
    route.query = { action: 'test' }
    const wrapper = await render(DataSourceDetailView, profile())
    expect(api.getDatasource).toHaveBeenCalledWith(1)
    expect(api.testSavedDatasourceConnection).not.toHaveBeenCalled()
    expect(button(wrapper, '测试连接')).toBeUndefined()
  })

  it('本源维护者的首次测试路由仍调用真实已保存连接接口', async () => {
    route.query = { action: 'test' }
    await render(DataSourceDetailView, currentManager())
    expect(api.testSavedDatasourceConnection).toHaveBeenCalledOnce()
    expect(api.testSavedDatasourceConnection).toHaveBeenCalledWith(1)
    expect(api.testDatasourceConnection).not.toHaveBeenCalled()
  })

  it('没有本源查看功能时不读取详情或执行测试路由', async () => {
    route.query = { action: 'test' }
    const wrapper = await render(DataSourceDetailView, withoutView())
    expect(api.getDatasource).not.toHaveBeenCalled()
    expect(api.getDatasourceReadiness).not.toHaveBeenCalled()
    expect(api.testSavedDatasourceConnection).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有查看数据源')
  })

  it.each([false, true])('开始采集独立按 metadata:collect:run，本源能力=%s', async (canCollect) => {
    api.getDatasourceReadiness.mockResolvedValue({ data: { ...ready, blockReasons: [{ code: 'SNAPSHOT_NOT_PUBLISHED', message: '尚无快照', actionText: '发布快照' }] } })
    const snapshot = capability(['datasource:view', 'metadata:collect:run'], { 1: canCollect ? ['datasource:view', 'metadata:collect:run'] : ['datasource:view'], 2: ['metadata:collect:run'] })
    const wrapper = await render(DataSourceDetailView, snapshot)
    expect(button(wrapper, '开始采集') !== undefined).toBe(canCollect)
    if (canCollect) { await button(wrapper, '开始采集')!.trigger('click'); await settle(); expect(api.triggerSync).toHaveBeenCalledWith({ datasourceId: 1, includeStatistics: false }) }
    else expect(api.triggerSync).not.toHaveBeenCalled()
  })

  it.each(['禁用数据源', '删除数据源'])('确认%s期间失去本源管理能力后不写入', async (label) => {
    if (label === '删除数据源') api.listDatasources.mockResolvedValue({ data: { records: [{ ...source, status: 0 }], total: 1 } })
    let accept!: (value: never) => void
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise(resolve => { accept = resolve }))
    const wrapper = await render(DataSourcesView, currentManager())
    await button(wrapper, label)!.trigger('click'); await flushPromises()
    useIamS1Store().snapshot = viewOnly()
    accept('confirm' as never); await settle()
    expect(api.updateDatasourceStatus).not.toHaveBeenCalled()
    expect(api.deleteDatasource).not.toHaveBeenCalled()
  })

  it('编辑弹窗打开后失去本源管理能力，保存 handler 也拒绝写入', async () => {
    const wrapper = await render(DataSourcesView, currentManager())
    await button(wrapper, '编辑数据源')!.trigger('click'); await settle()
    const save = wrapper.findAllComponents(ElButton).find(item => item.text() === '保存')!
    useIamS1Store().snapshot = viewOnly()
    save.vm.$emit('click', new MouseEvent('click')); await settle()
    expect(api.updateDatasource).not.toHaveBeenCalled()
    expect(api.createDatasource).not.toHaveBeenCalled()
  })
})
