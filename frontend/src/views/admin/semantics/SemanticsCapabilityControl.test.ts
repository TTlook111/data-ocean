// @vitest-environment happy-dom
/**
 * 批次 5 前端能力控制测试（语义中心）
 *
 * 后端拒绝可以防越权，但前端仍会向无权限用户展示可操作按钮，产生大量必然失败的请求。
 * 本测试按页面断言：无能力时不发那个必然 403 的请求；有查看权但没有操作权时按钮不可用
 * 并给出中文原因；能力结论只来自 IAM-SIMPLE-1 能力摘要。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'

const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))

const api = vi.hoisted(() => ({
  getIamS1Capabilities: vi.fn(),
  listGlossaries: vi.fn(),
  listTerms: vi.fn(),
  getLinkedColumns: vi.fn(),
  getTermWorkspace: vi.fn(),
  getTermFieldSources: vi.fn(),
  saveTermDraft: vi.fn(),
  getTermFieldTables: vi.fn(),
  getTermFieldColumns: vi.fn(),
  getGenerationInputs: vi.fn(),
  createGlossary: vi.fn(),
  updateGlossary: vi.fn(),
  deleteGlossary: vi.fn(),
  createTerm: vi.fn(),
  updateTerm: vi.fn(),
  deleteTerm: vi.fn(),
  submitTermForReview: vi.fn(),
  reviewTerm: vi.fn(),
  revertTermToDraft: vi.fn(),
  linkTermToColumn: vi.fn(),
  unlinkTermFromColumn: vi.fn(),
  getEntitiesByDatasource: vi.fn(),
  listSimpleDatasources: vi.fn(),
  getBatchDatasourceReadiness: vi.fn(),
  listKnowledgeDocs: vi.fn(),
  approveDoc: vi.fn(),
  rejectDoc: vi.fn(),
  generateFromSnapshot: vi.fn(),
  listSnapshots: vi.fn(),
  getPublishedSnapshot: vi.fn(),
  listPromptTemplates: vi.fn(),
  listPromptScenes: vi.fn(),
  listPromptInvocations: vi.fn(),
  validatePromptPolicy: vi.fn(),
  getPromptEffectiveness: vi.fn(),
  getPromptVersions: vi.fn(),
  approvePrompt: vi.fn(),
  rejectPrompt: vi.fn(),
  setPromptEnabled: vi.fn(),
  submitPromptForReview: vi.fn(),
  updatePromptTemplate: vi.fn(),
  rollbackPromptVersion: vi.fn(),
}))

vi.mock('../../../api/iamS1', () => ({ getIamS1Capabilities: api.getIamS1Capabilities }))
vi.mock('../../../api/admin/glossary', () => ({
  listGlossaries: api.listGlossaries,
  listTerms: api.listTerms,
  getLinkedColumns: api.getLinkedColumns,
  getTermWorkspace: api.getTermWorkspace,
  getTermFieldSources: api.getTermFieldSources,
  saveTermDraft: api.saveTermDraft,
  getTermFieldTables: api.getTermFieldTables,
  getTermFieldColumns: api.getTermFieldColumns,
  createGlossary: api.createGlossary,
  updateGlossary: api.updateGlossary,
  deleteGlossary: api.deleteGlossary,
  createTerm: api.createTerm,
  updateTerm: api.updateTerm,
  deleteTerm: api.deleteTerm,
  submitTermForReview: api.submitTermForReview,
  reviewTerm: api.reviewTerm,
  revertTermToDraft: api.revertTermToDraft,
  linkTermToColumn: api.linkTermToColumn,
  unlinkTermFromColumn: api.unlinkTermFromColumn,
}))
vi.mock('../../../api/admin/catalog', () => ({ getEntitiesByDatasource: api.getEntitiesByDatasource }))
vi.mock('../../../api/admin/datasource', () => ({
  listSimpleDatasources: api.listSimpleDatasources,
  getBatchDatasourceReadiness: api.getBatchDatasourceReadiness,
}))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots }))
vi.mock('../../../api/admin/versioning', () => ({ getPublishedSnapshot: api.getPublishedSnapshot }))
vi.mock('../../../api/admin/knowledge', () => ({
  listKnowledgeDocs: api.listKnowledgeDocs,
  approveDoc: api.approveDoc,
  rejectDoc: api.rejectDoc,
  generateFromSnapshot: api.generateFromSnapshot,
  getGenerationInputs: api.getGenerationInputs,
}))
vi.mock('../../../api/admin/prompt', () => ({
  listPromptScenes: api.listPromptScenes,
  listPromptInvocations: api.listPromptInvocations,
  validatePromptPolicy: api.validatePromptPolicy,
  listPromptTemplates: api.listPromptTemplates,
  getPromptEffectiveness: api.getPromptEffectiveness,
  getPromptVersions: api.getPromptVersions,
  approvePrompt: api.approvePrompt,
  rejectPrompt: api.rejectPrompt,
  setPromptEnabled: api.setPromptEnabled,
  submitPromptForReview: api.submitPromptForReview,
  updatePromptTemplate: api.updatePromptTemplate,
  rollbackPromptVersion: api.rollbackPromptVersion,
}))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  const { reactive } = await import('vue')
  return {
    ...actual,
    useRoute: () => reactive(routeState),
    useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  }
})

import GlossariesView from './GlossariesView.vue'
import KnowledgeView from './KnowledgeView.vue'
import PromptsView from './PromptsView.vue'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'

const DATASOURCE_ID = 5

function capability(options: {
  global?: string[]
  onDatasource?: string[]
  systemAdmin?: boolean
}): IamS1CapabilitySnapshot {
  const codes = options.onDatasource ?? []
  return {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 7,
    systemAdmin: options.systemAdmin ?? false,
    globalFunctions: options.global ?? [],
    datasourceCapabilities: [
      { datasourceId: DATASOURCE_ID, datasourceName: '销售库', functionCodes: codes, functionNames: codes },
    ],
    queryUse: false,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
}

function respond(snapshot: IamS1CapabilitySnapshot) {
  api.getIamS1Capabilities.mockResolvedValue({ code: 200, message: 'ok', data: snapshot })
}

function mountPage(component: unknown) {
  return mount(component as never, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        TaskPageHeader: { template: '<div><slot name="actions" /></div>' },
        ObjectContextSummary: true,
        BusinessStatusBadge: true,
      },
    },
  })
}

/**
 * 等到页面异步链路与渲染全部落定。
 *
 * 不能用单次 `flushPromises`：Element Plus 的表格与标签页在随后的微任务和帧里才真正渲染出内容，
 * 机器负载高时会读到“还没渲染完”的 DOM，让断言随机失败。
 */
