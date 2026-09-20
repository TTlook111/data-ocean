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
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'

const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))

const api = vi.hoisted(() => ({
  getIamS1Capabilities: vi.fn(),
  listGlossaries: vi.fn(),
  listTerms: vi.fn(),
  getLinkedColumns: vi.fn(),
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
}))
vi.mock('../../../api/admin/prompt', () => ({
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
  return {
    ...actual,
    useRoute: () => routeState,
    useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  }
})

import GlossariesView from './GlossariesView.vue'
import KnowledgeView from './KnowledgeView.vue'
import PromptsView from './PromptsView.vue'

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
  templateCode: 'sql_generation',
  templateName: 'SQL 生成',
  status: 'PENDING_REVIEW',
  enabled: true,
  content: 'x',
  version: 1,
}

beforeEach(() => {
  setActivePinia(createPinia())
  routeState.query = {}
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: DATASOURCE_ID, name: '销售库' }] })
  api.listGlossaries.mockResolvedValue({ data: [glossary] })
  api.listTerms.mockResolvedValue({ data: [term] })
  api.getLinkedColumns.mockResolvedValue({ data: [] })
  api.getEntitiesByDatasource.mockResolvedValue({ data: [] })
  api.getBatchDatasourceReadiness.mockResolvedValue({ data: [] })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [knowledgeDoc], total: 1 } })
  api.listSnapshots.mockResolvedValue({ data: { records: [], total: 0 } })
  api.getPublishedSnapshot.mockResolvedValue({ data: null })
  api.listPromptTemplates.mockResolvedValue({ data: { records: [prompt], total: 1 } })
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
    const create = buttonWithText(wrapper, '新建术语表')
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

    expect(buttonWithText(wrapper, '新建术语表')!.attributes('disabled')).toBeUndefined()
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
    expect(buttonWithText(wrapper, '新建术语表')!.attributes('disabled')).toBeDefined()
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
    const generate = wrapper.findAll('button').find((button) => button.text().includes('AI 一键生成'))
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
    const approve = buttonWithText(wrapper, '审核通过')
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

    expect(buttonWithText(wrapper, '审核通过')!.attributes('disabled')).toBeUndefined()
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
