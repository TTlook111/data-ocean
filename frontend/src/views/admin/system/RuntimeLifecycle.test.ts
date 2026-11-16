// @vitest-environment happy-dom
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
const api = vi.hoisted(() => ({
  getSystemHealth: vi.fn(),
  getPoolDashboard: vi.fn(),
  resetDatasourcePool: vi.fn(),
  listAlertRules: vi.fn(),
  createAlertRule: vi.fn(),
  updateAlertRule: vi.fn(),
  toggleAlertRule: vi.fn(),
  listSimpleDatasources: vi.fn(),
}))
const route = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))
vi.mock('../../../api/http', () => ({ http: {} }))
vi.mock('../../../api/admin/system', () => ({
  getSystemHealth: api.getSystemHealth,
  getPoolDashboard: api.getPoolDashboard,
  resetDatasourcePool: api.resetDatasourcePool,
}))
vi.mock('../../../api/admin/audit', () => ({
  listAlertRules: api.listAlertRules,
  createAlertRule: api.createAlertRule,
  updateAlertRule: api.updateAlertRule,
  toggleAlertRule: api.toggleAlertRule,
}))
vi.mock('../../../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }))
import Page from './RuntimeView.vue'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const service = { status: 'UNKNOWN', description: '未确认', lastCheckTime: null }
const health = (overall = 'DEGRADED') => ({
  data: {
    overall,
    checkTime: 'fixture',
    pythonService: { ...service, status: 'UNAVAILABLE' },
    mysql: { ...service, status: 'HEALTHY' },
    redis: service,
  },
})
const rule = {
  id: 8,
  metric: 'ERROR_RATE',
  operator: '>',
  threshold: 5,
  notificationType: 'SYSTEM',
  enabled: true,
}
const defer = () => {
  let resolve!: (v: any) => void
  return { promise: new Promise((r) => (resolve = r)), resolve: (v: any) => resolve(v) }
}
function rights(codes = ['system:runtime:view'], sys = false) {
  const i = useIamS1Store()
  i.snapshot = {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 1,
    systemAdmin: sys,
    globalFunctions: codes,
    datasourceCapabilities: [],
    queryUse: false,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
  i.loaded = true
}
function page(tab = 'health') {
  route.query = { tab }
  return mount(Page, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      stubs: {
        TaskPageHeader: { template: '<header><slot name="actions"/></header>' },
        TitleHelp: true,
        ElSelect: {
          props: ['modelValue', 'disabled'],
          template: '<select :value="modelValue" :disabled="disabled"><slot /></select>',
        },
        ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
        teleport: true,
      },
    },
  })
}
const button = (w: any, name: string) => w.findAll('button').find((b: any) => b.text() === name)!
beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().token = 'A'
  Object.values(api).forEach((f) => f.mockReset())
  pendingGovernanceWrites.value = []
  api.getSystemHealth.mockResolvedValue(health())
  api.getPoolDashboard.mockResolvedValue({ data: { activePools: 0, pools: [] } })
  api.listAlertRules.mockResolvedValue({ data: { records: [rule], total: 21 } })
  api.listSimpleDatasources.mockResolvedValue({ data: [] })
  rights()
})
afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
describe('运行工作面生命周期', () => {
  it('最小查看只读当前服务，不依赖源目录', async () => {
    const w = page()
    await flushPromises()
    expect(api.getSystemHealth).toHaveBeenCalledOnce()
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    expect(api.getPoolDashboard).not.toHaveBeenCalled()
    expect(w.text()).toContain('不可用')
    expect(w.text()).toContain('未确认')
    w.unmount()
  })
  it('无权不请求任何运行接口', async () => {
    rights([])
    const w = page()
    await flushPromises()
    expect(api.getSystemHealth).not.toHaveBeenCalled()
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    expect(w.text()).toContain('没有运行状态查看权限')
    w.unmount()
  })
  it('旧健康回包不能覆盖后来账号，重复刷新只读一次', async () => {
    const d = defer()
    api.getSystemHealth.mockReturnValueOnce(d.promise).mockResolvedValue(health('AVAILABLE'))
    const w = page()
    await flushPromises()
    await button(w, '刷新').trigger('click')
    expect(api.getSystemHealth).toHaveBeenCalledOnce()
    useAuthStore().token = 'B'
    await flushPromises()
    d.resolve(health('OLD_ACTOR'))
    await flushPromises()
    expect(w.text()).not.toContain('OLD_ACTOR')
    expect(w.text()).toContain('正常')
    w.unmount()
  })
  it('残缺健康回执不显示健康成功', async () => {
    api.getSystemHealth.mockResolvedValue({ data: { overall: 'HEALTHY' } })
    const w = page()
    await flushPromises()
    expect(w.text()).toContain('回执不完整')
    expect(w.find('.runtime-page__grid').exists()).toBe(false)
    w.unmount()
  })
  it('池数量未知和真实0分开且不要求源目录', async () => {
    const d = defer()
    api.getPoolDashboard.mockReturnValue(d.promise)
    const w = page('pools')
    await flushPromises()
    expect(w.text()).toContain('未确认')
    expect(w.text()).not.toContain('当前活跃连接池 0')
    d.resolve({ data: { activePools: 0, pools: [] } })
    await flushPromises()
    expect(w.text()).toContain('当前活跃连接池 0')
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    w.unmount()
  })
  it('规则是真实20条服务端分页，合法0不当错误', async () => {
    const w = page('alerts')
    await flushPromises()
    expect(api.listAlertRules).toHaveBeenCalledWith({ page: 1, pageSize: 20 })
    await w.find('.btn-next').trigger('click')
    await flushPromises()
    expect(api.listAlertRules).toHaveBeenLastCalledWith({ page: 2, pageSize: 20 })
    w.unmount()
  })
  it('创建双击仅一次，409保留精确草稿', async () => {
    rights(['system:runtime:view', 'system:runtime:manage'])
    api.createAlertRule.mockRejectedValue({
      response: { status: 409, data: { message: 'fixture conflict' } },
    })
    const w = page('alerts')
    await flushPromises()
    await button(w, '新增规则').trigger('click')
    await flushPromises()
    const save = button(w, '保存')
    await Promise.all([save.trigger('click'), save.trigger('click')])
    await flushPromises()
    expect(api.createAlertRule).toHaveBeenCalledOnce()
    expect(w.text()).toContain('fixture conflict')
    expect(w.find('.el-dialog').exists()).toBe(true)
    expect(api.createAlertRule.mock.calls[0]![0]).toEqual({
      metric: 'ERROR_RATE',
      threshold: 5,
      operator: '>',
      notificationType: 'SYSTEM',
    })
    w.unmount()
  })
  it('残缺创建回执留未知目标锁，跨挂载不盲重发', async () => {
    rights(['system:runtime:view', 'system:runtime:manage'])
    api.createAlertRule.mockResolvedValue({ data: null })
    let w = page('alerts')
    await flushPromises()
    await button(w, '新增规则').trigger('click')
    await flushPromises()
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('结果未知')
    w.unmount()
    w = page('alerts')
    await flushPromises()
    await button(w, '新增规则').trigger('click')
    await flushPromises()
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(api.createAlertRule).toHaveBeenCalledOnce()
    w.unmount()
  })
  it('状态精确确认期间撤权零PATCH，不本地盲翻状态', async () => {
    rights(['system:runtime:view', 'system:runtime:manage'])
    const d = defer()
    vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as any)
    const w = page('alerts')
    await flushPromises()
    await w.find('.el-switch').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('规则 #8'),
      expect.anything(),
      expect.anything(),
    )
    rights(['system:runtime:view'])
    await flushPromises()
    d.resolve('confirm')
    await flushPromises()
    expect(api.toggleAlertRule).not.toHaveBeenCalled()
    w.unmount()
  })
  it('清空阈值零POST，合法0可明确保存', async () => {
    rights(['system:runtime:view', 'system:runtime:manage'])
    api.createAlertRule.mockResolvedValue({ data: { id: 9 } })
    const w = page('alerts')
    await flushPromises()
    await button(w, '新增规则').trigger('click')
    await flushPromises()
    const input = w.find('.el-dialog input[role="spinbutton"]')
    await input.setValue('')
    await input.trigger('change')
    await input.trigger('blur')
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(api.createAlertRule).not.toHaveBeenCalled()
    await input.setValue('0')
    await input.trigger('change')
    await input.trigger('blur')
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(api.createAlertRule).toHaveBeenCalledWith(expect.objectContaining({ threshold: 0 }))
    w.unmount()
  })
  it('确认期间同ID阈值变化也禁止旧确认PATCH', async () => {
    rights(['system:runtime:view', 'system:runtime:manage'])
    const d = defer()
    vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as any)
    const w = page('alerts')
    await flushPromises()
    await w.find('.el-switch').trigger('click')
    await flushPromises()
    api.listAlertRules.mockResolvedValue({ data: { records: [{ ...rule, threshold: 99 }], total: 1 } })
    await button(w, '刷新').trigger('click')
    await flushPromises()
    d.resolve('confirm')
    await flushPromises()
    expect(api.toggleAlertRule).not.toHaveBeenCalled()
    w.unmount()
  })
  it('sys重置精确池和原因，双击只POST一次', async () => {
    rights(['system:runtime:view','system:runtime:manage'], true)
    api.getPoolDashboard.mockResolvedValue({ data:{ activePools:1, pools:[{ datasourceId:5,poolSize:2 }] } })
    vi.spyOn(ElMessageBox,'prompt').mockResolvedValue({ value:'fixture exact reason', action:'confirm' } as any)
    api.resetDatasourcePool.mockResolvedValue({ data:null })
    const w=page('pools'); await flushPromises(); const reset=button(w,'重置连接池'); await Promise.all([reset.trigger('click'),reset.trigger('click')]); await flushPromises()
    expect(ElMessageBox.prompt).toHaveBeenCalledWith(expect.stringContaining('数据源 #5'),expect.anything(),expect.anything())
    expect(api.resetDatasourcePool).toHaveBeenCalledOnce(); expect(api.resetDatasourcePool).toHaveBeenCalledWith(5,{ confirmed:true, reason:'fixture exact reason' }); w.unmount()
  })
  it('池重置确认期间换账号不向后来账号发POST', async () => {
    rights(['system:runtime:view','system:runtime:manage'], true)
    api.getPoolDashboard.mockResolvedValue({ data:{ activePools:1,pools:[{ datasourceId:5,poolSize:2 }] } })
    const d=defer(); vi.spyOn(ElMessageBox,'prompt').mockReturnValue(d.promise as any)
    const w=page('pools'); await flushPromises(); await button(w,'重置连接池').trigger('click'); await flushPromises(); useAuthStore().token='B'; await flushPromises()
    d.resolve({ value:'old identity reason',action:'confirm' }); await flushPromises(); expect(api.resetDatasourcePool).not.toHaveBeenCalled(); w.unmount()
  })
  it('非sys维护者不能重置池', async () => {
    rights(['system:runtime:view', 'system:runtime:manage'])
    api.getPoolDashboard.mockResolvedValue({
      data: { activePools: 1, pools: [{ datasourceId: 5, poolSize: 1 }] },
    })
    const w = page('pools')
    await flushPromises()
    expect(button(w, '重置连接池').attributes('disabled')).toBeDefined()
    expect(api.resetDatasourcePool).not.toHaveBeenCalled()
    w.unmount()
  })
})
