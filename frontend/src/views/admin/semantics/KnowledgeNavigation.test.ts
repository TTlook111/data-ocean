// @vitest-environment happy-dom
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import ElementPlus from 'element-plus'
const h = vi.hoisted(() => ({
  route: { params: { id: '4' }, query: {} as Record<string, string>, meta: {} },
  auth: { token: 'A' },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    systemAdmin: false,
    queryUse: true,
    codes: {} as Record<string, number[]>,
    load: vi.fn(),
    datasourcesWithFunction: vi.fn(),
    canOnDatasource: vi.fn(),
  },
  context: {
    datasourceId: 1,
    datasources: [
      { id: 1, name: '销售经营' },
      { id: 2, name: '供应链' },
    ],
    snapshotId: 11,
    loading: false,
    routeSyncing: false,
    initialize: vi.fn(),
    selectDatasource: vi.fn(),
    selectKnowledgeDoc: vi.fn(),
  },
  router: { push: vi.fn(), replace: vi.fn() },
  server: {} as any,
  api: Object.fromEntries(
    [
      'getKnowledgeDoc',
      'updateKnowledgeDoc',
      'createKnowledgeDoc',
      'getKnowledgeSourceStatus',
      'listVersions',
      'listSourceSnapshots',
      'listReviewTasks',
      'listVectorTasks',
      'previewChunks',
      'diffVersions',
      'listRagBuilds',
      'confirmRagBuild',
      'listKnowledgeDocs',
      'getBatchDatasourceReadiness',
      'getPublishedSnapshot',
      'listSnapshots',
      'generateFromSnapshot',
      'generateDraft',
      'getGenerationInputs',
      'generateSemanticDraft',
      'confirmSemanticDraft',
      'submitReview',
      'approveDoc',
      'rejectDoc',
      'publishDoc',
      'rollbackVersion',
    ].map((k) => [k, vi.fn()]),
  ),
}))
vi.mock('vue-router', async (orig) => ({
  ...(await orig<typeof import('vue-router')>()),
  useRoute: () => route,
  useRouter: () => h.router,
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => iam }))
vi.mock('../../../stores/adminContext', () => ({ useAdminContextStore: () => context }))
vi.mock('../../../api/admin/knowledge', async (orig) => ({
  ...(await orig<typeof import('../../../api/admin/knowledge')>()),
  ...h.api,
}))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: h.api.listSnapshots }))
vi.mock('../../../api/admin/datasource', () => ({
  getBatchDatasourceReadiness: h.api.getBatchDatasourceReadiness,
}))
vi.mock('../../../api/admin/versioning', () => ({ getPublishedSnapshot: h.api.getPublishedSnapshot }))
import Doc from './KnowledgeDocView.vue'

import Create from './KnowledgeDocCreateView.vue'

import SourcePreview from '../../../components/admin/KnowledgeSourcePreview.vue'
import { pendingKnowledgeWrites } from '../../../composables/knowledgeWriteTracker'
const route = reactive(h.route),
  auth = reactive(h.auth),
  iam = reactive(h.iam),
  context = reactive(h.context)
const wrappers: VueWrapper[] = []
const record = (id = 4, source = 1, status = 'DRAFT') => ({
  id,
  datasourceId: source,
  title: '原主题',
  content: '已保存正文',
  version: 3,
  currentVersion: 2,
  status,
  tableNames: '["orders"]',
  createdAt: '2026-10-28',
  updatedAt: '2026-10-29',
})

async function settle() {
  for (let i = 0; i < 5; i++) await flushPromises()
}
async function render(component: any = Doc, props?: any) {
  const w = mount(component, {
    props,
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :data-target="JSON.stringify(to)"><slot/></a>' },
        KnowledgeSemanticAssistant: true,
        KnowledgeSourcePreview: component !== SourcePreview,
        ManualJoinPathPanel: true,
      },
    },
  })
  wrappers.push(w)
  await settle()
  return w
}