async function settle() {
  for (let round = 0; round < 5; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

/** el-table 会在隐藏列区域再渲染一份列模板，那份插槽数据是空对象，必须只在真实数据行里找按钮。 */
function rowButton(wrapper: ReturnType<typeof mountPage>, rowText: string, buttonText: string) {
  const row = wrapper.findAll('.el-table__row').find((item) => item.text().includes(rowText))
  return row?.findAll('button').find((button) => button.text() === buttonText)
}

function buttonWithText(wrapper: ReturnType<typeof mountPage>, text: string) {
  return wrapper.findAll('button').find((button) => button.text() === text)
}

const glossary = { id: 1, name: 'order-domain', displayName: '订单域', description: '', status: 'DRAFT' }
const term = {
  id: 11,
  glossaryId: 1,
  name: 'order_amount',
  displayName: '订单金额',
  description: '',
  synonyms: '[]',
  status: 'PENDING_REVIEW',
}
const knowledgeDoc = {
  id: 21,
  datasourceId: DATASOURCE_ID,
  title: '订单域语义',
  status: 'PENDING_REVIEW',
  reviewStatus: 'PENDING',
  currentVersion: 1,
}
const prompt = {
  id: 7,
  scenario: 'query',
  updatedAt: '',
  templateCode: 'sql_generation',
  templateName: 'SQL 生成',
  status: 'PENDING_REVIEW',
  enabled: true,
  content: 'x',
  currentVersion: 1,
}

beforeEach(() => {
  setActivePinia(createPinia())
  pendingGovernanceWrites.value = []
  routeState.query = {}
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: DATASOURCE_ID, name: '销售库' }] })
  api.listGlossaries.mockResolvedValue({ data: [glossary] })
  api.listTerms.mockResolvedValue({ data: [term] })
  api.getLinkedColumns.mockResolvedValue({ data: [] })
  api.getTermWorkspace.mockResolvedValue({ data: { term: { ...term, revision: 1 }, columns: [], reviews: [], needsReview: false } })
  api.getTermFieldSources.mockResolvedValue({ data: [] })
  api.getGenerationInputs.mockResolvedValue({ data: { glossaryFacts: [], excludedTerms: [] } })
  api.getEntitiesByDatasource.mockResolvedValue({ data: [] })
  api.getBatchDatasourceReadiness.mockResolvedValue({ data: [] })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [knowledgeDoc], total: 1 } })
  api.listSnapshots.mockResolvedValue({ data: { records: [], total: 0 } })
  api.getPublishedSnapshot.mockResolvedValue({ data: null })
  api.listPromptTemplates.mockResolvedValue({ data: { records: [prompt], total: 1 } })
  api.updatePromptTemplate.mockImplementation(async (code, payload) => {
    const last = await api.listPromptTemplates.mock.results.at(-1)?.value
    const row = last?.data?.records?.find((item: typeof prompt) => item.templateCode === code) || prompt
    return { data: { ...row, content: payload.content, currentVersion: row.currentVersion + 1, status: 'DRAFT' } }
  })
  api.listPromptScenes.mockResolvedValue({ data: [{ code: prompt.templateCode, name: 'SQL 生成', group: '问数', consumer: 'iam_s1/graph.py', contractVersion: '1', variables: ['question'], outputContract: 'SELECT', fixedConstraints: 'AST', metric: '任务结果不等于业务正确率' }] })
  api.listPromptInvocations.mockResolvedValue({ data: { records: [], total: 0 } })
  api.validatePromptPolicy.mockResolvedValue({ data: { systemPreview: '预览角色', userPreview: '合成变量', contractVersion: '1' } })
  api.getPromptVersions.mockResolvedValue({ data: [] })
  api.getPromptEffectiveness.mockResolvedValue({ data: [] })
})

