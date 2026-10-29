// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
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
vi.mock('../../stores/auth', () => ({ useAuthStore: () => reactive(s.auth) }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => reactive(s.iam) }))
vi.mock('../../api/admin/glossary', () => s.api)
import Glossaries from '../../views/admin/semantics/GlossariesView.vue'
import Editor from './TermEditor.vue'
import Picker from './TermFieldPicker.vue'
import { pendingGovernanceWrites } from '../../composables/governanceWriteTracker'
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
function deferred() {
  let resolve!: (x: any) => void, reject!: (x: any) => void
  const promise = new Promise<any>((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
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
        RouterLink: { template: '<a><slot/></a>' },
        TermEditor: c === Glossaries,
        TermFieldPicker: c === Editor,
      },
    },
  })
}
function vm(w: any) {
  return w.vm.$.setupState as any
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
it.each(['bad', '999'])('显式分组%s不选第一组且不读取其他分组术语', async (raw) => {
  s.route.query = { glossaryId: raw }
  const w = page()
  await settle()
  expect(s.api.listTerms).not.toHaveBeenCalled()
  expect(vm(w).selectedGlossaryId).toBeUndefined()
  expect(vm(w).glossaryError).toContain('未改用')
  w.unmount()
})
it('跨分组URL恢复同时取原术语，不push覆盖正在恢复的URL', async () => {
  const w = page()
  await settle()
  expect(s.router.push).not.toHaveBeenCalled()
  reactive(s.route).query = { glossaryId: '32', termId: '302' }
  await settle()
  expect(vm(w).selectedGlossaryId).toBe(32)
  expect(vm(w).selectedTermId).toBe(302)
  expect(s.router.push).not.toHaveBeenCalled()
  reactive(s.route).query = { glossaryId: '31', termId: '301' }
  await settle()
  expect(vm(w).selectedTermId).toBe(301)
  expect(vm(w).terms[0].id).toBe(301)
  expect(s.router.push).not.toHaveBeenCalled()
  w.unmount()
})
it('A分组列表慢B快时旧列表不能覆盖B术语和映射', async () => {
  const d = deferred()
  s.api.listTerms.mockImplementation((id) => (id === 31 ? d.promise : Promise.resolve({ data: [other] })))
  const w = page()
  await settle()
  reactive(s.route).query = { glossaryId: '32', termId: '302' }
  await settle()
  d.resolve({ data: [term] })
  await settle()
  expect(vm(w).terms[0].id).toBe(302)
  expect(vm(w).selectedTermId).toBe(302)
  w.unmount()
})
it('同范围换账号旧分组/失败回包不覆盖新账号', async () => {
  const d = deferred()
  s.api.listGlossaries.mockReturnValueOnce(d.promise).mockResolvedValue({ data: [groupB] })
  const w = page()
  await settle()
  reactive(s.route).query = { glossaryId: '32', termId: '302' }
  reactive(s.auth).token = 'B'
  await settle()
  d.resolve({ data: [group] })
  await settle()
  expect(vm(w).glossaries[0].id).toBe(32)
  expect(vm(w).terms[0].id).toBe(302)
  w.unmount()
})
it('术语删除确认撤权零写，重复点击只一个确认', async () => {
  const d = deferred(),
    confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as never)
  const w = page()
  await settle()
  const a = vm(w).removeTerm(term)
  await vm(w).removeTerm(term)
  expect(confirm).toHaveBeenCalledTimes(1)
  reactive(s.iam).snapshot = { r: 2 }
  reactive(s.iam).codes = ['glossary:view']
  await settle()
  d.resolve('confirm')
  await a
  expect(s.api.deleteTerm).not.toHaveBeenCalled()
  w.unmount()
})
it('分组删除说明级联影响，导航挂起换账号后旧回调不重选首组', async () => {
  const nav = deferred()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  const w = page()
  await settle()
  s.router.push.mockReturnValueOnce(nav.promise)
  const a = vm(w).removeGlossary(group)
  await settle()
  expect(vi.mocked(ElMessageBox.confirm).mock.calls[0][0]).toContain('全部术语与字段关联')
  reactive(s.route).query = { glossaryId: '32', termId: '302' }
  reactive(s.auth).token = 'B'
  await settle()
  const reads = s.api.listGlossaries.mock.calls.length
  nav.resolve(undefined)
  await a
  await settle()
  expect(s.api.listGlossaries).toHaveBeenCalledTimes(reads)
  expect(vm(w).selectedGlossaryId).toBe(32)
  w.unmount()
})
it('主动选择导航挂起后新选择取胜，不重读旧选择', async () => {
  const nav = deferred()
  const w = page()
  await settle()
  s.router.push.mockReturnValueOnce(nav.promise)
  const a = vm(w).selectGlossary(32)
  await settle()
  await vm(w).selectGlossary(31, true)
  await settle()
  const reads = s.api.listTerms.mock.calls.length
  nav.resolve(undefined)
  await a
  expect(s.api.listTerms).toHaveBeenCalledTimes(reads)
  expect(vm(w).selectedTermId).toBe(301)
  w.unmount()
})
it('审核409保留拒绝依据与精确修订，重复一次', async () => {
  s.route.query = { glossaryId: '32', termId: '302' }
  const d = deferred()
  s.api.reviewTerm.mockReturnValue(d.promise)
  const w = page()
  await settle()
  vm(w).review(vm(w).selectedTerm, false)
  vm(w).reviewReason = '缺少完整业务口径'
  const a = vm(w).confirmReview()
  await vm(w).confirmReview()
  expect(s.api.reviewTerm).toHaveBeenCalledTimes(1)
  expect(s.api.reviewTerm).toHaveBeenCalledWith(302, false, '缺少完整业务口径', 3)
  d.reject({ response: { status: 409, data: { message: '修订冲突' } } })
  await a
  expect(vm(w).reviewTarget.term.id).toBe(302)
  expect(vm(w).reviewReason).toBe('缺少完整业务口径')
  expect(vm(w).reviewError).toBe('修订冲突')
  w.unmount()
})
it('术语目录仅依glossary能力可读，不要求metadata或datasource:view', async () => {
  const w = page()
  await settle()
  expect(s.api.getTermFieldSources).toHaveBeenCalled()
  expect(s.api.listTerms).toHaveBeenCalled()
  expect(s.iam.codes).not.toContain('metadata:view')
  w.unmount()
})
it('编辑A慢B快不以A覆盖B，定义与映射都取同一fresh修订', async () => {
  const d = deferred()
  s.api.getTermWorkspace
    .mockReturnValueOnce(d.promise)
    .mockResolvedValue(workspace({ ...term, id: 303, name: 'B' }))
  const w = page(Editor, { groupId: 31, sources: [{ id: 5, name: '销售库' }] })
  const a = vm(w).open(term)
  await vm(w).open({ ...term, id: 303, name: 'B' })
  d.resolve(workspace(term))
  await a
  expect(vm(w).id).toBe(303)
  expect(vm(w).form.description).toBe('精确修订定义303')
  expect(vm(w).columns[0].entityId).toBe(901)
  w.unmount()
})
it('术语写双击一次，旧保存晚回不关闭新对象编辑器', async () => {
  const d = deferred()
  s.api.saveTermDraft.mockReturnValue(d.promise)
  const w = page(Editor, { groupId: 31, sources: [{ id: 5, name: '销售库' }] })
  await vm(w).open(term)
  const a = vm(w).save(false)
  await vm(w).save(false)
  expect(s.api.saveTermDraft).toHaveBeenCalledTimes(1)
  await vm(w).open({ ...term, id: 303 })
  d.resolve({ data: { ...term, revision: 4 } })
  await a
  expect(vm(w).visible).toBe(true)
  expect(vm(w).id).toBe(303)
  expect(w.emitted('saved')).toBeUndefined()
  w.unmount()
})
it('工作面读取失败禁止以空映射保存，即使清错误文案也不绕过', async () => {
  s.api.getTermWorkspace.mockRejectedValue(new Error('read failed'))
  const w = page(Editor, { groupId: 31, sources: [] })
  await vm(w).open(term)
  vm(w).error = ''
  await vm(w).save(false)
  expect(s.api.saveTermDraft).not.toHaveBeenCalled()
  expect(vm(w).workspaceVerified).toBe(false)
  w.unmount()
})
it('可修正写错误保留草稿后允许显式再保存，revision不自动改变', async () => {
  s.api.saveTermDraft
    .mockRejectedValueOnce({ response: { status: 400, data: { message: '请修正定义' } } })
    .mockResolvedValue({ data: { ...term, revision: 4 } })
  const w = page(Editor, { groupId: 31, sources: [{ id: 5, name: '销售库' }] })
  await vm(w).open(term)
  await vm(w).save(false)
  expect(vm(w).error).toBe('请修正定义')
  vm(w).form.description = '人工修正后定义'
  await vm(w).save(false)
  expect(s.api.saveTermDraft).toHaveBeenCalledTimes(2)
  expect(s.api.saveTermDraft.mock.calls[1][2].revision).toBe(3)
  expect(s.api.saveTermDraft.mock.calls[1][2].description).toBe('人工修正后定义')
  w.unmount()
})
it('已通过/待审核术语不能直接编辑；失效映射不自动删成无绑定', async () => {
  const w = page(Editor, { groupId: 31, sources: [{ id: 5, name: '销售库' }] })
  await vm(w).open({ ...term, status: 'APPROVED' })
  expect(s.api.getTermWorkspace).not.toHaveBeenCalled()
  s.api.getTermWorkspace.mockResolvedValue({ data: { ...workspace().data, mappingError: '来源映射BROKEN' } })
  await vm(w).open(term)
  await vm(w).save(false)
  expect(s.api.saveTermDraft).not.toHaveBeenCalled()
  expect(vm(w).columns).toHaveLength(1)
  w.unmount()
})
it('字段picker撤权/换账号后旧目录回包失效，已选映射不自动清空', async () => {
  const d = deferred()
  s.api.getTermFieldTables.mockReturnValue(d.promise)
  const w = page(Picker, { modelValue: [column], sources: [{ id: 5, name: '销售库' }] })
  await settle()
  reactive(s.auth).token = 'B'
  reactive(s.iam).snapshot = { r: 2 }
  reactive(s.iam).codes = ['glossary:view']
  await settle()
  d.resolve({ data: [{ id: 11, tableName: 'old_orders' }] })
  await settle()
  expect(vm(w).tables).toEqual([])
  expect(vm(w).columns).toEqual([])
  expect(w.emitted('update:modelValue')).toBeUndefined()
  w.unmount()
})
it('字段picker无精确源维护能力时不能增删已选映射', async () => {
  s.iam.codes = ['glossary:view']
  const w = page(Picker, { modelValue: [column], sources: [{ id: 5, name: '销售库' }] })
  await settle()
  vm(w).toggle(column)
  expect(w.emitted('update:modelValue')).toBeUndefined()
  expect(s.api.getTermFieldTables).not.toHaveBeenCalled()
  w.unmount()
})

