// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const s = vi.hoisted(() => ({
  auth: { token: 'A' },
  route: { query: { glossaryId: '31', termId: '301' } as Record<string, unknown> },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    codes: ['glossary:view', 'glossary:manage', 'glossary:approve'],
    hasGlobal: vi.fn(),
    canOnDatasource: vi.fn(),
    load: vi.fn(),
  },
  router: { push: vi.fn() },
  api: {
    listGlossaries: vi.fn(),
    listTerms: vi.fn(),
    getTermWorkspace: vi.fn(),
    getTermFieldSources: vi.fn(),
    getTermFieldTables: vi.fn(),
    getTermFieldColumns: vi.fn(),
    createGlossary: vi.fn(),
    updateGlossary: vi.fn(),
    deleteGlossary: vi.fn(),
    deleteTerm: vi.fn(),
    reviewTerm: vi.fn(),
    revertTermToDraft: vi.fn(),
    submitTermForReview: vi.fn(),
    saveTermDraft: vi.fn(),
  },
}))
vi.mock('vue-router', () => ({ useRoute: () => reactive(s.route), useRouter: () => s.router }))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(s.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(s.iam) }))
vi.mock('../../../api/admin/glossary', () => s.api)
import Glossaries from './GlossariesView.vue'
import Editor from '../../../components/admin/TermEditor.vue'

import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const group = { id: 31, name: 'sales', displayName: '销售业务', status: 'PUBLISHED' },
  groupB = { ...group, id: 32, name: 'supply', displayName: '供应业务' }
const term = {
  id: 301,
  glossaryId: 31,
  name: 'amount',
  displayName: '实付金额',
  description: '原始列表定义',
  status: 'DRAFT',
  revision: 3,
  synonyms: '["成交金额"]',
  fqn: 'sales.amount',
}
const other = {
  ...term,
  id: 302,
  glossaryId: 32,
  name: 'quantity',
  displayName: '供货数量',
  status: 'PENDING_REVIEW',
}
const column = {
  entityId: 901,
  datasourceId: 5,
  datasourceName: '销售库',
  snapshotId: 8,
  tableId: 11,
  columnId: 101,
  tableName: 'orders',
  columnName: 'amount',
  columnType: 'DECIMAL',
  fqn: 'sales.orders.amount',
}
function workspace(t: any = term) {
  return {
    data: {
      term: { ...t, description: '精确修订定义' + t.id },
      columns: [column],
      reviews: [],
      invalidMappings: [],
      needsReview: false,
    },
  }
}

async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function page(c: any = Glossaries, props = {}) {
  return shallowMount(c, {
    props,
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :data-target="JSON.stringify(to)"><slot/></a>' },
        TermEditor: c === Glossaries,
        TermFieldPicker: c === Editor,
      },
    },
  })
}

beforeEach(() => {
  vi.restoreAllMocks()
  s.auth.token = 'A'
  s.route.query = { glossaryId: '31', termId: '301' }
  s.iam.loaded = true
  s.iam.errorMessage = ''
  s.iam.snapshot = {}
  s.iam.codes = ['glossary:view', 'glossary:manage', 'glossary:approve']
  s.iam.hasGlobal.mockImplementation((c) => reactive(s.iam).codes.includes(c))
  s.iam.canOnDatasource.mockImplementation((c, id) => id === 5 && reactive(s.iam).codes.includes(c))
  s.iam.load.mockResolvedValue(undefined)
  s.router.push.mockReset().mockImplementation(async ({ query }) => {
    reactive(s.route).query = { ...query }
  })
  Object.values(s.api).forEach((x) => x.mockReset())
  s.api.listGlossaries.mockResolvedValue({ data: [group, groupB] })
  s.api.listTerms.mockImplementation(async (id) => ({ data: [id === 31 ? term : other] }))
  s.api.getTermWorkspace.mockImplementation(async (id) =>
    workspace(id === 301 ? term : id === 302 ? other : { ...term, id }),
  )
  s.api.getTermFieldSources.mockResolvedValue({ data: [{ id: 5, name: '销售库' }] })
  s.api.getTermFieldTables.mockResolvedValue({ data: [{ id: 11, tableName: 'orders' }] })
  s.api.getTermFieldColumns.mockResolvedValue({ data: { records: [column], total: 1 } })
  s.api.saveTermDraft.mockResolvedValue({ data: { ...term, revision: 4 } })
  s.api.deleteTerm.mockResolvedValue({ data: null })
  s.api.deleteGlossary.mockResolvedValue({ data: 1 })
  s.api.reviewTerm.mockResolvedValue({ data: null })
  pendingGovernanceWrites.value = []
})
it.each([0, 2])('已审术语只生成真实有权知识源入口(知识授权源%s)', async (source) => {
  ;(s.iam as any).systemAdmin = false
  ;(s.iam as any).datasourcesWithFunction = vi.fn((c) => (c === 'knowledge:view' && source ? [source] : []))
  const approved = { ...term, status: 'APPROVED' }
  s.api.listTerms.mockResolvedValue({ data: [approved] })
  s.api.getTermWorkspace.mockResolvedValue(workspace(approved))
  const w = page()
  try {
    await settle()
    expect(w.find('.term-detail__next').exists()).toBe(true)
    const a = w.find('.term-detail__next-link')
    expect(a.exists()).toBe(source === 2)
    if (a.exists())
      expect(JSON.parse(a.attributes('data-target')!)).toEqual({
        path: '/admin/semantics/knowledge',
        query: { datasourceId: '2' },
      })
    expect(s.api.saveTermDraft).not.toHaveBeenCalled()
  } finally {
    w.unmount()
  }
})
