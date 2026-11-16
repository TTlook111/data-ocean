// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const f = vi.hoisted(() => ({
  auth: { token: 'A' },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    systemAdmin: false,
    datasourceCapabilities: [
      { datasourceId: 1, datasourceName: '一源' },
      { datasourceId: 2, datasourceName: '二源' },
    ],
    canOnDatasource: vi.fn(),
  },
  api: {
    listIamS1Datasources: vi.fn(),
    listIamS1Snapshots: vi.fn(),
    listIamS1Tables: vi.fn(),
    listIamS1Columns: vi.fn(),
    listIamS1FieldProtections: vi.fn(),
    saveIamS1FieldProtection: vi.fn(),
    revokeIamS1FieldProtection: vi.fn(),
  },
  candidate: { listMaskCandidates: vi.fn(), confirmMaskCandidate: vi.fn(), rejectMaskCandidate: vi.fn() },
}))
vi.mock('../../../../stores/auth', () => ({ useAuthStore: () => reactive(f.auth) }))
vi.mock('../../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(f.iam) }))
vi.mock('../../../../api/iamS1', () => f.api)
vi.mock('../../../../api/admin/catalog', () => f.candidate)
import Panel from './IamS1FieldProtectionPanel.vue'
import Candidate from './MaskCandidatePanel.vue'
import { pendingGovernanceWrites } from '../../../../composables/governanceWriteTracker'
const column = { columnMetaId: 204, columnName: 'amount', protectionLevel: 'NORMAL', selectable: true },
  protection = {
    id: 71,
    datasourceId: 1,
    metadataSnapshotId: 11,
    tableName: 'orders',
    columnMetaId: 204,
    columnName: 'amount',
    protectionLevel: 'MASKED',
    maskPolicy: 'PHONE',
    status: 'ACTIVE',
  }
const candidate = {
  entityId: 204,
  name: 'phone',
  fqn: 'ds.db.orders.phone',
  pendingMask: { mask_strategy: 'PHONE' },
}
const deferred = () => {
  let resolve!: (v?: any) => void
  const promise = new Promise<any>((r) => (resolve = r))
  return { resolve, promise }
}
const state = (w: any) => w.vm.$.setupState as any
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function page(props: any = { defaultDatasourceId: 1 }) {
  return shallowMount(Panel, { props, global: { plugins: [ElementPlus] } })
}
async function table(w: any) {
  await settle()
  state(w).tableName = 'orders'
  await state(w).loadColumns()
  await settle()
}
beforeEach(() => {
  vi.restoreAllMocks()
  Object.values(f.api).forEach((x) => x.mockReset())
  Object.values(f.candidate).forEach((x) => x.mockReset())
  f.auth.token = 'A'
  f.iam.loaded = true
  f.iam.errorMessage = ''
  f.iam.snapshot = {}
  f.iam.systemAdmin = false
  f.iam.canOnDatasource.mockReset().mockImplementation((_c, ds) => [1, 2].includes(ds))
  pendingGovernanceWrites.value = []
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
  f.api.listIamS1Snapshots.mockImplementation(async (ds) => ({
    data: [{ id: 11, datasourceId: ds, status: 'PUBLISHED' }],
  }))
  f.api.listIamS1Tables.mockImplementation(async (ds, s) => ({
    data: [{ datasourceId: ds, metadataSnapshotId: s, tableName: 'orders' }],
  }))
  f.api.listIamS1Columns.mockResolvedValue({ data: [column] })
  f.api.listIamS1FieldProtections.mockResolvedValue({ data: [protection] })
  f.api.saveIamS1FieldProtection.mockResolvedValue({ data: 72 })
  f.api.revokeIamS1FieldProtection.mockResolvedValue({})
  f.candidate.listMaskCandidates.mockResolvedValue({ data: [candidate] })
  f.candidate.confirmMaskCandidate.mockResolvedValue({})
  f.candidate.rejectMaskCandidate.mockResolvedValue({})
})
it('最小查看角色不依赖组织/公共元数据或管理源，维护入口与函数分开', async () => {
  f.iam.canOnDatasource.mockImplementation((c, ds) => c === 'security:mask:view' && ds === 1)
  const w = page()
  await table(w)
  expect(f.api.listIamS1Datasources).not.toHaveBeenCalled()
  expect(state(w).canManage).toBe(false)
  await state(w).save(column)
  expect(f.api.saveIamS1FieldProtection).not.toHaveBeenCalled()
  w.unmount()
})
it('快照切换同时重读正式保护，源/快照归属不符记录不展示', async () => {
  const w = page()
  await settle()
  expect(state(w).protections).toHaveLength(1)
  state(w).snapshots.push({ id: 12, datasourceId: 1, status: 'PUBLISHED' })
  state(w).snapshotId = 12
  await state(w).changeSnapshot()
  expect(f.api.listIamS1FieldProtections).toHaveBeenLastCalledWith({ datasourceId: 1, snapshotId: 12 })
  expect(state(w).protections).toEqual([])
  w.unmount()
})
it('列晚回不覆盖新源，草稿和确认在切源时清理', async () => {
  const w = page()
  await settle()
  const d = deferred()
  f.api.listIamS1Columns.mockReturnValueOnce(d.promise)
  state(w).tableName = 'orders'
  const job = state(w).loadColumns()
  state(w).datasourceId = 2
  await state(w).loadSnapshots()
  d.resolve({ data: [column] })
  await job
  expect(state(w).columns).toEqual([])
  expect(state(w).drafts).toEqual({})
  w.unmount()
})
it('保存保护冻结精确对象和MASK策略，重复点击只提交一次', async () => {
  const w = page()
  await table(w)
  state(w).drafts[204] = { level: 'MASKED', policy: 'EMAIL', reason: '人工依据' }
  const d = deferred()
  f.api.saveIamS1FieldProtection.mockReturnValueOnce(d.promise)
  const a = state(w).save(state(w).columns[0])
  await settle()
  state(w).drafts[204].level = 'NORMAL'
  await state(w).save(state(w).columns[0])
  expect(f.api.saveIamS1FieldProtection).toHaveBeenCalledTimes(1)
  expect(f.api.saveIamS1FieldProtection).toHaveBeenCalledWith(
    expect.objectContaining({
      datasourceId: 1,
      metadataSnapshotId: 11,
      tableName: 'orders',
      columnMetaId: 204,
      columnName: 'amount',
      protectionLevel: 'MASKED',
      maskPolicy: 'EMAIL',
      reason: '人工依据',
    }),
  )
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('数据源 #1 / 快照 #11 / orders'),
    expect.any(String),
    expect.any(Object),
  )
  d.resolve({ data: 72 })
  await a
  w.unmount()
})
it('确认期间撤权不得提交NORMAL或撤销', async () => {
  const w = page()
  await table(w)
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  state(w).drafts[204].level = 'NORMAL'
  const a = state(w).save(state(w).columns[0])
  expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
  reactive(f.iam).snapshot = { new: true } as any
  f.iam.canOnDatasource.mockReturnValue(false)
  d.resolve('confirm')
  await a
  expect(f.api.saveIamS1FieldProtection).not.toHaveBeenCalled()
  w.unmount()
})
it('409保留策略/依据、释放锁，显式再试可用；不自动改版本', async () => {
  const w = page()
  await table(w)
  state(w).drafts[204].reason = '我的依据'
  f.api.saveIamS1FieldProtection.mockRejectedValueOnce(
    Object.assign(new Error('409冲突'), { response: { status: 409 } }),
  )
  state(w).drafts[204].level = 'NORMAL'
  await state(w).save(state(w).columns[0])
  expect(state(w).drafts[204].reason).toBe('我的依据')
  expect(state(w).writeError).toContain('409')
  expect(pendingGovernanceWrites.value).toEqual([])
  expect(f.api.saveIamS1FieldProtection).toHaveBeenCalledTimes(1)
  w.unmount()
})
it('残缺保存回执视为未知，跨挂载不允许本源重复', async () => {
  const w = page()
  await table(w)
  f.api.saveIamS1FieldProtection.mockResolvedValueOnce({ data: null })
  state(w).drafts[204].level = 'NORMAL'
  await state(w).save(state(w).columns[0])
  w.unmount()
  const next = page()
  await table(next)
  expect(state(next).locked).toBe(true)
  await state(next).save(state(next).columns[0])
  expect(f.api.saveIamS1FieldProtection).toHaveBeenCalledTimes(1)
  next.unmount()
})
it('正式保护读取失败保留失败而非空成功，列未知保护不补正常', async () => {
  f.api.listIamS1FieldProtections.mockRejectedValue(new Error('403辅助禁止'))
  f.api.listIamS1Columns.mockResolvedValue({ data: [{ ...column, protectionLevel: undefined }] })
  const w = page()
  await table(w)
  expect(state(w).protectionError).toContain('403')
  expect(state(w).protectionLabel(undefined)).toBe('未记录')
  expect(state(w).drafts[204].level).toBe('')
  expect(state(w).drafts[204].policy).toBe('')
  await state(w).save(state(w).columns[0])
  expect(f.api.saveIamS1FieldProtection).not.toHaveBeenCalled()
  w.unmount()
})
it('精确撤销需要ACTIVE当前记录，确认包含保护ID/快照/字段', async () => {
  const w = page()
  await settle()
  await state(w).revoke({ ...protection })
  expect(f.api.revokeIamS1FieldProtection).not.toHaveBeenCalled()
  await state(w).revoke(state(w).protections[0])
  expect(f.api.revokeIamS1FieldProtection).toHaveBeenCalledWith(71, expect.any(String))
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('快照 #11 / orders.amount'),
    expect.any(String),
    expect.any(Object),
  )
  w.unmount()
})
it('候选确认期间切源或换账号，旧对象不能提交；新候选不被晚回清理', async () => {
  const w = shallowMount(Candidate, { props: { datasourceId: 1 }, global: { plugins: [ElementPlus] } })
  await settle()
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).confirmCandidate(state(w).candidates[0])
  reactive(f.auth).token = 'B'
  await w.setProps({ datasourceId: 2 })
  d.resolve('confirm')
  await a
  expect(f.candidate.confirmMaskCandidate).not.toHaveBeenCalled()
  expect(state(w).candidates).toHaveLength(1)
  w.unmount()
})
it('候选未知策略不确认，拒绝仅调用候选接口且重复互斥', async () => {
  const w = shallowMount(Candidate, { props: { datasourceId: 1 }, global: { plugins: [ElementPlus] } })
  await settle()
  state(w).candidates[0].pendingMask.mask_strategy = 'UNKNOWN'
  await state(w).confirmCandidate(state(w).candidates[0])
  expect(f.candidate.confirmMaskCandidate).not.toHaveBeenCalled()
  const d = deferred()
  f.candidate.rejectMaskCandidate.mockReturnValueOnce(d.promise)
  const a = state(w).rejectCandidate(state(w).candidates[0])
  await settle()
  await state(w).rejectCandidate(state(w).candidates[0])
  expect(f.candidate.rejectMaskCandidate).toHaveBeenCalledTimes(1)
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('字段实体 #204'),
    expect.any(String),
    expect.any(Object),
  )
  d.resolve({})
  await a
  w.unmount()
})
it('候选过滤与本地分页明确已返回数据，401无权不伪装空', async () => {
  f.candidate.listMaskCandidates.mockResolvedValue({
    data: Array.from({ length: 23 }, (_, i) => ({ ...candidate, entityId: i, name: 'phone' + i })),
  })
  const w = shallowMount(Candidate, { props: { datasourceId: 1 }, global: { plugins: [ElementPlus] } })
  await settle()
  expect(state(w).shown).toHaveLength(10)
  state(w).page = 3
  expect(state(w).shown).toHaveLength(3)
  state(w).keyword = 'phone22'
  await settle()
  expect(state(w).shown).toHaveLength(1)
  f.candidate.listMaskCandidates.mockRejectedValue(new Error('401权限失效'))
  await state(w).fetchCandidates()
  expect(state(w).error).toContain('401')
  w.unmount()
})

