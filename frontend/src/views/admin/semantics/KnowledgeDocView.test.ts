// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import KnowledgeDocView from './KnowledgeDocView.vue'

const api = vi.hoisted(() => ({
  getKnowledgeDoc: vi.fn(), getKnowledgeSourceStatus: vi.fn(), listRagBuilds: vi.fn(),
  listVersions: vi.fn(), listSourceSnapshots: vi.fn(), listSimpleDatasources: vi.fn(), listSnapshots: vi.fn(),
}))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => ({ systemAdmin: true, load: async () => {}, datasourcesWithFunction: () => [] }) }))
vi.mock('../../../stores/adminContext', () => ({ useAdminContextStore: () => ({ selectDatasource() {}, selectKnowledgeDoc() {} }) }))
vi.mock('../../../api/admin/knowledge', async (original) => ({ ...await original<typeof import('../../../api/admin/knowledge')>(), ...api }))
vi.mock('../../../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots }))

beforeEach(() => {
  vi.clearAllMocks()
  api.getKnowledgeDoc.mockResolvedValue({ data: { id: 4, datasourceId: 1, title: '目录', content: '# 目录', status: 'DRAFT', version: 0, currentVersion: 1 } })
  api.getKnowledgeSourceStatus.mockResolvedValue({ data: { valid: true, reasons: [] } })
  api.listRagBuilds.mockResolvedValue({ data: [] })
  api.listVersions.mockResolvedValue({ data: [{ versionNo: 1, metadataSnapshotId: 7 }] })
  api.listSourceSnapshots.mockResolvedValue({ data: [{ snapshotId: 7, snapshotVersion: 3, snapshotStatus: 'PUBLISHED', versionNo: 1 }] })
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: 1, name: '测试源' }] })
  api.listSnapshots.mockResolvedValue({ data: { records: [], total: 0 } })
})

it('从内容切到来源时真实加载快照，且不显示未关联的假空状态', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/docs/:id', component: KnowledgeDocView }] })
  await router.push('/docs/4')
  await router.isReady()
  const wrapper = mount(KnowledgeDocView, { global: { plugins: [router, ElementPlus], stubs: { KnowledgeSemanticAssistant: true, KnowledgeSourcePreview: true } } })
  await flushPromises()
  expect(api.listSourceSnapshots).not.toHaveBeenCalled()
  const source = wrapper.findAll('[role="tab"]').find(tab => tab.text() === '来源与覆盖')!
  await source.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.query.tab).toBe('source')
  expect(api.listSourceSnapshots).toHaveBeenCalledWith(4)
  expect(wrapper.text()).not.toContain('版本记录里没有关联的元数据快照')
  wrapper.unmount()
})

it('采纳释义后同时刷新版本和来源，不能继续显示旧版本快照', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/docs/:id', component: KnowledgeDocView }] })
  await router.push('/docs/4?tab=source')
  await router.isReady()
  const wrapper = mount(KnowledgeDocView, { global: { plugins: [router, ElementPlus], stubs: { KnowledgeSemanticAssistant: true, KnowledgeSourcePreview: true } } })
  await flushPromises()
  const originalSourceCalls = api.listSourceSnapshots.mock.calls.length
  const originalVersionCalls = api.listVersions.mock.calls.length
  api.getKnowledgeDoc.mockResolvedValue({ data: { id: 4, datasourceId: 1, title: '目录', content: '# 目录\n释义', status: 'DRAFT', version: 1, currentVersion: 2 } })
  api.listVersions.mockResolvedValue({ data: [{ versionNo: 2, metadataSnapshotId: 7 }, { versionNo: 1, metadataSnapshotId: 7 }] })
  api.listSourceSnapshots.mockResolvedValue({ data: [{ snapshotId: 7, snapshotVersion: 3, snapshotStatus: 'PUBLISHED', versionNo: 2 }] })
  wrapper.findComponent({ name: 'KnowledgeSemanticAssistant' }).vm.$emit('saved')
  await flushPromises()
  expect(api.listSourceSnapshots.mock.calls.length).toBeGreaterThan(originalSourceCalls)
  expect(api.listVersions.mock.calls.length).toBeGreaterThan(originalVersionCalls)
  expect(wrapper.text()).toContain('v2')
  wrapper.unmount()
})
