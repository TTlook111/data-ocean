// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({
  route: { query: { datasourceId: '5', snapshotId: '8' } as Record<string, unknown> },
  auth: { token: 'A', currentUser: { id: 7 }, user: null },
  context: {
    datasourceId: 5,
    snapshotId: 8 as number | undefined,
    datasources: [{ id: 5, name: '源5' }],
    snapshots: [{ id: 8, datasourceId: 5, snapshotVersion: 1, qualityScore: 0 }],
    currentSnapshot: { id: 8, snapshotVersion: 1, qualityScore: 0 },
    initialized: true,
    loading: false,
    routeSyncing: false,
    routeSyncError: '',
    initialize: vi.fn(),
  },
  iam: {
    loaded: true,
    errorMessage: null,
    snapshot: {},
    codes: [
      'metadata:view',
      'governance:view',
      'governance:check',
      'governance:issue:view',
      'governance:issue:manage',
      'governance:rule:view',
    ],
    globals: [] as string[],
    systemAdmin: false,
    canOnDatasource: vi.fn(),
    hasGlobal: vi.fn(),
    load: vi.fn(),
  },
  api: {
    getLatestQualityCheckBatch: vi.fn(),
    getQualityRuleSummary: vi.fn(),
    getSnapshotQualityIssueSummary: vi.fn(),
    triggerQualityCheck: vi.fn(),
    listQualityIssues: vi.fn(),
    handleIssue: vi.fn(),
    batchHandleIssues: vi.fn(),
    assignIssue: vi.fn(),
    listReviewRecords: vi.fn(),
    listSnapshotTables: vi.fn(),
    listUsers: vi.fn(),
  },
  router: { push: vi.fn(), replace: vi.fn() },
}))
vi.mock('vue-router', () => ({ useRoute: () => reactive(state.route), useRouter: () => state.router }))
vi.mock('../../../stores/adminContext', () => ({ useAdminContextStore: () => reactive(state.context) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(state.iam) }))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(state.auth) }))
vi.mock('../../../api/admin/governance', () => state.api)
vi.mock('../../../api/admin/user', () => ({ listUsers: state.api.listUsers }))
import Overview from './QualityDashboard.vue'
import Issues from './IssueList.vue'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
function deferred() {
  let resolve!: (x: any) => void, reject!: (x: any) => void
  const promise = new Promise<any>((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
const issue = {
  id: 1,
  snapshotId: 8,
  datasourceId: 5,
  issueDomain: 'SEMANTIC',
  tableName: 'orders',
  columnMetaId: 101,
  columnName: 'amount',
  status: 'CONFIRMED',
  issueDescription: '需复查',
}
async function settle() {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await nextTick()
  }
}
function page(component: any) {
  return shallowMount(component, { global: { plugins: [ElementPlus], stubs:{RouterLink:{template:'<a><slot/></a>'}} } })
}
beforeEach(() => {
  vi.restoreAllMocks()
  state.auth.token = 'A'
  state.route.query = { datasourceId: '5', snapshotId: '8' }
  state.context.snapshotId = 8
  state.context.datasourceId = 5
  state.context.loading = false
  state.iam.loaded = true
  state.iam.snapshot = {}
  state.iam.codes = [
    'metadata:view',
    'governance:view',
    'governance:check',
    'governance:issue:view',
    'governance:issue:manage',
    'governance:rule:view',
  ]
  state.iam.globals = []
  state.iam.canOnDatasource
    .mockReset()
    .mockImplementation((code, id) => id === 5 && reactive(state.iam).codes.includes(code))
  state.iam.hasGlobal.mockReset().mockImplementation((code) => reactive(state.iam).globals.includes(code))
  state.iam.load.mockResolvedValue(undefined)
  state.context.initialize.mockResolvedValue(undefined)
  Object.values(state.api).forEach((x) => x.mockReset())
  state.api.getLatestQualityCheckBatch.mockResolvedValue({ data: null })
  state.api.getQualityRuleSummary.mockResolvedValue({ data: { enabledCount: 0, applicableCount: 0 } })
  state.api.getSnapshotQualityIssueSummary.mockResolvedValue({
    data: {
      totalUnresolved: 0,
      domainCounts: { SOURCE: 0, SEMANTIC: 0 },
      statusCounts: {},
      recentIssues: [],
    },
  })
  state.api.listQualityIssues.mockResolvedValue({ data: { records: [issue], total: 1 } })
  state.api.listSnapshotTables.mockResolvedValue({ data: [] })
  state.api.listUsers.mockResolvedValue({ data: { records: [], total: 0 } })
  state.api.handleIssue.mockResolvedValue({ data: null })
  state.api.batchHandleIssues.mockResolvedValue({
    data: { updated: 0, skipped: 1, skippedIssues: [{ issueId: 1, reason: '需最新复查及依据' }] },
  })
  pendingGovernanceWrites.value = []
})
afterEach(() => vi.restoreAllMocks())
it('总览同范围换账号旧批次/失败不覆盖新批次', async () => {
  const old = deferred(),
    latest = deferred()
  state.api.getLatestQualityCheckBatch.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
  const w = page(Overview)
  await settle()
  reactive(state.auth).token = 'B'
  await settle()
  old.resolve({ data: { id: 1, status: 'SUCCESS', complete: true } })
  await settle()
  expect((w.vm as any).latestBatch).toBeNull()
  expect((w.vm as any).batchLoading).toBe(true)
  latest.resolve({ data: { id: 2, status: 'PARTIAL', complete: false } })
  await settle()
  expect((w.vm as any).latestBatch.id).toBe(2)
  w.unmount()
})
it('总览缺公共快照不调用任何治理快照API，不补metadata权', async () => {
  state.context.snapshotId = undefined
  state.iam.codes = ['governance:view']
  const w = page(Overview)
  await settle()
  expect(state.api.getLatestQualityCheckBatch).not.toHaveBeenCalled()
  expect(w.text()).toContain('metadata:view')
  w.unmount()
})
it('来源检查双击只一次、网络未知不能重发，旧SUCCESS不证明本次', async () => {
  const pending = deferred()
  state.api.triggerQualityCheck.mockReturnValue(pending.promise)
  const w = page(Overview)
  await settle()
  const a = (w.vm as any).runCheck()
  await (w.vm as any).runCheck()
  expect(state.api.triggerQualityCheck).toHaveBeenCalledTimes(1)
  pending.reject(new Error('网络未知'))
  await a
  await (w.vm as any).runCheck()
  expect(state.api.triggerQualityCheck).toHaveBeenCalledTimes(1)
  expect(pendingGovernanceWrites.value[0].state).toBe('unknown')
  w.unmount()
})
it('问题辅助目录独立无权零请求，仍读取已核验范围的问题', async () => {
  state.iam.codes = ['governance:issue:view']
  const w = page(Issues)
  await settle()
  expect(state.api.listSnapshotTables).not.toHaveBeenCalled()
  expect(state.api.listUsers).not.toHaveBeenCalled()
  expect(state.api.listQualityIssues).toHaveBeenCalledWith(8, expect.any(Object))
  w.unmount()
})
it('非法显式源不使用旧公共范围', async () => {
  state.route.query = { datasourceId: 'bad', snapshotId: '8' }
  const w = page(Issues)
  await settle()
  expect(state.api.listQualityIssues).not.toHaveBeenCalled()
  w.unmount()
})
it('同范围换账号使旧问题列表和详情失效', async () => {
  const old = deferred(),
    latest = deferred()
  state.api.listQualityIssues.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
  const w = page(Issues)
  await settle()
  reactive(state.auth).token = 'B'
  await settle()
  old.resolve({ data: { records: [issue], total: 1 } })
  await settle()
  expect((w.vm as any).issues).toEqual([])
  latest.resolve({ data: { records: [{ ...issue, id: 2 }], total: 9 } })
  await settle()
  expect((w.vm as any).total).toBe(9)
  expect((w.vm as any).issues[0].id).toBe(2)
  w.unmount()
})
it('语义解决先输入依据，409保留本问题原稿，不自动再发', async () => {
  state.api.handleIssue.mockRejectedValue({ response: { status: 409, data: { message: '当前修订已变化' } } })
  const w = page(Issues)
  await settle()
  await (w.vm as any).doHandle(issue, 'RESOLVED')
  expect(state.api.handleIssue).not.toHaveBeenCalled()
  ;(w.vm as any).resolutionNote = '修订R1，复查61，人工资料第3节'
  await (w.vm as any).confirmResolution()
  expect(state.api.handleIssue).toHaveBeenCalledWith(1, {
    status: 'RESOLVED',
    resolutionNote: '修订R1，复查61，人工资料第3节',
  })
  expect((w.vm as any).resolutionTarget.issue.id).toBe(1)
  expect((w.vm as any).resolutionNote).toContain('修订R1')
  expect((w.vm as any).resolutionError).toBe('当前修订已变化')
  w.unmount()
})
it('批量部分成功保留每条原因，重复点击一批请求', async () => {
  const pending = deferred()
  state.api.batchHandleIssues.mockReturnValue(pending.promise)
  const w = page(Issues)
  await settle()
  ;(w.vm as any).selectedIds = [1]
  const a = (w.vm as any).doBatchHandle('REJECTED')
  await (w.vm as any).doBatchHandle('REJECTED')
  expect(state.api.batchHandleIssues).toHaveBeenCalledTimes(1)
  pending.resolve({
    data: { updated: 0, skipped: 1, skippedIssues: [{ issueId: 1, reason: '需最新复查及依据' }] },
  })
  await a
  expect((w.vm as any).batchResult.skippedIssues).toEqual([{ issueId: 1, reason: '需最新复查及依据' }])
  w.unmount()
})
it('重新打开确认期间切快照后不发送旧写入', async () => {
  const confirm = deferred()
  vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(confirm.promise as never)
  const w = page(Issues)
  await settle()
  const a = (w.vm as any).reopenIssue({ ...issue, status: 'RESOLVED' })
  reactive(state.context).snapshotId = 9
  await settle()
  confirm.resolve('confirm')
  await a
  expect(state.api.handleIssue).not.toHaveBeenCalled()
  w.unmount()
})
it('语义处理双击冻结一次请求，撤权后旧成功不能关闭新范围状态', async () => {
  const pending = deferred()
  state.api.handleIssue.mockReturnValue(pending.promise)
  const w = page(Issues)
  await settle()
  await (w.vm as any).doHandle(issue, 'RESOLVED')
  ;(w.vm as any).resolutionNote = '人工依据'
  const a = (w.vm as any).confirmResolution()
  await (w.vm as any).confirmResolution()
  expect(state.api.handleIssue).toHaveBeenCalledTimes(1)
  reactive(state.iam).codes = ['governance:issue:view']
  reactive(state.iam).snapshot = {}
  await settle()
  pending.resolve({ data: null })
  await a
  expect((w.vm as any).resolutionTarget).toBeNull()
  expect((w.vm as any).busyIds).toEqual([])
  w.unmount()
})
it('辅助表目录挂起不阻断问题主列表与独立统计', async () => {
  const pending = deferred()
  state.api.listSnapshotTables.mockReturnValue(pending.promise)
  const w = page(Issues)
  await settle()
  expect(state.api.listQualityIssues).toHaveBeenCalled()
  expect(state.api.getSnapshotQualityIssueSummary).toHaveBeenCalledWith(8)
  expect((w.vm as any).issues[0].id).toBe(1)
  w.unmount()
  pending.resolve({ data: [] })
})
it('权限loaded=true但error时总览刷新强制重读，不被store短路', async () => {
  ;(state.iam as any).errorMessage = '读权失败'
  const w = page(Overview)
  await settle()
  expect(state.iam.load).toHaveBeenCalledWith(true)
  w.unmount()
  ;(state.iam as any).errorMessage = null
})