it('目录403不阻断源级正式保护；真实最小角色不调用无权目录', async () => {
  f.iam.canOnDatasource.mockImplementation((c, ds) => c.startsWith('security:mask:') && ds === 1)
  const w = page()
  await settle()
  expect(f.api.listIamS1Snapshots).not.toHaveBeenCalled()
  expect(f.api.listIamS1FieldProtections).toHaveBeenCalledWith({ datasourceId: 1, snapshotId: undefined })
  expect(state(w).protections).toHaveLength(1)
  expect(state(w).error).toContain('security:permission:view')
  await state(w).revoke(state(w).protections[0])
  expect(f.api.revokeIamS1FieldProtection).toHaveBeenCalledWith(71, expect.any(String))
  w.unmount()
})
it('正式保护迟回不会给MASKED草稿默认PHONE，候选变更显式重读当前列事实', async () => {
  const w = page()
  await settle()
  const d = deferred()
  f.api.listIamS1FieldProtections.mockReturnValueOnce(d.promise)
  const job = state(w).loadProtections()
  f.api.listIamS1Columns.mockResolvedValue({ data: [{ ...column, protectionLevel: 'MASKED' }] })
  await table(w)
  expect(state(w).drafts[204]).toEqual({ level: '', policy: '', reason: '' })
  d.resolve({ data: [protection] })
  await job
  expect(state(w).drafts[204].policy).toBe('')
  f.api.listIamS1Columns.mockClear()
  await state(w).refreshFacts()
  expect(f.api.listIamS1Columns).toHaveBeenCalledWith(1, 11, 'orders')
  expect(state(w).columns[0].protectionLevel).toBe('MASKED')
  w.unmount()
})

it('撤销确认等待时切源，原正式记录不得撤销', async () => {
  const w = page()
  await settle()
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).revoke(state(w).protections[0])
  expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
  state(w).datasourceId = 2
  await state(w).loadSnapshots()
  d.resolve('confirm')
  await a
  expect(f.api.revokeIamS1FieldProtection).not.toHaveBeenCalled()
  w.unmount()
})

it('最小mask角色切源重读正式记录，不让目录辅助权卡主列表', async () => {
  f.iam.canOnDatasource.mockImplementation((c, ds) => c.startsWith('security:mask:') && [1, 2].includes(ds))
  f.api.listIamS1FieldProtections.mockImplementation(async ({ datasourceId }) => ({
    data: [{ ...protection, datasourceId }],
  }))
  const w = page()
  await settle()
  state(w).datasourceId = 2
  await state(w).loadSource()
  expect(f.api.listIamS1FieldProtections).toHaveBeenLastCalledWith({ datasourceId: 2, snapshotId: undefined })
  expect(state(w).protections[0].datasourceId).toBe(2)
  expect(f.api.listIamS1Snapshots).not.toHaveBeenCalled()
  w.unmount()
})