describe('GlossariesView 的能力控制', () => {
  it('没有 glossary:view 时不请求术语表列表', async () => {
    respond(capability({ global: [], onDatasource: [] }))
    mountPage(GlossariesView)
    await settle()

    expect(api.listGlossaries).not.toHaveBeenCalled()
  })

  it('有查看权但没有维护权时新建与编辑不可用', async () => {
    respond(capability({ global: ['glossary:view'] }))
    const wrapper = mountPage(GlossariesView)
    await settle()

    expect(api.listGlossaries).toHaveBeenCalled()
    const create = buttonWithText(wrapper, '新建分组')
    expect(create).toBeTruthy()
    expect(create!.attributes('disabled')).toBeDefined()

    await create!.trigger('click')
    await settle()
    expect(api.createGlossary).not.toHaveBeenCalled()
  })

  it('拥有 glossary:manage 时新建可用', async () => {
    respond(capability({ global: ['glossary:view', 'glossary:manage'] }))
    const wrapper = mountPage(GlossariesView)
    await settle()

    expect(buttonWithText(wrapper, '新建分组')!.attributes('disabled')).toBeUndefined()
  })

  it('审核权与维护权相互独立', async () => {
    // 只有 glossary:approve：审核按钮可用，维护按钮仍不可用
    respond(capability({ global: ['glossary:view', 'glossary:approve'] }))
    routeState.query = { glossaryId: '1', termId: '11' }
    const wrapper = mountPage(GlossariesView)
    await settle()

    const approve = buttonWithText(wrapper, '审核通过')
    expect(approve).toBeTruthy()
    expect(approve!.attributes('disabled')).toBeUndefined()
    expect(buttonWithText(wrapper, '新建分组')!.attributes('disabled')).toBeDefined()
  })

  it('没有审核权时审核按钮不可用且不调用审核接口', async () => {
    respond(capability({ global: ['glossary:view', 'glossary:manage'] }))
    routeState.query = { glossaryId: '1', termId: '11' }
    const wrapper = mountPage(GlossariesView)
    await settle()

    const approve = buttonWithText(wrapper, '审核通过')
    expect(approve).toBeTruthy()
    expect(approve!.attributes('disabled')).toBeDefined()

    await approve!.trigger('click')
    await settle()
    // 审核动作在无能力时连确认弹窗都不该走到
    expect(api.reviewTerm).not.toHaveBeenCalled()
  })
})

