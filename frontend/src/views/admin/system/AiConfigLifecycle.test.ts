// @vitest-environment happy-dom
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
const api = vi.hoisted(() => ({
  getAiConfig: vi.fn(),
  updateAiConfig: vi.fn(),
  createAiProvider: vi.fn(),
  updateAiProvider: vi.fn(),
  deleteAiProvider: vi.fn(),
  testAiProvider: vi.fn(),
  syncAiProviderModels: vi.fn(),
  detectEmbeddingDimension: vi.fn(),
}))
const route = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))
vi.mock('../../../api/http', () => ({ http: {} }))
vi.mock('../../../api/admin/system', () => api)
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }))
import Page from './AiConfig.vue'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const provider = {
  id: 'p',
  name: '合成供应商',
  baseUrl: 'https://example.invalid/v1',
  chatModels: [{ name: 'chat' }],
  embeddingModels: [{ name: 'embed', dimension: 8 }],
  apiKeyMasked: 'SYNTHETIC_MASK_ONLY',
}
function config() {
  return {
    providers: [{ ...provider }],
    activeChat: { providerId: 'p', model: 'chat', temperature: '0.3', timeout: '120', maxRetries: '2' },
    activeEmbedding: { providerId: 'p', model: 'embed', dimension: 8 },
    vectorizeStatus: { status: 'NORMAL' },
  }
}
const defer = () => {
  let resolve!: (v: any) => void
  return { promise: new Promise((r) => (resolve = r)), resolve: (v: any) => resolve(v) }
}
function rights(codes = ['system:ai-config:view', 'system:ai-config:manage']) {
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
const wrappers: any[] = []
function page(tab = 'chat') {
  route.query = { tab }
  const w = mount(Page, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      stubs: { TaskPageHeader: { template: '<header><slot name="actions"/></header>' }, teleport: true },
    },
  })
  wrappers.push(w)
  return w
}
const button = (w: any, name: string) =>
  w.findAll('button').find((b: any) => b.text() === name || b.attributes('aria-label') === name)!
beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().token = 'A'
  Object.values(api).forEach((f) => f.mockReset())
  pendingGovernanceWrites.value = []
  api.getAiConfig.mockImplementation(async () => ({ data: config() }))
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
  rights()
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
describe('AI配置呈现与写入保护', () => {
  it('无查看权不读取，仅查看时不能维护', async () => {
    rights([])
    let w = page()
    await flushPromises()
    expect(api.getAiConfig).not.toHaveBeenCalled()
    w.unmount()
    rights(['system:ai-config:view'])
    w = page()
    await flushPromises()
    expect(button(w, '添加供应商').attributes('disabled')).toBeDefined()
    expect(button(w, '测试供应商连接').attributes('disabled')).toBeDefined()
  })
  it('未同步模型的供应商仍可维护，不隐藏成空列表', async () => {
    api.getAiConfig.mockResolvedValue({
      data: {
        providers: [{ id: 'new', name: '未同步', baseUrl: 'https://example.invalid' }],
        activeChat: null,
        activeEmbedding: null,
      },
    })
    const w = page()
    await flushPromises()
    expect(w.text()).toContain('未同步')
    expect(button(w, '编辑供应商')).toBeTruthy()
    expect(w.text()).toContain('未确认')
    expect(w.text()).not.toContain('schema_knowledge')
    expect(w.text()).not.toContain('SYNTHETIC_MASK_ONLY')
  })
  it('旧配置晚回不落新账号，后续GET归属隔离', async () => {
    const d = defer()
    api.getAiConfig.mockReturnValueOnce(d.promise)
    const w = page()
    await flushPromises()
    useAuthStore().token = 'B'
    await flushPromises()
    d.resolve({ data: { ...config(), providers: [{ ...provider, name: 'OLD_ACCOUNT' }] } })
    await flushPromises()
    expect(w.text()).not.toContain('OLD_ACCOUNT')
    expect(w.text()).toContain('合成供应商')
  })
  it('刷新不覆盖既有Chat参数草稿', async () => {
    const w = page()
    await flushPromises()
    const input = w.find('.model-params input')
    await input.setValue('0.7')
    await button(w, '刷新').trigger('click')
    await flushPromises()
    expect((w.find('.model-params input').element as HTMLInputElement).value).toBe('0.7')
  })
  it('新密钥编辑初始空、关闭再开已清除，原掩码不回显', async () => {
    const w = page()
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    let input = w.find('.el-dialog input[type="password"]')
    expect((input.element as HTMLInputElement).value).toBe('')
    await input.setValue('SYNTHETIC_NEW_KEY')
    await button(w, '取消').trigger('click')
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    input = w.find('.el-dialog input[type="password"]')
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(w.html()).not.toContain('SYNTHETIC_MASK_ONLY')
  })
  it('供应商双击一次且payload固定，成功清密钥', async () => {
    api.updateAiProvider.mockResolvedValue({ data: { id: 'p' } })
    const w = page()
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    await w.find('.el-dialog input[type="password"]').setValue('SYNTHETIC_NEW_KEY')
    const save = button(w, '保存')
    await Promise.all([save.trigger('click'), save.trigger('click')])
    await flushPromises()
    expect(api.updateAiProvider).toHaveBeenCalledOnce()
    expect(api.updateAiProvider).toHaveBeenCalledWith(
      'p',
      expect.objectContaining({ apiKey: 'SYNTHETIC_NEW_KEY' }),
    )
    expect(w.html()).not.toContain('SYNTHETIC_NEW_KEY')
  })
  it('pending时Chat保存零PUT，不静默取消待切换配置', async () => {
    api.getAiConfig.mockResolvedValue({
      data: { ...config(), pendingEmbedding: { providerId: 'p', model: 'next', dimension: 9 } },
    })
    const w = page()
    await flushPromises()
    await button(w, '保存参数').trigger('click')
    await flushPromises()
    expect(api.updateAiConfig).not.toHaveBeenCalled()
    expect(w.text()).toContain('当前Chat保存接口可能取消')
  })
  it('409保非秘密草稿、清新输入密钥，不重发', async () => {
    api.updateAiProvider.mockRejectedValue({
      response: { status: 409, data: { message: 'fixture conflict' } },
    })
    const w = page()
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    const inputs = w.findAll('.el-dialog input')
    await inputs[1]!.setValue('人工名称')
    await w.find('.el-dialog input[type="password"]').setValue('SYNTHETIC_NEW_KEY')
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect((w.findAll('.el-dialog input')[1]!.element as HTMLInputElement).value).toBe('人工名称')
    expect((w.find('.el-dialog input[type="password"]').element as HTMLInputElement).value).toBe('')
    expect(w.text()).toContain('fixture conflict')
    expect(api.updateAiProvider).toHaveBeenCalledOnce()
  })
  it('确认期间换账号不向后来账号保存', async () => {
    const d = defer()
    vi.mocked(ElMessageBox.confirm).mockReturnValue(d.promise as any)
    const w = page()
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    await button(w, '保存').trigger('click')
    await flushPromises()
    useAuthStore().token = 'B'
    await flushPromises()
    d.resolve('confirm')
    await flushPromises()
    expect(api.updateAiProvider).not.toHaveBeenCalled()
  })
  it('残缺供应商回执结果未知跨挂载留锁', async () => {
    api.updateAiProvider.mockResolvedValue({ data: { id: 'wrong' } })
    let w = page()
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('结果未知')
    w.unmount()
    w = page()
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(api.updateAiProvider).toHaveBeenCalledOnce()
  })
  it('所选供应商检测合同未核验时不发模型请求', async () => {
    const w = page('embedding')
    await flushPromises()
    expect(button(w, '自动检测向量维度').attributes('disabled')).toBeDefined()
    expect(api.detectEmbeddingDimension).not.toHaveBeenCalled()
    expect(w.text()).toContain('不能核验所选供应商')
  })
  it('当前Embedding修改维度可显式创建待配置，不回落旧维度', async () => {
    api.updateAiConfig.mockResolvedValue({
      data: { ...config(), pendingEmbedding: { providerId: 'p', model: 'embed', dimension: 9 } },
    })
    const w = page('embedding')
    await flushPromises()
    const input = w.find('.model-params input[role="spinbutton"]')
    await input.setValue('9')
    await input.trigger('change')
    await input.trigger('blur')
    await button(w, '使用').trigger('click')
    await flushPromises()
    expect(api.updateAiConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        embedding: expect.objectContaining({ providerId: 'p', model: 'embed', dimension: 9 }),
      }),
    )
  })

  it('200但Chat参数回执错属仍为未知，不冒称保存', async () => {
    api.updateAiConfig.mockResolvedValue({
      data: { ...config(), activeChat: { ...config().activeChat, model: 'wrong' } },
    })
    const w = page()
    await flushPromises()
    await button(w, '保存参数').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('结果未知')
    expect(pendingGovernanceWrites.value[0]?.state).toBe('unknown')
  })

  it('创建已有ID不将upsert伪装新增，原值/密钥不覆盖', async () => {
    const w = page()
    await flushPromises()
    await button(w, '添加供应商').trigger('click')
    await flushPromises()
    await w.find('.el-dialog input[placeholder="dashscope"]').setValue('p')
    await w.find('.el-dialog input[placeholder^="https://"]').setValue('https://example.invalid/new')
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(api.createAiProvider).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    expect(w.text()).toContain('未创建或覆盖')
  })
  it('确认后才出现同ID也零POST，不自动切成编辑', async () => {
    const w = page()
    await flushPromises()
    await button(w, '添加供应商').trigger('click')
    await flushPromises()
    await w.find('.el-dialog input[placeholder="dashscope"]').setValue('new')
    await w.find('.el-dialog input[placeholder^="https://"]').setValue('https://example.invalid/new')
    api.getAiConfig.mockResolvedValue({
      data: {
        ...config(),
        providers: [provider, { id: 'new', name: 'other administrator', baseUrl: 'https://other.invalid' }],
      },
    })
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(api.createAiProvider).not.toHaveBeenCalled()
    expect(api.updateAiProvider).not.toHaveBeenCalled()
    expect(w.text()).toContain('其他会话出现')
  })
  it('provider/model含冒号时维度草稿以精确元组隔离', async () => {
    api.getAiConfig.mockResolvedValue({
      data: {
        ...config(),
        providers: [
          { id: 'p:q', name: '一', embeddingModels: [{ name: 'r', dimension: 8 }] },
          { id: 'p', name: '二', embeddingModels: [{ name: 'q:r', dimension: 16 }] },
        ],
        activeEmbedding: { providerId: 'p:q', model: 'r', dimension: 8 },
      },
    })
    const w = page('embedding')
    await flushPromises()
    const input = w.find('.provider-card input[role="spinbutton"]')
    await input.setValue('9')
    await input.trigger('change')
    await input.trigger('blur')
    await w.findAll('.provider-opener')[1]!.trigger('click')
    await flushPromises()
    expect(
      (w.findAll('.provider-card')[1]!.find('input[role="spinbutton"]').element as HTMLInputElement).value,
    ).toBe('16')
  })
  it.each([
    [0, 'abc'],
    [0, '0x1'],
    [1, '0x10'],
    [1, '1e2'],
    [2, '1e2'],
    [0, '2.1'],
    [1, ''],
    [1, '1.5'],
    [1, '-1'],
    [2, 'abc'],
    [2, '-1'],
  ])('非法Chat参数%s=%s在写前拒绝', async (index, value) => {
    const w = page()
    await flushPromises()
    await w.findAll('.model-params input')[Number(index)]!.setValue(String(value))
    await button(w, '保存参数').trigger('click')
    await flushPromises()
    expect(api.updateAiConfig).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    expect(w.text()).toContain('未提交配置')
  })
  it('合法0温度和0重试保留，不能当作未填写', async () => {
    api.updateAiConfig.mockImplementation(async (p) => ({ data: { ...config(), activeChat: p.chat } }))
    const w = page()
    await flushPromises()
    await w.findAll('.model-params input')[0]!.setValue('0')
    await w.findAll('.model-params input')[2]!.setValue('0')
    await button(w, '保存参数').trigger('click')
    await flushPromises()
    expect(api.updateAiConfig).toHaveBeenCalledWith({
      chat: { providerId: 'p', model: 'chat', temperature: '0', timeout: '120', maxRetries: '0' },
    })
  })
  it.each(['chat', 'embedding'])('确认期间%s目标模型消失不先写全局配置', async (tab) => {
    const w = page(tab)
    await flushPromises()
    if (tab === 'embedding') {
      const input = w.find('input[role="spinbutton"]')
      await input.setValue('9')
      await input.trigger('change')
      await input.trigger('blur')
    }
    api.getAiConfig.mockResolvedValue({ data: { ...config(), providers: [] } })
    await button(w, tab === 'chat' ? '保存参数' : '使用').trigger('click')
    await flushPromises()
    expect(api.updateAiConfig).not.toHaveBeenCalled()
    expect(w.text()).toContain('目标供应商或模型已变化')
  })
  it('供应商编辑基准不随后台刷新悄悄推进', async () => {
    const w = page()
    await flushPromises()
    await button(w, '编辑供应商').trigger('click')
    await flushPromises()
    api.getAiConfig.mockResolvedValue({
      data: { ...config(), providers: [{ ...provider, name: '其他会话已改' }] },
    })
    await button(w, '刷新').trigger('click')
    await flushPromises()
    await button(w, '保存').trigger('click')
    await flushPromises()
    expect(api.updateAiProvider).not.toHaveBeenCalled()
    expect(w.text()).toContain('原供应商已变化')
  })
  it('刷新后保存当前行不会误写旧active模型', async () => {
    const w = page()
    await flushPromises()
    const fresh = {
      ...config(),
      providers: [{ ...provider, chatModels: [{ name: 'chat' }, { name: 'next-chat' }] }],
      activeChat: { ...config().activeChat, model: 'next-chat' },
    }
    api.getAiConfig.mockResolvedValue({ data: fresh })
    api.updateAiConfig.mockImplementation(async (p) => ({ data: { ...fresh, activeChat: p.chat } }))
    await button(w, '刷新').trigger('click')
    await flushPromises()
    await button(w, '保存参数').trigger('click')
    await flushPromises()
    expect(api.updateAiConfig.mock.calls[0]![0].chat.model).toBe('next-chat')
  })
})
