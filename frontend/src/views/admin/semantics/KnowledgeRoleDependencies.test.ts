// @vitest-environment happy-dom
// 受控 S1 能力摘要下的真实组件回归；不替代七角色浏览器验收。
import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import KnowledgeDocView from './KnowledgeDocView.vue'
import KnowledgeView from './KnowledgeView.vue'

const role = vi.hoisted(() => ({ manage: true, manageDatasourceId: 2, queryUse: false }))
const api = vi.hoisted(() => ({ getKnowledgeDoc: vi.fn(), getKnowledgeSourceStatus: vi.fn(), listRagBuilds: vi.fn(),
  listVersions: vi.fn(), listSimpleDatasources: vi.fn(), listSnapshots: vi.fn(), updateKnowledgeDoc: vi.fn(),
  listKnowledgeDocs: vi.fn(), getBatchDatasourceReadiness: vi.fn(), getPublishedSnapshot: vi.fn() }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => ({ systemAdmin: false, load: async () => {}, hasGlobal: () => false,
  get queryUse() { return role.queryUse },
  datasourceCapabilities: [{ datasourceId: 2, datasourceName: '第二合成源', functionCodes: ['knowledge:view'] }],
  datasourcesWithFunction: (code: string) => code === 'knowledge:view' ? [2] : code === 'knowledge:manage' && role.manage ? [role.manageDatasourceId] : [],
  canOnDatasource: (code: string, id: number) => code === 'knowledge:view' && id === 2 || code === 'knowledge:manage' && role.manage && id === role.manageDatasourceId,
}) }))
vi.mock('../../../stores/adminContext', () => ({ useAdminContextStore: () => ({ datasourceId: 2, datasources: [{ id: 2, name: '第二合成源' }], initialize: async () => {}, selectDatasource() {}, selectKnowledgeDoc() {} }) }))
vi.mock('../../../api/admin/knowledge', async original => ({ ...await original<typeof import('../../../api/admin/knowledge')>(), ...api }))
vi.mock('../../../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources, getBatchDatasourceReadiness: api.getBatchDatasourceReadiness }))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots }))
vi.mock('../../../api/admin/versioning', () => ({ getPublishedSnapshot: api.getPublishedSnapshot }))

beforeEach(() => {
  vi.clearAllMocks(); role.manage = true; role.manageDatasourceId = 2; role.queryUse = false
  api.getKnowledgeDoc.mockResolvedValue({ data: { id: 6, datasourceId: 2, title: '本源手工草稿', content: '# 本源内容', status: 'DRAFT', version: 1, currentVersion: 2 } })
  api.getKnowledgeSourceStatus.mockResolvedValue({ data: { valid: true, reasons: [] } })
  api.listVersions.mockResolvedValue({ data: [] })
  api.listRagBuilds.mockResolvedValue({ data: [] })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [{ id: 6, datasourceId: 2, title: '本源手工草稿', status: 'DRAFT' }], total: 1 } })
  api.listSimpleDatasources.mockRejectedValue(new Error('没有 datasource:view'))
  api.listSnapshots.mockRejectedValue(new Error('没有 metadata:view'))
  api.getBatchDatasourceReadiness.mockRejectedValue(new Error('没有 datasource:view'))
  api.getPublishedSnapshot.mockRejectedValue(new Error('没有 metadata:release:view'))
})

async function render(list = false) {
  const component = list ? KnowledgeView : KnowledgeDocView
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/docs/:id', component }] })
  await router.push('/docs/6'); await router.isReady()
  const wrapper = mount(component, { global: { plugins: [router, ElementPlus], stubs: { KnowledgeSemanticAssistant: true, KnowledgeSourcePreview: true, ManualJoinPathPanel: true } } })
  for (let i = 0; i < 4; i++) await flushPromises()
  return wrapper
}

it('仅知识维护者保留合法保存，但不请求无权依赖或开放按快照生成', async () => {
  const wrapper = await render()
  try {
    expect(api.getKnowledgeDoc).toHaveBeenCalledWith(6)
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    expect(api.listSnapshots).not.toHaveBeenCalled()
    const generate = wrapper.findAll('button').find(item => item.text() === '按快照生成目录')!
    expect(generate.attributes('disabled')).toBeDefined()
    expect(generate.attributes('title')).toContain('元数据')
    expect(wrapper.findAll('button').some(item => item.text() === '保存')).toBe(true)
    expect((wrapper.find('.pane__title-input input').element as HTMLInputElement).readOnly).toBe(false)
  } finally { wrapper.unmount() }
})