beforeEach(() => {
  vi.restoreAllMocks()
  Object.values(h.api).forEach((x) => x.mockReset())
  pendingKnowledgeWrites.value = []
  route.params = { id: '4' }
  route.query = {}
  auth.token = 'A'
  iam.loaded = true
  iam.errorMessage = ''
  iam.systemAdmin = false
  iam.snapshot = {}
  iam.codes = {
    'knowledge:view': [1, 2],
    'knowledge:manage': [1],
    'knowledge:approve': [1],
    'knowledge:publish': [1],
  }
  iam.datasourcesWithFunction.mockImplementation((code) => iam.codes[code] || [])
  iam.canOnDatasource.mockImplementation((code, id) => (iam.codes[code] || []).includes(id))
  iam.load.mockResolvedValue(undefined)
  context.datasourceId = 1
  context.routeSyncing = false
  context.loading = false
  context.initialize.mockResolvedValue(undefined)
  context.selectKnowledgeDoc.mockClear()
  h.router.push.mockReset()
  h.router.replace.mockReset()
  h.server = record()
  h.api.getKnowledgeDoc.mockImplementation((id) =>
    Promise.resolve({ data: id === h.server.id ? { ...h.server } : record(id, 2) }),
  )
  h.api.updateKnowledgeDoc.mockImplementation(async (_id, p) => {
    h.server = {
      ...h.server,
      ...p,
      version: h.server.version + 1,
      currentVersion: h.server.currentVersion + 1,
    }
    return { data: null }
  })
  h.api.getKnowledgeSourceStatus.mockResolvedValue({ data: { valid: true, reasons: [] } })
  h.api.listVersions.mockResolvedValue({
    data: [
      {
        id: 12,
        docId: 4,
        versionNo: 2,
        reviewStatus: 'APPROVED',
        metadataSnapshotId: 11,
        content: '保存正文',
      },
      { id: 11, docId: 4, versionNo: 1, reviewStatus: 'APPROVED', metadataSnapshotId: 11, content: '旧正文' },
    ],
  })
  h.api.listSourceSnapshots.mockResolvedValue({
    data: [{ snapshotId: 11, snapshotVersion: 3, status: 'EXPIRED', versionNo: 2 }],
  })
  for (const k of ['listReviewTasks', 'listVectorTasks', 'previewChunks', 'diffVersions'])
    h.api[k].mockResolvedValue({ data: [] })
  h.api.listRagBuilds.mockResolvedValue({
    data: [{ buildId: 'OLD_ACTIVE', datasourceId: 1, sourceSnapshotId: 11, status: 'ACTIVE' }],
  })
  h.api.confirmRagBuild.mockResolvedValue({ data: { buildId: 'NEW', datasourceId: 1, status: 'QUEUED' } })
  h.api.listKnowledgeDocs.mockResolvedValue({
    data: { records: [record(4, 1, 'PUBLISHED'), record(5, 2, 'PENDING_REVIEW')], total: 2 },
  })
  h.api.getBatchDatasourceReadiness.mockResolvedValue({ data: [], failedDatasourceIds: [] })
  h.api.getPublishedSnapshot.mockResolvedValue({ data: null })
  h.api.listSnapshots.mockResolvedValue({
    data: { records: [{ id: 11, datasourceId: 1, snapshotVersion: 3, status: 'PUBLISHED' }], total: 1 },
  })
  h.api.generateFromSnapshot.mockResolvedValue({ data: [] })
  h.api.generateDraft.mockResolvedValue({ data: { content: '目录' } })
  h.api.getGenerationInputs.mockResolvedValue({
    data: { datasourceId: 1, snapshotId: 11, tableNames: ['orders'], glossaryFacts: [], excludedTerms: [] },
  })
  h.api.generateSemanticDraft.mockResolvedValue({
    data: [{ id: 1, text: '候选释义', dependencies: ['column:orders.amount'] }],
  })
  for (const k of [
    'confirmSemanticDraft',
    'submitReview',
    'approveDoc',
    'rejectDoc',
    'publishDoc',
    'rollbackVersion',
  ])
    h.api[k].mockResolvedValue({ data: null })
  h.api.createKnowledgeDoc.mockResolvedValue({ data: { id: 9 } })
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  document.body.innerHTML = ''
})
it.each([
  { tab: 'source', source: 0 },
  { tab: 'source', source: 2 },
  { tab: 'source', source: 1 },
  { tab: 'versions', source: 0 },
  { tab: 'versions', source: 2 },
  { tab: 'versions', source: 1 },
])('知识$tab来源ID保留且仅同源结构权链接(授权源$source)', async ({ tab, source }) => {
  iam.codes = { 'knowledge:view': [1], 'metadata:view': source ? [source] : [] }
  route.query = { datasourceId: '1', tab }
  const w = await render()
  const tables = w.findAll('.el-table').filter((t) => t.text().includes('来源快照'))
  expect(tables.length).toBeGreaterThan(0)
  const text = tables.map((t) => t.text()).join(' ')
  expect(text).toContain(source === 1 && tab === 'source' ? 'v3' : '#11')
  expect(w.findAll('.snapshot-link').length > 0).toBe(source === 1)
  if (source !== 1) {
    expect(text).toContain('无结构查看权')
    expect(h.api.listSnapshots).not.toHaveBeenCalled()
  } else
    for (const a of w.findAll('.snapshot-link')) {
      const target = JSON.parse(a.attributes('data-target')!)
      expect(target.query.datasourceId).toBe('1')
      expect(target.path).toBe('/admin/releases/snapshots/11')
    }
})
it.each([false, true])('新建无可选源时接入入口遵守独立datasource:view(allowed=%s)', async (allowed) => {
  context.datasources = []
  context.datasourceId = undefined as any
  iam.codes = { 'knowledge:manage': [1], 'datasource:view': allowed ? [2] : [] }
  ;(iam as any).hasGlobal = () => false
  const w = await render(Create)
  expect(w.findAll('button').some((x) => x.text() === '去数据源接入')).toBe(allowed)
  expect(h.api.createKnowledgeDoc).not.toHaveBeenCalled()
})