it('术语删除后导航挂起再换身份，旧回调不读取新账号列表', async () => {
  const nav = deferred()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  const w = page()
  await settle()
  s.router.push.mockReturnValueOnce(nav.promise)
  const a = vm(w).removeTerm(term)
  await settle()
  reactive(s.route).query = { glossaryId: '32', termId: '302' }
  reactive(s.auth).token = 'B'
  await settle()
  const reads = s.api.listTerms.mock.calls.length
  nav.resolve(undefined)
  await a
  expect(s.api.listTerms).toHaveBeenCalledTimes(reads)
  expect(vm(w).selectedTermId).toBe(302)
  w.unmount()
})
it('保存完成导航挂起后切到别组，旧完成不能重读新组', async () => {
  const nav = deferred()
  const w = page()
  await settle()
  s.router.push.mockReturnValueOnce(nav.promise)
  const a = vm(w).onTermSaved({ ...term, id: 303, revision: 4 })
  await settle()
  reactive(s.route).query = { glossaryId: '32', termId: '302' }
  await settle()
  const reads = s.api.listTerms.mock.calls.length
  nav.resolve(undefined)
  await a
  expect(s.api.listTerms).toHaveBeenCalledTimes(reads)
  expect(vm(w).selectedTermId).toBe(302)
  w.unmount()
})