describe('KnowledgeView 的能力控制', () => {
  it('没有 knowledge:manage 时手动新建与 AI 生成不可用', async () => {
    respond(capability({ onDatasource: ['knowledge:view'] }))
    const wrapper = mountPage(KnowledgeView)
    await settle()

    expect(api.listKnowledgeDocs).toHaveBeenCalled()
    expect(buttonWithText(wrapper, '手动新建')!.attributes('disabled')).toBeDefined()
    const generate = wrapper.findAll('button').find((button) => button.text().includes('生成快照目录'))
    expect(generate!.attributes('disabled')).toBeDefined()
  })

  it('拥有 knowledge:manage 时手动新建可用', async () => {
    respond(capability({ onDatasource: ['knowledge:view', 'knowledge:manage'] }))
    const wrapper = mountPage(KnowledgeView)
    await settle()

    expect(buttonWithText(wrapper, '手动新建')!.attributes('disabled')).toBeUndefined()
  })

  it('审核权与维护权相互独立，且无权时不调用审核接口', async () => {
    respond(capability({ onDatasource: ['knowledge:view', 'knowledge:manage'] }))
    routeState.query = { tab: 'review' }
    const wrapper = mountPage(KnowledgeView)
    await settle()

    const approve = rowButton(wrapper, '订单域语义', '通过')
    expect(approve).toBeTruthy()
    expect(approve!.attributes('disabled')).toBeDefined()

    await approve!.trigger('click')
    await settle()
    expect(api.approveDoc).not.toHaveBeenCalled()
  })

  it('拥有 knowledge:approve 时审核按钮可用', async () => {
    respond(capability({ onDatasource: ['knowledge:view', 'knowledge:approve'] }))
    routeState.query = { tab: 'review' }
    const wrapper = mountPage(KnowledgeView)
    await settle()

    const approve = rowButton(wrapper, '订单域语义', '通过')
    expect(approve!.attributes('disabled')).toBeUndefined()
  })
})

