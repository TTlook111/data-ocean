// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
const s = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  auth: { token: 'A' },
  context: { datasourceId: 5, snapshotId: 8, loading: false, routeSyncing: false, routeSyncError: '' },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    codes: ['knowledge:view', 'knowledge:manage', 'knowledge:approve', 'metadata:view'],
    canOnDatasource: vi.fn(),
  },
  api: {
    listBusinessKnowledge: vi.fn(),
    saveBusinessKnowledge: vi.fn(),
    actBusinessKnowledge: vi.fn(),
    businessSourceStatus: vi.fn(),
    listSnapshotTables: vi.fn(),
    listSnapshotTableColumns: vi.fn(),
    generateFromSnapshot: vi.fn(),
    readGovernanceHistory: vi.fn(),
  },
  router: { push: vi.fn() },
}))
vi.mock('vue-router', () => ({ useRoute: () => reactive(s.route), useRouter: () => s.router }))
vi.mock('../../stores/adminContext', () => ({ useAdminContextStore: () => reactive(s.context) }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => reactive(s.iam) }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => reactive(s.auth) }))
vi.mock('../../api/admin/governanceWorkflow', () => ({
  ...s.api,
  governanceError: (e: any, f: string) => e?.response?.data?.message || f,
}))
vi.mock('../../api/admin/governance', () => ({
  listSnapshotTables: s.api.listSnapshotTables,
  listSnapshotTableColumns: s.api.listSnapshotTableColumns,
}))
vi.mock('../../api/admin/knowledge', () => ({ generateFromSnapshot: s.api.generateFromSnapshot }))
import Business from './BusinessKnowledgeWorkbench.vue'
import History from './GovernanceHistoryDialog.vue'
import { pendingGovernanceWrites } from '../../composables/governanceWriteTracker'
import { pendingKnowledgeWrites } from '../../composables/knowledgeWriteTracker'
const row = {
  id: 71,
  rowVersion: 3,
  kind: 'TABLE',
  knowledgeKey: 'TABLE:orders',
  title: '订单业务粒度',
  status: 'PENDING_REVIEW',
  body: { description: '一张订单一行' },
  dependencies: ['table:orders'],
  evidence: '手册第3节',
  unresolvedItems: [],
  origin: 'MANUAL',
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
function page(c: any = Business, props = {}) {
  return shallowMount(c, { props, global: { plugins: [ElementPlus] } })
}
beforeEach(() => {
  vi.restoreAllMocks()
  s.auth.token = 'A'
  s.context.datasourceId = 5
  s.context.snapshotId = 8
  s.context.routeSyncing = false
  s.route.query = {}
  s.iam.loaded = true
  s.iam.errorMessage = ''
  s.iam.snapshot = {}
  s.iam.codes = ['knowledge:view', 'knowledge:manage', 'knowledge:approve', 'metadata:view']
  s.iam.canOnDatasource.mockImplementation((code, id) => id === 5 && reactive(s.iam).codes.includes(code))
  Object.values(s.api).forEach((x) => x.mockReset())
  s.api.listBusinessKnowledge.mockResolvedValue({ data: [row] })
  s.api.listSnapshotTables.mockResolvedValue({ data: [{ id: 1, tableName: 'orders' }] })
  s.api.listSnapshotTableColumns.mockResolvedValue({ data: [] })
  s.api.saveBusinessKnowledge.mockResolvedValue({ data: row })
  s.api.actBusinessKnowledge.mockResolvedValue({ data: null })
  s.api.businessSourceStatus.mockResolvedValue({
    data: { sourceChanged: false, currentHash: 'h1', original: [], current: [] },
  })
  s.api.generateFromSnapshot.mockResolvedValue({ data: [] })
  s.api.readGovernanceHistory.mockResolvedValue({ data: [] })
  pendingGovernanceWrites.value = []
  pendingKnowledgeWrites.value = []
})
it('无metadata辅助权保留知识依赖且不读取表字段', async () => {
  s.iam.codes = ['knowledge:view', 'knowledge:manage']
  const w = page()
  await settle()
  await (w.vm as any).open(row)
  expect(s.api.listSnapshotTables).not.toHaveBeenCalled()
  expect((w.vm as any).form.dependencies).toEqual(['table:orders'])
  expect((w.vm as any).catalogError).toContain('metadata:view')
  w.unmount()
})
it('同范围换账号旧列表不得覆盖新列表', async () => {
  const d = deferred()
  s.api.listBusinessKnowledge.mockReturnValueOnce(d.promise).mockResolvedValue({ data: [{ ...row, id: 72 }] })
  const w = page()
  await settle()
  reactive(s.auth).token = 'B'
  await settle()
  d.resolve({ data: [row] })
  await settle()
  expect((w.vm as any).rows[0].id).toBe(72)
  w.unmount()
})
it('关闭重开同修订，旧目录和来源回包不能填入新会话', async () => {
  const a = deferred(),
    b = deferred()
  s.api.listSnapshotTables.mockReturnValueOnce(a.promise).mockResolvedValue({ data: [] })
  s.api.businessSourceStatus.mockReturnValueOnce(b.promise)
  const w = page()
  await settle()
  const open = (w.vm as any).open(row)
  const sources = (w.vm as any).checkSources()
  ;(w.vm as any).dialog = false
  await settle()
  await (w.vm as any).open({ ...row, title: '新会话' })
  a.resolve({ data: [{ tableName: 'old_table' }] })
  b.resolve({ data: { sourceChanged: true, currentHash: 'old', original: [], current: [] } })
  await Promise.all([open, sources])
  expect((w.vm as any).options.some((x: any) => x.value === 'table:old_table')).toBe(false)
  expect((w.vm as any).sourceInfo).toBeUndefined()
  w.unmount()
})
it('修改依赖后旧来源核对失效，不复用确认hash', async () => {
  const d = deferred()
  s.api.businessSourceStatus.mockReturnValue(d.promise)
  const w = page()
  await settle()
  await (w.vm as any).open(row)
  const a = (w.vm as any).checkSources()
  ;(w.vm as any).form.dependencies = ['table:new']
  d.resolve({ data: { sourceChanged: true, currentHash: 'old', original: [], current: [] } })
  await a
  expect((w.vm as any).sourceInfo).toBeUndefined()
  expect((w.vm as any).sourceConfirmed).toBe(false)
  w.unmount()
})
it('AI候选双击一次，晚回不能关闭或覆盖新增人工草稿', async () => {
  const d = deferred()
  s.api.saveBusinessKnowledge.mockReturnValue(d.promise)
  const w = page()
  await settle()
  await (w.vm as any).open(row)
  const a = (w.vm as any).save(true)
  await (w.vm as any).save(true)
  expect(s.api.saveBusinessKnowledge).toHaveBeenCalledTimes(1)
  ;(w.vm as any).form.body.description = '等待时补充人工依据'
  d.resolve({ data: { ...row, id: 72 } })
  await a
  expect((w.vm as any).form.body.description).toBe('等待时补充人工依据')
  expect((w.vm as any).dialog).toBe(true)
  expect((w.vm as any).editorError).toContain('新输入仍保留')
  w.unmount()
})
it('保存409保留精确版本/hash与人工依据', async () => {
  s.api.saveBusinessKnowledge.mockRejectedValue({ response: { status: 409, data: { message: '修订冲突' } } })
  const w = page()
  await settle()
  await (w.vm as any).open(row)
  await (w.vm as any).save(false)
  expect(s.api.saveBusinessKnowledge).toHaveBeenCalledWith(
    8,
    expect.objectContaining({ expectedRevisionId: 71, expectedVersion: 3, evidence: '手册第3节' }),
    false,
  )
  expect((w.vm as any).editorError).toBe('修订冲突')
  expect((w.vm as any).dialog).toBe(true)
  w.unmount()
})
it('审核409保留处理依据，重复确认一次请求', async () => {
  const d = deferred()
  s.api.actBusinessKnowledge.mockReturnValue(d.promise)
  const w = page()
  await settle()
  await (w.vm as any).action(row, 'APPROVE')
  ;(w.vm as any).actionNote = '资料v2第3节'
  const a = (w.vm as any).confirmAction()
  await (w.vm as any).confirmAction()
  expect(s.api.actBusinessKnowledge).toHaveBeenCalledTimes(1)
  d.reject({ response: { status: 409, data: { message: '版本已变化' } } })
  await a
  expect((w.vm as any).actionNote).toBe('资料v2第3节')
  expect((w.vm as any).actionTarget.row.id).toBe(71)
  expect((w.vm as any).actionError).toBe('版本已变化')
  w.unmount()
})
it('确认后撤权零写，撤回必须同时manage和approve', async () => {
  s.iam.codes = ['knowledge:view', 'knowledge:approve']
  const w = page()
  await settle()
  await (w.vm as any).action({ ...row, status: 'VALID' }, 'REVOKE')
  expect((w.vm as any).actionTarget).toBeUndefined()
  await (w.vm as any).action(row, 'APPROVE')
  ;(w.vm as any).actionNote = '依据'
  reactive(s.iam).snapshot = { revision: 2 }
  reactive(s.iam).codes = ['knowledge:view']
  await settle()
  await (w.vm as any).confirmAction()
  expect(s.api.actBusinessKnowledge).not.toHaveBeenCalled()
  w.unmount()
})
it('整组生成复用文档页同源同快照锁，不能跨页重复生成', async () => {
  pendingKnowledgeWrites.value = [
    { identity: 'A', source: 5, snapshotId: 8, kind: 'generate-group', started: 1 },
  ]
  const w = page()
  await settle()
  await (w.vm as any).generateDocuments()
  expect(s.api.generateFromSnapshot).not.toHaveBeenCalled()
  expect((w.vm as any).error).toContain('不能重复')
  w.unmount()
})
it('历史同源换账号旧操作记录不展示', async () => {
  const d = deferred()
  s.api.readGovernanceHistory.mockReturnValueOnce(d.promise).mockResolvedValue({ data: [] })
  const w = page(History, { id: 71, kind: 'BUSINESS' })
  await settle()
  reactive(s.auth).token = 'B'
  await settle()
  d.resolve({ data: [{ id: 9, action: 'APPROVE', createdAt: '2026-10-29', payload: { note: '旧账号' } }] })
  await settle()
  expect((w.vm as any).rows).toEqual([])
  expect(w.emitted('close')).toBeTruthy()
  expect(s.api.readGovernanceHistory).toHaveBeenCalledTimes(1)
  w.unmount()
})