it('仅知识角色可读取本源列表和RAG记录，不依赖数据源或版本查看接口', async () => {
  const wrapper = await render(true)
  try {
    expect(api.listKnowledgeDocs).toHaveBeenCalledWith(expect.objectContaining({ datasourceId: 2 }))
    expect(api.listRagBuilds).toHaveBeenCalledWith(2)
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    expect(api.getBatchDatasourceReadiness).not.toHaveBeenCalled()
    expect(api.getPublishedSnapshot).not.toHaveBeenCalled()
    expect(api.listSnapshots).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('本源手工草稿')
    expect(wrapper.findAll('button').find(item => item.text() === '手动新建')!.attributes('disabled')).toBeUndefined()
    const generate = wrapper.findAll('button').find(item => item.text() === '生成快照目录')!
    expect(generate.attributes('disabled')).toBeDefined()
    expect(generate.attributes('title')).toContain('元数据')
    expect(wrapper.find('.knowledge-page__readiness:not(details)').exists()).toBe(false)
  } finally { wrapper.unmount() }
})

it('没有维护能力的审核者看到只读内容，不出现假编辑表单', async () => {
  role.manage = false
  const wrapper = await render()
  try {
    expect((wrapper.find('.pane__title-input input').element as HTMLInputElement).readOnly).toBe(true)
    expect((wrapper.find('.pane__textarea textarea').element as HTMLTextAreaElement).readOnly).toBe(true)
    expect(wrapper.find('.pane__title').text()).toContain('只读')
    expect(wrapper.findAll('button').some(item => item.text() === '保存')).toBe(false)
    expect(api.updateKnowledgeDoc).not.toHaveBeenCalled()
  } finally { wrapper.unmount() }
})

it('其他源的维护能力不开放当前文档的编辑', async () => {
  role.manageDatasourceId = 1
  const wrapper = await render()
  try {
    expect((wrapper.find('.pane__title-input input').element as HTMLInputElement).readOnly).toBe(true)
    expect((wrapper.find('.pane__textarea textarea').element as HTMLTextAreaElement).readOnly).toBe(true)
    expect(wrapper.findAll('button').some(item => item.text() === '保存')).toBe(false)
    expect(api.updateKnowledgeDoc).not.toHaveBeenCalled()
  } finally { wrapper.unmount() }
})

it('无版本查看权时空文档列表不冒充无发布快照，并保留手动新建入口', async () => {
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [], total: 0 } })
  const wrapper = await render(true)
  try {
    expect(wrapper.text()).not.toContain('还没有正式发布的快照')
    expect(wrapper.text()).not.toContain('去元数据版本')
    expect(wrapper.findAll('button').some(item => item.text() === '手动新建文档')).toBe(true)
    expect(api.getPublishedSnapshot).not.toHaveBeenCalled()
  } finally { wrapper.unmount() }
})

it.each([true, false])('已发布知识的问数入口遵循 queryUse，且能力失效后的旧按钮不能跳转（列表=%s）', async (list) => {
  role.manage = false
  api.getKnowledgeDoc.mockResolvedValue({ data: { id: 6, datasourceId: 2, title: '已发布文档', content: '# 内容', status: 'PUBLISHED', version: 1, currentVersion: 2 } })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [{ id: 6, datasourceId: 2, title: '已发布文档', status: 'PUBLISHED' }], total: 1 } })
  const label = list ? '去问数' : '进入智能问数'
  const readonly = await render(list)
  try {
    expect(readonly.findAll('button').some(item => item.text() === label)).toBe(false)
  } finally { readonly.unmount() }

  role.queryUse = true
  const queryUser = await render(list)
  try {
    const button = queryUser.findAll('button').find(item => item.text() === label)!
    expect(button).toBeDefined()
    const push = vi.spyOn(queryUser.vm.$router, 'push').mockResolvedValue(undefined)
    role.queryUse = false
    await button.trigger('click')
    expect(push).not.toHaveBeenCalled()
    role.queryUse = true
    await button.trigger('click')
    expect(push).toHaveBeenCalledWith('/query')
  } finally { queryUser.unmount() }
})