describe('PromptsView 的能力控制', () => {
  it('保存请求期间的新编辑和摘要不会被保存完成回调清空', async () => {
    api.listPromptTemplates.mockResolvedValue({ data: { records: [{ ...prompt, status: 'DRAFT', content: '{{question}}' }], total: 1 } })
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    let finish!: (value: { data: typeof prompt }) => void
    api.updatePromptTemplate.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    await buttonWithText(wrapper, '保存并创建新版本')!.trigger('click')
    await wrapper.find('#prompt-user').setValue('保存期间的新任务 {{question}}')
    await wrapper.find('.content-summary input').setValue('尚未保存的新摘要')
    finish({ data: { ...prompt, status: 'DRAFT', currentVersion: 2, content: api.updatePromptTemplate.mock.calls[0]![1].content } })
    await settle()
    expect(wrapper.find('#prompt-user').element).toHaveProperty('value', '保存期间的新任务 {{question}}')
    expect(wrapper.find('.content-summary input').element).toHaveProperty('value', '尚未保存的新摘要')
    expect(api.listPromptTemplates).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('尚未保存')
  })
  it.each(['success', 'failure'])('同一场景刷新 %s 不覆盖请求之后的新编辑', async (outcome) => {
    const row = { ...prompt, status: 'DRAFT', content: '{{question}}' }
    api.listPromptTemplates.mockResolvedValue({ data: { records: [row], total: 1 } })
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    let finish!: (value: { data: { records: typeof row[]; total: number } }) => void
    let fail!: (cause: Error) => void
    api.listPromptTemplates.mockReturnValueOnce(new Promise((resolve, reject) => { finish = resolve; fail = reject }))
    await buttonWithText(wrapper, '刷新')!.trigger('click')
    await wrapper.find('#prompt-system').setValue('本地新角色')
    await wrapper.find('#prompt-user').setValue('本地新任务 {{question}}')
    await wrapper.find('.content-summary input').setValue('本地变更摘要')
    if (outcome === 'success') finish({ data: { records: [{ ...row, content: '服务端旧任务 {{question}}' }], total: 1 } })
    else fail(new Error('旧刷新失败'))
    await settle()
    expect(wrapper.find('#prompt-system').element).toHaveProperty('value', '本地新角色')
    expect(wrapper.find('#prompt-user').element).toHaveProperty('value', '本地新任务 {{question}}')
    expect(wrapper.find('.content-summary input').element).toHaveProperty('value', '本地变更摘要')
    expect(wrapper.text()).toContain('编辑已保留')
  })
  it('批准请求携带审核者看到的版本号', async () => {
    api.listPromptTemplates.mockResolvedValue({ data: { records: [{ ...prompt, currentVersion: 4 }], total: 1 } })
    respond(capability({ global: ['prompt:view', 'prompt:approve'] }))
    const dialog = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '已核对 v4', action: 'confirm' } as Awaited<ReturnType<typeof ElMessageBox.prompt>>)
    try {
      const wrapper = mountPage(PromptsView)
      await settle()
      await buttonWithText(wrapper, '审核通过并激活')!.trigger('click')
      await settle()
      expect(api.approvePrompt).toHaveBeenCalledWith(prompt.templateCode, 4, '已核对 v4')
    } finally { dialog.mockRestore() }
  })
  it('保存后的旧列表刷新不能覆盖浏览器切换的新场景和草稿', async () => {
    const rows = [{ ...prompt, status: 'DRAFT' }, { ...prompt, id: 99, templateCode: 'field_semantic', templateName: '字段解释候选', status: 'DRAFT', content: '另一场景草稿 {{input}}' }]
    api.listPromptTemplates.mockResolvedValue({ data: { records: rows, total: 2 } })
    const catalog = (await api.listPromptScenes()).data
    api.listPromptScenes.mockResolvedValue({ data: [...catalog, { ...catalog[0], code: 'field_semantic', name: '字段解释候选' }] })
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    let finish!: (value: { data: { records: typeof rows; total: number } }) => void
    api.listPromptTemplates.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    await buttonWithText(wrapper, '保存并创建新版本')!.trigger('click')
    await settle()
    reactive(routeState).query = { code: 'field_semantic' }
    await settle()
    expect(wrapper.find('.detail__heading h2').text()).toBe('字段解释候选')
    finish({ data: { records: rows, total: 2 } })
    await settle()
    expect(wrapper.find('.detail__heading h2').text()).toBe('字段解释候选')
    expect(wrapper.find('#prompt-user').element).toHaveProperty('value', '另一场景草稿 {{input}}')
  })
  it('审核提交确认期间切换场景会取消原操作', async () => {
    let release!: () => void
    // Element Plus 的声明把确认结果与输入结果交叉；此处只模拟确认动作。
    const confirmed = new Promise<Awaited<ReturnType<typeof ElMessageBox.confirm>>>((resolve) => {
      release = () => resolve('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirmed)
    api.listPromptTemplates.mockResolvedValue({ data: { records: [{ ...prompt, status: 'DRAFT' }, { ...prompt, id: 99, templateCode: 'field_semantic', templateName: '字段解释候选', status: 'DRAFT' }], total: 2 } })
    const catalog = (await api.listPromptScenes()).data
    api.listPromptScenes.mockResolvedValue({ data: [...catalog, { ...catalog[0], code: 'field_semantic', name: '字段解释候选' }] })
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    await buttonWithText(wrapper, '提交审核')!.trigger('click')
    await wrapper.findAll('.template-list__select').find(button => button.text().includes('字段解释候选'))!.trigger('click')
    release()
    await settle()
    expect(api.submitPromptForReview).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('草稿和生效版本独立显示，保存角色与任务为同一个版本包', async () => {
    api.listPromptTemplates.mockResolvedValue({ data: { records: [{ ...prompt, status: 'DRAFT', currentVersion: 4,
      activeVersion: 2, activeContent: '旧的生效内容', content: JSON.stringify({ formatVersion: 1, system: '新角色', user: '{{question}}' }) }], total: 1 } })
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    expect(wrapper.text()).toContain('编辑版本 v4 · 生效版本 v2')
    expect(wrapper.text()).toContain('旧的生效内容')
    await buttonWithText(wrapper, '保存并创建新版本')!.trigger('click')
    await settle()
    const payload = api.updatePromptTemplate.mock.calls[0]![1]
    expect(JSON.parse(payload.content)).toEqual({ formatVersion: 1, system: '新角色', user: '{{question}}' })
  })

  it('场景存在而配置未初始化时明确提示内置策略，不提供保存入口', async () => {
    routeState.query = { code: 'field_semantic' }
    api.listPromptTemplates.mockResolvedValue({ data: { records: [], total: 0 } })
    api.listPromptScenes.mockResolvedValue({ data: [{ code: 'field_semantic', name: '字段解释候选', group: '知识治理', consumer: 'knowledge/semantic.py', contractVersion: '1', variables: ['input'], outputContract: 'JSON', fixedConstraints: '人工审核', metric: '人工采纳率' }] })
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    expect(wrapper.text()).toContain('后台配置尚未初始化')
    expect(wrapper.text()).toContain('人工审核')
    expect(buttonWithText(wrapper, '保存并创建新版本')).toBeFalsy()
  })

  it('校验预览失败展示错误，不保存或调用审核接口', async () => {
    api.validatePromptPolicy.mockRejectedValue(new Error('未知策略变量'))
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    api.listPromptTemplates.mockResolvedValue({ data: { records: [{ ...prompt, status: 'DRAFT' }], total: 1 } })
    const wrapper = mountPage(PromptsView)
    await settle()
    await buttonWithText(wrapper, '校验并预览')!.trigger('click')
    await settle()
    expect(wrapper.text()).toContain('未知策略变量')
    expect(api.updatePromptTemplate).not.toHaveBeenCalled()
    expect(api.approvePrompt).not.toHaveBeenCalled()
  })

  it('调用记录接口失败显示错误，不显示为暂无调用', async () => {
    routeState.query = { tab: 'invocations' }
    api.listPromptInvocations.mockRejectedValue(new Error('回执查询失败'))
    respond(capability({ global: ['prompt:view'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    expect(wrapper.text()).toContain('回执查询失败')
    expect(wrapper.text()).not.toContain('暂无调用回执')
  })

  it('历史意图模板不宣称已接入节点，展示实际接入范围', async () => {
    api.listPromptTemplates.mockResolvedValue({
      data: { records: [{ ...prompt, templateCode: 'intent_recognition', status: 'APPROVED', enabled: true }], total: 1 },
    })
    respond(capability({ global: ['prompt:view'] }))
    const wrapper = mountPage(PromptsView)
    await settle()
    expect(wrapper.text()).toContain('已退役')
    expect(wrapper.text()).not.toContain('生效节点')
    expect(wrapper.text()).toContain('历史版本保留')
    expect(buttonWithText(wrapper, '保存并创建新版本')).toBeFalsy()
  })

  it('没有 prompt:view 时不请求模板列表', async () => {
    respond(capability({ global: [] }))
    const wrapper = mountPage(PromptsView)
    await settle()

    expect(api.listPromptTemplates).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('prompt:view')
  })

  it('待审核模板：只有 prompt:view 时审核按钮不可用且不调用审核接口', async () => {
    respond(capability({ global: ['prompt:view'] }))
    const wrapper = mountPage(PromptsView)
    await settle()

    expect(api.listPromptTemplates).toHaveBeenCalled()
    const approve = buttonWithText(wrapper, '审核通过并激活')
    expect(approve).toBeTruthy()
    expect(approve!.attributes('disabled')).toBeDefined()

    await approve!.trigger('click')
    await settle()
    expect(api.approvePrompt).not.toHaveBeenCalled()
  })

  it('待审核模板：拥有 prompt:approve 时审核按钮可用', async () => {
    respond(capability({ global: ['prompt:view', 'prompt:approve'] }))
    const wrapper = mountPage(PromptsView)
    await settle()

    expect(buttonWithText(wrapper, '审核通过并激活')!.attributes('disabled')).toBeUndefined()
  })

  it('草稿模板：没有 prompt:manage 时保存按钮不可用', async () => {
    api.listPromptTemplates.mockResolvedValue({
      data: { records: [{ ...prompt, status: 'DRAFT' }], total: 1 },
    })
    respond(capability({ global: ['prompt:view', 'prompt:approve'] }))
    const wrapper = mountPage(PromptsView)
    await settle()

    const save = wrapper.findAll('button').find((button) => button.text().includes('保存'))
    expect(save).toBeTruthy()
    expect(save!.attributes('disabled')).toBeDefined()
  })

  it('草稿模板：拥有 prompt:manage 时保存按钮可用', async () => {
    api.listPromptTemplates.mockResolvedValue({
      data: { records: [{ ...prompt, status: 'DRAFT' }], total: 1 },
    })
    respond(capability({ global: ['prompt:view', 'prompt:manage'] }))
    const wrapper = mountPage(PromptsView)
    await settle()

    const save = wrapper.findAll('button').find((button) => button.text().includes('保存'))
    expect(save!.attributes('disabled')).toBeUndefined()
  })
})

describe('能力摘要读取失败时', () => {
  it('保持无能力，不发任何语义中心请求', async () => {
    api.getIamS1Capabilities.mockRejectedValue(new Error('服务不可用'))

    const glossaries = mountPage(GlossariesView)
    await settle()
    expect(api.listGlossaries).not.toHaveBeenCalled()

    const prompts = mountPage(PromptsView)
    await settle()
    expect(api.listPromptTemplates).not.toHaveBeenCalled()
    expect(glossaries.text()).toBeTruthy()
    expect(prompts.text()).toBeTruthy()
  })
})


it('读取术语详情后保留列表的字段关联摘要', async () => {
  respond(capability({ global: ['glossary:view'], onDatasource: ['glossary:view'] }))
  routeState.query = { glossaryId: '1', termId: '11' }
  api.listTerms.mockResolvedValue({ data: [{ ...term, revision: 1, linkedColumnCount: 1, datasourceNames: ['销售库'] }] })
  api.getTermWorkspace.mockResolvedValue({ data: { term: { ...term, revision: 1, linkedColumnCount: null, datasourceNames: null },
    columns: [{ entityId: 20, datasourceName: '销售库', tableName: 'orders', columnName: 'amount' }], reviews: [], needsReview: false } })
  const wrapper = mountPage(GlossariesView)
  await settle()
  expect(wrapper.text()).toContain('已关联 1 个可见字段')
  expect(wrapper.text()).toContain('关联字段（1）')
})
