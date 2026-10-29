// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const s = vi.hoisted(() => ({
  auth: { token: 'A' },
  route: { query: {} as Record<string, unknown> },
  context: {
    datasourceId: 5,
    snapshotId: 8 as number | undefined,
    snapshots: [{ id: 8, datasourceId: 5 }],
    loading: false,
    routeSyncing: false,
    routeSyncError: '',
    initialize: vi.fn(),
    selectSnapshot: vi.fn(),
    ensureSnapshot: vi.fn(),
  },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    codes: ['governance:field:view', 'governance:field:manage', 'metadata:view'],
    canOnDatasource: vi.fn(),
  },
  api: {
    listSnapshotTables: vi.fn(),
    listSnapshotTableColumns: vi.fn(),
    currentUsageProfile: vi.fn(),
    usageEvidence: vi.fn(),
    listSnapshots: vi.fn(),
    listPredefinedTags: vi.fn(),
    getFieldTags: vi.fn(),
    batchAddFieldTags: vi.fn(),
    autoTagByPattern: vi.fn(),
    pageConfidence: vi.fn(),
    adminSetConfidence: vi.fn(),
    getConfidenceTrend: vi.fn(),
  },
}))
vi.mock('vue-router', () => ({ useRoute: () => reactive(s.route) }))
vi.mock('../../stores/adminContext', () => ({ useAdminContextStore: () => reactive(s.context) }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => reactive(s.auth) }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => reactive(s.iam) }))
vi.mock('../../api/admin/governance', () => ({
  listSnapshotTables: s.api.listSnapshotTables,
  listSnapshotTableColumns: s.api.listSnapshotTableColumns,
}))
vi.mock('../../api/admin/governanceWorkflow', () => ({
  currentUsageProfile: s.api.currentUsageProfile,
  usageEvidence: s.api.usageEvidence,
}))
vi.mock('../../api/admin/metadata', () => ({ listSnapshots: s.api.listSnapshots }))
vi.mock('../../api/admin/field', () => s.api)
vi.mock('../../composables/useGsapMotion', () => ({
  useGsapMotion: () => ({ reveal: vi.fn(), withContext: (f: any) => f() }),
}))
import Profiles from './GovernanceUsageProfiles.vue'
import Tags from '../../views/admin/field/FieldTagManager.vue'
import Legacy from '../../views/admin/field/ConfidenceDashboard.vue'
import { pendingGovernanceWrites } from '../../composables/governanceWriteTracker'
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
function page(c: any, props = {}) {
  return shallowMount(c, { props, global: { plugins: [ElementPlus] } })
}
const profile = {
  columnMetaId: 101,
  fieldName: 'orders.amount',
  revisionId: 51,
  reviewStatus: 'APPROVED',
  score: 0,
  evidenceCount: 0,
  tags: ['MONEY'],
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
  s.iam.codes = ['governance:field:view', 'governance:field:manage', 'metadata:view']
  s.iam.canOnDatasource.mockImplementation((code, id) => id === 5 && reactive(s.iam).codes.includes(code))
  Object.values(s.api).forEach((x) => x.mockReset())
  s.context.initialize.mockResolvedValue(undefined)
  s.api.listSnapshotTables.mockResolvedValue({ data: [{ id: 1, tableName: 'orders' }] })
  s.api.listSnapshotTableColumns.mockResolvedValue({
    data: [
      { id: 101, columnName: 'amount' },
      { id: 102, columnName: 'order_id' },
    ],
  })
  s.api.currentUsageProfile.mockImplementation(async (_snap, id) => ({
    data: { ...profile, columnMetaId: id },
  }))
  s.api.usageEvidence.mockResolvedValue({ data: { records: [], total: 0, revisionId: 51 } })
  s.api.listPredefinedTags.mockResolvedValue({ data: [{ tagCode: 'MONEY', tagName: '金额' }] })
  s.api.getFieldTags.mockResolvedValue({ data: [] })
  s.api.batchAddFieldTags.mockResolvedValue({ data: { tagged: 0 } })
  s.api.autoTagByPattern.mockResolvedValue({ data: { tagged: 0, skipped: 2 } })
  s.api.pageConfidence.mockResolvedValue({
    data: { records: [{ columnMetaId: 101, score: 0, level: 'LOW' }], total: 0 },
  })
  s.api.adminSetConfidence.mockResolvedValue({ data: {} })
  s.api.getConfidenceTrend.mockResolvedValue({ data: [] })
  pendingGovernanceWrites.value = []
})
it('当前档案无metadata辅助权不读目录', async () => {
  s.iam.codes = ['governance:field:view']
  const w = page(Profiles)
  await settle()
  expect(s.api.listSnapshotTables).not.toHaveBeenCalled()
  expect((w.vm as any).error).toContain('metadata:view')
  w.unmount()
})
it('字段档案部分失败保留已知0分，不把失败当0', async () => {
  s.api.currentUsageProfile.mockImplementation(async (_snap, id) => {
    if (id === 102) throw new Error('fail')
    return { data: profile }
  })
  const w = page(Profiles)
  await settle()
  ;(w.vm as any).selectedTable = 'orders'
  await (w.vm as any).load()
  expect((w.vm as any).rows).toHaveLength(1)
  expect((w.vm as any).rows[0].score).toBe(0)
  expect((w.vm as any).error).toContain('1 个字段档案读取失败')
  w.unmount()
})
it('证据修订必须等于当前档案修订，不能冒领他版样例', async () => {
  s.api.usageEvidence.mockResolvedValue({ data: { revisionId: 52, records: [{ id: 1 }], total: 1 } })
  const w = page(Profiles)
  await settle()
  ;(w.vm as any).selectedTable = 'orders'
  await (w.vm as any).load()
  ;(w.vm as any).openEvidence((w.vm as any).rows[0])
  await settle()
  expect((w.vm as any).evidenceRows).toEqual([])
  expect((w.vm as any).evidenceError).toContain('字段修订已变化')
  w.unmount()
})
it('证据关闭后旧响应不显示，换账号旧档案清空', async () => {
  const d = deferred()
  s.api.usageEvidence.mockReturnValue(d.promise)
  const w = page(Profiles)
  await settle()
  ;(w.vm as any).selectedTable = 'orders'
  await (w.vm as any).load()
  ;(w.vm as any).openEvidence((w.vm as any).rows[0])
  ;(w.vm as any).closeEvidence(false)
  d.resolve({ data: { revisionId: 51, records: [{ id: 1 }], total: 1 } })
  await settle()
  expect((w.vm as any).evidenceRows).toEqual([])
  reactive(s.auth).token = 'B'
  await settle()
  expect((w.vm as any).rows).toEqual([])
  w.unmount()
})
it('标签批量固定打开时ids，双击一次，真实新增0保留', async () => {
  const d = deferred()
  s.api.batchAddFieldTags.mockReturnValue(d.promise)
  const w = page(Tags, { embedded: true })
  await settle()
  ;(w.vm as any).query.tableName = 'orders'
  await (w.vm as any).fetchColumns()
  ;(w.vm as any).selectedColumnIds = [101]
  ;(w.vm as any).openBatchTagDialog()
  ;(w.vm as any).selectedColumnIds = [102]
  ;(w.vm as any).selectedTagCode = 'MONEY'
  const a = (w.vm as any).confirmBatchTag()
  await (w.vm as any).confirmBatchTag()
  expect(s.api.batchAddFieldTags).toHaveBeenCalledTimes(1)
  expect(s.api.batchAddFieldTags).toHaveBeenCalledWith({ columnMetaIds: [101], tagCode: 'MONEY' })
  d.resolve({ data: { tagged: 0 } })
  await a
  expect((w.vm as any).writeReceipt).toContain('新增 0')
  w.unmount()
})
it('标签详情A慢B快不能将A标签归给B，关闭不重开', async () => {
  const d = deferred()
  s.api.getFieldTags.mockReturnValueOnce(d.promise).mockResolvedValue({ data: [{ tagCode: 'B' }] })
  const w = page(Tags, { embedded: true })
  await settle()
  ;(w.vm as any).query.tableName = 'orders'
  await (w.vm as any).fetchColumns()
  const a = (w.vm as any).viewColumnTags((w.vm as any).columns[0])
  await (w.vm as any).viewColumnTags((w.vm as any).columns[1])
  d.resolve({ data: [{ tagCode: 'A' }] })
  await a
  expect((w.vm as any).currentColumnName).toBe('order_id')
  expect((w.vm as any).currentColumnTags[0].tagCode).toBe('B')
  w.unmount()
})
it('源级模式打标确认说明全版本，确认期间撤权零写', async () => {
  const d = deferred()
  const confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as never)
  const w = page(Tags, { embedded: true })
  await settle()
  const a = (w.vm as any).handleAutoTag()
  await (w.vm as any).handleAutoTag()
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(confirm.mock.calls[0][0]).toContain('全部采集版本')
  reactive(s.iam).snapshot = { r: 2 }
  reactive(s.iam).codes = ['governance:field:view', 'metadata:view']
  await settle()
  d.resolve('confirm')
  await a
  expect(s.api.autoTagByPattern).not.toHaveBeenCalled()
  w.unmount()
})
it('标签残缺回执留未知锁，不能自动重发', async () => {
  s.api.batchAddFieldTags.mockResolvedValue({ data: {} })
  const w = page(Tags, { embedded: true })
  await settle()
  ;(w.vm as any).query.tableName = 'orders'
  await (w.vm as any).fetchColumns()
  ;(w.vm as any).selectedColumnIds = [101]
  ;(w.vm as any).openBatchTagDialog()
  ;(w.vm as any).selectedTagCode = 'MONEY'
  await (w.vm as any).confirmBatchTag()
  await (w.vm as any).confirmBatchTag()
  expect(s.api.batchAddFieldTags).toHaveBeenCalledTimes(1)
  expect(pendingGovernanceWrites.value[0].state).toBe('unknown')
  w.unmount()
})
it('标签无效显式快照不回退公共快照', async () => {
  s.route.query.snapshotId = 'bad'
  const w = page(Tags, { embedded: true })
  await settle()
  expect(s.api.listSnapshotTables).not.toHaveBeenCalled()
  expect((w.vm as any).snapshotError).toContain('未切换')
  w.unmount()
})
it('旧来源分是源级，无公共快照也可读；统计失败显示未知', async () => {
  s.context.snapshotId = undefined
  s.api.pageConfidence.mockRejectedValue(new Error('fail'))
  const w = page(Legacy)
  await settle()
  expect(s.api.pageConfidence).toHaveBeenCalled()
  expect((w.vm as any).levelCounts.LOW).toBeNull()
  expect((w.vm as any).countError).toContain('不可用')
  expect((w.vm as any).pageError).toContain('读取失败')
  w.unmount()
})
it('旧来源分手调双击一次，409保留分数0和原因', async () => {
  const d = deferred()
  s.api.adminSetConfidence.mockReturnValue(d.promise)
  const w = page(Legacy)
  await settle()
  ;(w.vm as any).openSetDialog(101, 0)
  ;(w.vm as any).setForm.reason = '人工追溯'
  const a = (w.vm as any).confirmSetScore()
  await (w.vm as any).confirmSetScore()
  expect(s.api.adminSetConfidence).toHaveBeenCalledTimes(1)
  d.reject({ response: { status: 409, data: { message: '旧分已变化' } } })
  await a
  expect((w.vm as any).setForm.score).toBe(0)
  expect((w.vm as any).setError).toBe('旧分已变化')
  expect((w.vm as any).showSetDialog).toBe(true)
  w.unmount()
})
it('旧趋势关闭后晚回不重新打开，同源换账号旧列表失效', async () => {
  const d = deferred()
  s.api.getConfidenceTrend.mockReturnValue(d.promise)
  const w = page(Legacy)
  await settle()
  const a = (w.vm as any).openTrend(101)
  expect((w.vm as any).trendLoading).toBe(true)
  ;(w.vm as any).showTrendDialog = false
  expect((w.vm as any).trendLoading).toBe(false)
  d.resolve({ data: [{ time: '2026', cumulativeScore: 80 }] })
  await a
  expect((w.vm as any).showTrendDialog).toBe(false)
  expect((w.vm as any).trendData).toEqual([])
  reactive(s.auth).token = 'B'
  await settle()
  expect((w.vm as any).showSetDialog).toBe(false)
  w.unmount()
})
