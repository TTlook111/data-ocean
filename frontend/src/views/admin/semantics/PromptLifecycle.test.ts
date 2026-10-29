// @vitest-environment happy-dom
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
const api = vi.hoisted(() =>
  Object.fromEntries(
    [
      'listPromptTemplates',
      'listPromptScenes',
      'listPromptInvocations',
      'validatePromptPolicy',
      'getPromptEffectiveness',
      'getPromptVersions',
      'approvePrompt',
      'rejectPrompt',
      'setPromptEnabled',
      'submitPromptForReview',
      'updatePromptTemplate',
      'rollbackPromptVersion',
    ].map((k) => [k, vi.fn()]),
  ),
)
const route = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))
vi.mock('../../../api/http', () => ({ http: {} }))
vi.mock('../../../api/admin/prompt', () => api)
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))
import Page from './PromptsView.vue'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const content = (user = 'original') => JSON.stringify({ formatVersion: 1, system: 'role', user })
const template = (code = 'plan', v = 2, status = 'DRAFT') => ({
  id: 1,
  templateCode: code,
  templateName: code,
  scenario: 'query',
  content: content(),
  currentVersion: v,
  status,
  enabled: true,
  activeVersion: 1,
  activeContent: content('active'),
  updatedAt: 'fixture',
})
const scene = (code: string) => ({
  code,
  name: code,
  group: '问数',
  consumer: '合成调用方',
  contractVersion: '1',
  variables: ['input'],
  outputContract: 'JSON',
  fixedConstraints: '程序校验',
  metric: '仅历史查询',
})
const deferred = () => {
  let resolve!: (x: any) => void
  return { promise: new Promise((r) => (resolve = r)), resolve: (x: any) => resolve(x) }
}
function rights(codes = ['prompt:view', 'prompt:manage', 'prompt:approve']) {
  const i = useIamS1Store()
  i.snapshot = {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 1,
    systemAdmin: false,
    globalFunctions: codes,
    datasourceCapabilities: [],
    queryUse: false,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
  i.loaded = true
}
let rows: any[] = []
function page(tab = 'content', code = 'plan') {
  route.query = { tab, code }
  return mount(Page, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        TaskPageHeader: { template: '<header><slot name="actions"/></header>' },
        teleport: true,
        ElSelect: { props: ['modelValue'], template: '<select><slot/></select>' },
        ElOption: true,
      },
    },
  })
}
const button = (w: any, n: string) => w.findAll('button').find((b: any) => b.text() === n)!
const click = async (w: any, n: string) => {
  await button(w, n).trigger('click')
  await flushPromises()
}
let wrappers: any[] = []
const open = async (tab = 'content') => {
  const w = page(tab)
  wrappers.push(w)
  await flushPromises()
  return w
}
beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().token = 'A'
  rights()
  pendingGovernanceWrites.value = []
  Object.values(api).forEach((f) => f.mockReset())
  rows = [template(), template('answer')]
  api.listPromptTemplates.mockImplementation(async () => ({ data: { records: rows, total: rows.length } }))
  api.listPromptScenes.mockResolvedValue({ data: [scene('plan'), scene('answer')] })
  api.getPromptVersions.mockResolvedValue({
    data: [
      {
        id: 10,
        versionNo: 1,
        content: content('old'),
        isActive: false,
        status: 'APPROVED',
        createdAt: 'fixture',
      },
    ],
  })
  api.getPromptEffectiveness.mockResolvedValue({ data: [] })
  api.listPromptInvocations.mockResolvedValue({ data: { records: [], total: 0 } })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
  vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '人工原因', action: 'confirm' } as any)
})
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers = []
  vi.restoreAllMocks()
})
describe('Prompt 精确场景与版本生命周期', () => {
  it('无查看权不发目录或策略请求，维护审核不蕴含查看', async () => {
    rights(['prompt:manage', 'prompt:approve'])
    const w = await open()
    expect(api.listPromptTemplates).not.toHaveBeenCalled()
    expect(api.listPromptScenes).not.toHaveBeenCalled()
    expect(w.text()).toContain('没有“查看 AI 提示词”')
  })
  it('只有查看权不保存、不提交', async () => {
    rights(['prompt:view'])
    const w = await open()
    expect(button(w, '提交审核').attributes('disabled')).toBeDefined()
    expect(button(w, '保存并创建新版本').attributes('disabled')).toBeDefined()
  })
  it('账号切换同步作废旧目录回包', async () => {
    const d = deferred()
    api.listPromptTemplates.mockReturnValueOnce(d.promise)
    const w = await open()
    useAuthStore().token = 'B'
    await flushPromises()
    d.resolve({ data: { records: [{ ...template('SECRET_OLD') }], total: 1 } })
    await flushPromises()
    expect(w.text()).not.toContain('SECRET_OLD')
    expect(w.text()).toContain('plan')
  })
  it('刷新保留已经存在的人工编辑', async () => {
    const w = await open()
    await w.find('#prompt-user').setValue('人工编辑')
    await click(w, '刷新')
    expect(w.find<HTMLTextAreaElement>('#prompt-user').element.value).toBe('人工编辑')
    expect(w.text()).toContain('刷新未应用')
  })
  it('切场景再返回恢复各场景草稿', async () => {
    const w = await open()
    await w.find('#prompt-user').setValue('A草稿')
    await w.findAll('.template-list__select')[1].trigger('click')
    await flushPromises()
    await w.find('#prompt-user').setValue('B草稿')
    await w.findAll('.template-list__select')[0].trigger('click')
    await flushPromises()
    expect(w.find<HTMLTextAreaElement>('#prompt-user').element.value).toBe('A草稿')
  })
  it('新编辑不能随审核提交旧版本', async () => {
    const w = await open()
    await w.find('#prompt-user').setValue('新内容')
    await click(w, '提交审核')
    expect(api.submitPromptForReview).not.toHaveBeenCalled()
    expect(w.text()).toContain('请先保存新草稿')
  })
  it('保存双击只一次，409保留草稿', async () => {
    const d = deferred()
    api.updatePromptTemplate.mockReturnValue(d.promise)
    const w = await open()
    await w.find('#prompt-user').setValue('人工草稿')
    await button(w, '保存并创建新版本').trigger('click')
    await button(w, '保存并创建新版本').trigger('click')
    expect(api.updatePromptTemplate).toHaveBeenCalledOnce()
    d.resolve(Promise.reject({ response: { status: 409, data: { message: '合成409' } } }))
    await flushPromises()
    expect(w.find<HTMLTextAreaElement>('#prompt-user').element.value).toBe('人工草稿')
    expect(w.text()).toContain('合成409')
  })
  it('错属成功回执不作保存成功，跨挂载阻止重发', async () => {
    api.updatePromptTemplate.mockResolvedValue({ data: template('other', 3) })
    let w = await open()
    await click(w, '保存并创建新版本')
    expect(w.text()).toContain('结果未知')
    expect(pendingGovernanceWrites.value[0]?.state).toBe('unknown')
    w.unmount()
    wrappers = []
    w = await open()
    await click(w, '保存并创建新版本')
    expect(api.updatePromptTemplate).toHaveBeenCalledOnce()
  })
  it('精确版本审核，批准只接受同版本激活回执', async () => {
    rows = [template('plan', 4, 'PENDING_REVIEW')]
    api.approvePrompt.mockResolvedValue({ data: { ...rows[0], status: 'APPROVED', activeVersion: 4 } })
    const w = await open()
    await click(w, '审核通过并激活')
    expect(api.approvePrompt).toHaveBeenCalledWith('plan', 4, '人工原因')
    expect(pendingGovernanceWrites.value).toHaveLength(0)
  })
  it('确认中切换账号不审核', async () => {
    rows = [template('plan', 4, 'PENDING_REVIEW')]
    const d = deferred()
    vi.mocked(ElMessageBox.prompt).mockReturnValue(d.promise as any)
    const w = await open()
    await button(w, '审核通过并激活').trigger('click')
    useAuthStore().token = 'B'
    d.resolve({ value: 'old', action: 'confirm' })
    await flushPromises()
    expect(api.approvePrompt).not.toHaveBeenCalled()
  })
  it('拒绝版本必须另存草稿才能再提交', async () => {
    rows = [template('plan', 4, 'REJECTED')]
    const w = await open()
    expect(button(w, '提交审核')).toBeUndefined()
    expect(button(w, '保存并创建新版本')).toBeDefined()
  })
  it('迟到的同场景版本请求不能覆盖最新重读', async () => {
    const d = deferred()
    api.getPromptVersions.mockReturnValueOnce(d.promise).mockResolvedValue({
      data: [{ id: 12, versionNo: 3, content: 'NEW', isActive: false, status: 'DRAFT' }],
    })
    const w = await open('versions')
    await w.find('[id$=tab-content]').trigger('click')
    await w.find('[id$=tab-versions]').trigger('click')
    await flushPromises()
    d.resolve({ data: [{ id: 11, versionNo: 1, content: 'OLD', isActive: false, status: 'DRAFT' }] })
    await flushPromises()
    expect(w.text()).toContain('v3')
    expect(w.findAll('.el-table__row').some((r: any) => r.text().startsWith('v1'))).toBe(false)
  })
  it('回滚精确目标创建新草稿，非立即激活', async () => {
    api.rollbackPromptVersion.mockImplementation(async () => ({
      data: { ...template('plan', 3), content: content('old') },
    }))
    const w = await open('versions')
    await click(w, '回滚')
    expect(api.rollbackPromptVersion).toHaveBeenCalledWith('plan', 1)
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('不立即替换'),
      expect.anything(),
      expect.anything(),
    )
  })
  it('调用错属场景显示错误，不当为空', async () => {
    api.listPromptInvocations.mockResolvedValue({
      data: { records: [{ sceneCode: 'OTHER', invocationId: 'x' }], total: 1 },
    })
    const w = await open('invocations')
    expect(w.text()).toContain('错属场景')
    expect(w.text()).not.toContain('暂无调用回执')
  })
  it('残缺统计显示错误，真实0反馈不构造0%正确性', async () => {
    api.getPromptEffectiveness.mockResolvedValue({
      data: [
        {
          templateCode: 'plan',
          versionNo: 2,
          totalQueries: 0,
          successRate: 0,
          avgExecutionTimeMs: 0,
          feedbackCount: 0,
          positiveFeedbackCount: 0,
          positiveFeedbackRate: 0,
        },
      ],
    })
    const w = await open('effectiveness')
    expect(w.find('.metric-row').text()).toContain('—')
    expect(w.text()).toContain('0ms')
    api.getPromptEffectiveness.mockResolvedValue({ data: [{ templateCode: 'plan' }] })
    await click(w, '刷新统计')
    expect(w.text()).toContain('不能按零解释')
  })
  it.each(['missing','uninitialized'])('显式 %s 场景不静默跳到其他可维护模板', async (code) => {
    api.listPromptScenes.mockResolvedValue({data:[scene('plan'),scene('answer'),scene('uninitialized')]})
    const w=page('content',code);wrappers.push(w);await flushPromises()
    expect(w.text()).toContain(code==='missing'?'请选择左侧场景':'配置尚未初始化')
    expect(button(w,'保存并创建新版本')).toBeUndefined()
  })
  it('同账号切场景后仍核记原策略保存回执，返回不恢复已保存旧草稿', async () => {
    const d = deferred()
    api.updatePromptTemplate.mockReturnValue(d.promise)
    const w = await open()
    await w.find('#prompt-user').setValue('保存A')
    await button(w, '保存并创建新版本').trigger('click')
    await w.findAll('.template-list__select')[1].trigger('click')
    await flushPromises()
    d.resolve({ data: { ...template('plan', 3), content: content('保存A') } })
    await flushPromises()
    await w.findAll('.template-list__select')[0].trigger('click')
    await flushPromises()
    expect(w.text()).toContain('编辑版本 v3')
    expect(w.text()).not.toContain('已恢复此场景尚未保存')
    expect(w.find<HTMLTextAreaElement>('#prompt-user').element.value).toBe('保存A')
  })
 it('启停跨场景成功不删除未保存的内容草稿',async()=>{
   const d=deferred();api.setPromptEnabled.mockReturnValue(d.promise)
   const w=await open();await w.find('#prompt-user').setValue('未保存的人工输入')
   await button(w,'改用内置策略').trigger('click');await flushPromises()
   await w.findAll('.template-list__select')[1].trigger('click');await flushPromises()
   d.resolve({data:{...template(),enabled:false}});await flushPromises()
   await w.findAll('.template-list__select')[0].trigger('click');await flushPromises()
   expect(w.find<HTMLTextAreaElement>('#prompt-user').element.value).toBe('未保存的人工输入')
   expect(w.text()).toContain('本地编辑尚未保存')
   expect(api.updatePromptTemplate).not.toHaveBeenCalled()
 })

 it('刷新已读到v4后，迟到v3保存回执不能回退版本事实',async()=>{
   const d=deferred();api.updatePromptTemplate.mockReturnValue(d.promise)
   const w=await open();await button(w,'保存并创建新版本').trigger('click')
   rows=[{...template('plan',4),content:content('其他会话较新版本')},template('answer')]
   await click(w,'刷新')
   d.resolve({data:{...template('plan',3),content:content()}});await flushPromises()
   expect(w.text()).toContain('编辑版本 v4')
   expect(w.find<HTMLTextAreaElement>('#prompt-user').element.value).toBe('其他会话较新版本')
   expect(w.text()).toContain('已读到更高版本')
 })

})
