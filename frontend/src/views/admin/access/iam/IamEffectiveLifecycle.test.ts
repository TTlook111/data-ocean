// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const f = vi.hoisted(() => ({
  auth: { token: 'A', currentUser: { id: 1 }, user: { userId: 1 } },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    systemAdmin: false,
    queryUse: true,
    datasourceCapabilities: [{ datasourceId: 2, datasourceName: '管理源' }],
    canOnDatasource: vi.fn(),
    hasGlobal: vi.fn(),
  },
  api: {
    listIamS1Columns: vi.fn(),
    listIamS1Datasources: vi.fn(),
    listIamS1QueryResourceColumns: vi.fn(),
    listIamS1QueryResourceDatasources: vi.fn(),
    listIamS1QueryResourceSnapshots: vi.fn(),
    listIamS1QueryResourceTables: vi.fn(),
    listIamS1Snapshots: vi.fn(),
    listIamS1Subjects: vi.fn(),
    listIamS1Tables: vi.fn(),
    previewIamS1EffectivePermission: vi.fn(),
  },
}))
vi.mock('../../../../stores/auth', () => ({ useAuthStore: () => reactive(f.auth) }))
vi.mock('../../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(f.iam) }))
vi.mock('../../../../api/iamS1', () => f.api)
import Panel from './IamS1EffectivePermissionPanel.vue'
const state = (w: any) => w.vm.$.setupState as any
const deferred = () => {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((r) => (resolve = r))
  return { resolve, promise }
}
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function page(props: any = {}) {
  return shallowMount(Panel, { props, global: { plugins: [ElementPlus] } })
}
async function fields(w: any) {
  await settle()
  state(w).tableName = 'orders'
  await state(w).loadColumns()
  state(w).selectedColumns = ['amount']
}
const result = (userId = 1, datasourceId = 1) => ({
  allowed: true,
  userId,
  datasourceId,
  activeMetadataSnapshotId: 11,
  tables: [
    {
      tableName: 'orders',
      allowed: true,
      allowedColumns: ['amount'],
      grantSources: [],
      fieldProtections: [],
      reasons: [],
    },
  ],
})
beforeEach(() => {
  vi.restoreAllMocks()
  Object.values(f.api).forEach((x) => x.mockReset())
  f.auth.token = 'A'
  f.auth.currentUser.id = 1
  f.iam.loaded = true
  f.iam.errorMessage = ''
  f.iam.snapshot = {}
  f.iam.systemAdmin = false
  f.iam.queryUse = true
  f.iam.hasGlobal.mockReset().mockReturnValue(false)
  f.iam.canOnDatasource
    .mockReset()
    .mockImplementation(
      (c, ds) => ds === 2 && ['security:effective:view', 'security:permission:view'].includes(c),
    )
  f.api.listIamS1QueryResourceDatasources.mockResolvedValue({ data: [{ id: 1, name: '本人数据源' }] })
  f.api.listIamS1Datasources.mockResolvedValue({ data: [{ id: 2, name: '管理源' }] })
  for (const name of ['listIamS1Snapshots', 'listIamS1QueryResourceSnapshots'] as const)
    f.api[name].mockImplementation(async (ds) => ({
      data: [{ id: 11, datasourceId: ds, status: 'PUBLISHED' }],
    }))
  for (const name of ['listIamS1Tables', 'listIamS1QueryResourceTables'] as const)
    f.api[name].mockImplementation(async (ds, snap) => ({
      data: [{ datasourceId: ds, metadataSnapshotId: snap, tableName: 'orders' }],
    }))
  for (const name of ['listIamS1Columns', 'listIamS1QueryResourceColumns'] as const)
    f.api[name].mockResolvedValue({ data: [{ columnMetaId: 204, columnName: 'amount', selectable: true }] })
  f.api.listIamS1Subjects.mockResolvedValue({
    data: [
      { subjectType: 'USER', id: 1, name: '本人' },
      { subjectType: 'USER', id: 8, name: '其他用户' },
    ],
  })
  f.api.previewIamS1EffectivePermission.mockResolvedValue({ data: result() })
})
it('本人只走QUERY资源，不合并管理源、不调用他人目录', async () => {
  const w = page()
  await fields(w)
  expect(state(w).datasources.map((x: any) => x.id)).toEqual([1])
  expect(f.api.listIamS1Datasources).not.toHaveBeenCalled()
  expect(f.api.listIamS1Subjects).not.toHaveBeenCalled()
  expect(f.api.listIamS1Snapshots).not.toHaveBeenCalled()
  expect(f.api.listIamS1QueryResourceColumns).toHaveBeenCalledWith(1, 11, 'orders', 'QUERY')
  await state(w).preview()
  expect(f.api.previewIamS1EffectivePermission).toHaveBeenCalledWith({
    userId: 1,
    datasourceId: 1,
    activeMetadataSnapshotId: 11,
    tables: [{ tableName: 'orders', referencedColumns: ['amount'] }],
  })
  w.unmount()
})
it('查看他人只列同绑定effective源，最小角色不依赖组织user:view', async () => {
  f.iam.queryUse = false
  const w = page()
  await fields(w)
  expect(state(w).datasources.map((x: any) => x.id)).toEqual([2])
  expect(f.api.listIamS1QueryResourceDatasources).not.toHaveBeenCalled()
  expect(f.api.listIamS1Datasources).not.toHaveBeenCalled()
  expect(f.api.listIamS1Subjects).toHaveBeenCalledWith('EFFECTIVE', 2, 'USER')
  expect(state(w).users.map((x: any) => x.id)).toEqual([8])
  state(w).targetUserId = 8
  f.api.previewIamS1EffectivePermission.mockResolvedValue({ data: result(8, 2) })
  await state(w).preview()
  expect(state(w).snapshot.userId).toBe(8)
  w.unmount()
})
it('系统管理员本人无业务ALLOW时仍为空QUERY，不回退管理源', async () => {
  f.iam.systemAdmin = true
  f.iam.queryUse = false
  f.api.listIamS1QueryResourceDatasources.mockResolvedValue({ data: [] })
  const w = page()
  await settle()
  expect(state(w).datasources).toEqual([])
  expect(f.api.listIamS1Datasources).not.toHaveBeenCalled()
  expect(f.api.previewIamS1EffectivePermission).not.toHaveBeenCalled()
  w.unmount()
})
it('他人目录缺permission:view明确说明，不自动补权或改走本人QUERY', async () => {
  f.iam.queryUse = false
  f.iam.canOnDatasource.mockImplementation((c, ds) => ds === 2 && c === 'security:effective:view')
  const w = page()
  await settle()
  expect(f.api.listIamS1Subjects).toHaveBeenCalledWith('EFFECTIVE', 2, 'USER')
  expect(f.api.listIamS1Snapshots).not.toHaveBeenCalled()
  expect(f.api.listIamS1QueryResourceSnapshots).not.toHaveBeenCalled()
  expect(state(w).error).toContain('security:permission:view')
  w.unmount()
})
it('用途切换后旧QUERY源响应不能混入管理资源', async () => {
  const d = deferred()
  f.api.listIamS1QueryResourceDatasources.mockReturnValueOnce(d.promise)
  const w = page()
  await settle()
  state(w).mode = 'other'
  await state(w).loadDatasources()
  d.resolve({ data: [{ id: 9, name: '旧QUERY' }] })
  await settle()
  expect(state(w).datasources.map((x: any) => x.id)).toEqual([2])
  expect(state(w).mode).toBe('other')
  w.unmount()
})
it('显式非法/用途无权源不回退其他源或读取子资源', async () => {
  const w = page({ defaultDatasourceId: 2 })
  await settle()
  expect(state(w).datasourceId).toBeUndefined()
  expect(state(w).error).toContain('未选择其他源')
  expect(f.api.listIamS1QueryResourceSnapshots).not.toHaveBeenCalled()
  w.unmount()
})
it('空字段及非当前列禁止预览，未确认目标不能用空表示本人', async () => {
  f.iam.queryUse = false
  const w = page()
  await fields(w)
  await state(w).preview()
  expect(f.api.previewIamS1EffectivePermission).not.toHaveBeenCalled()
  state(w).targetUserId = 8
  state(w).selectedColumns = ['secret']
  await state(w).preview()
  expect(f.api.previewIamS1EffectivePermission).not.toHaveBeenCalled()
  w.unmount()
})
it('预览重复只请求一次，变更所选字段后晚结果失效', async () => {
  const w = page()
  await fields(w)
  const d = deferred()
  f.api.previewIamS1EffectivePermission.mockReturnValueOnce(d.promise)
  const a = state(w).preview()
  await state(w).preview()
  expect(f.api.previewIamS1EffectivePermission).toHaveBeenCalledTimes(1)
  state(w).selectedColumns = []
  d.resolve({ data: result() })
  await a
  expect(state(w).snapshot).toBeNull()
  expect(state(w).previewing).toBe(false)
  w.unmount()
})
it('切换账号或撤权后旧预览不展示，当前新加载不被旧finally清理', async () => {
  const w = page()
  await fields(w)
  const d = deferred()
  f.api.previewIamS1EffectivePermission.mockReturnValueOnce(d.promise)
  const a = state(w).preview()
  reactive(f.auth).token = 'B'
  reactive(f.iam).queryUse = false
  reactive(f.iam).snapshot = { new: true } as any
  await settle()
  d.resolve({ data: result() })
  await a
  expect(state(w).snapshot).toBeNull()
  expect(state(w).mode).toBe('other')
  w.unmount()
})
it('返回错误用户/快照不展示，读取失败与真实空分开', async () => {
  const w = page()
  await fields(w)
  f.api.previewIamS1EffectivePermission.mockResolvedValueOnce({ data: result(99) })
  await state(w).preview()
  expect(state(w).snapshot).toBeNull()
  expect(state(w).previewError).toContain('不符')
  f.api.listIamS1QueryResourceDatasources.mockRejectedValue(new Error('资源403'))
  await state(w).loadDatasources()
  expect(state(w).error).toContain('403')
  w.unmount()
})
it('切换目标立即清理旧解析结论，不显示操作者功能为目标用户职责', async () => {
  f.iam.queryUse = false
  const w = page()
  await fields(w)
  state(w).targetUserId = 8
  f.api.previewIamS1EffectivePermission.mockResolvedValue({ data: result(8, 2) })
  await state(w).preview()
  expect(state(w).snapshot.userId).toBe(8)
  state(w).targetUserId = 99
  await nextTick()
  expect(state(w).snapshot).toBeNull()
  expect(w.text()).not.toContain('后台职责（与业务查询范围分开）')
  w.unmount()
})

it.each(['empty', 'missingArrays', 'wrongSource', 'wrongSnapshot', 'wrongTable'])(
  '残缺或错属回执%s拒绝展示成功',
  async (kind) => {
    const w = page()
    await fields(w)
    const data: any = result()
    if (kind === 'empty') data.tables = []
    if (kind === 'missingArrays') delete data.tables[0].grantSources
    if (kind === 'wrongSource') data.datasourceId = 99
    if (kind === 'wrongSnapshot') data.activeMetadataSnapshotId = 22
    if (kind === 'wrongTable') data.tables[0].tableName = 'other'
    f.api.previewIamS1EffectivePermission.mockResolvedValue({ data })
    await state(w).preview()
    expect(state(w).snapshot).toBeNull()
    expect(state(w).previewError).toContain('不符')
    w.unmount()
  },
)
it('本人ID变化清旧结论并拒绝旧ID晚回，即使token与IAM未变化', async () => {
  const w = page()
  await fields(w)
  await state(w).preview()
  expect(state(w).snapshot.userId).toBe(1)
  const d = deferred()
  f.api.previewIamS1EffectivePermission.mockReturnValueOnce(d.promise)
  const a = state(w).preview()
  reactive(f.auth).currentUser.id = 9
  await nextTick()
  expect(state(w).snapshot).toBeNull()
  d.resolve({ data: result() })
  await a
  expect(state(w).snapshot).toBeNull()
  w.unmount()
  f.auth.currentUser.id = 1
})

it.each([{allowedColumns:[] as string[]}, {allowedColumns:['secret']}])('允许回执列%j必须等于本次冻结核对列', async ({allowedColumns}) => {
  const w = page()
  await fields(w)
  const data = result()
  data.tables[0]!.allowedColumns = allowedColumns
  expect(Array.isArray(data.tables[0]!.allowedColumns)).toBe(true)
  f.api.previewIamS1EffectivePermission.mockResolvedValue({ data })
  await state(w).preview()
  expect(state(w).snapshot).toBeNull()
  expect(state(w).previewError).toContain('不符')
  w.unmount()
})
it('合法拒绝允许空表且不要求虚构修订/计算时间',async()=>{const w=page();await fields(w);f.api.previewIamS1EffectivePermission.mockResolvedValue({data:{allowed:false,reasonCode:'NO_ALLOW_GRANT',userId:1,datasourceId:1,activeMetadataSnapshotId:11,tables:[]}});await state(w).preview();expect(state(w).snapshot.allowed).toBe(false);expect(state(w).snapshot.tables).toEqual([]);expect(state(w).previewError).toBe('');w.unmount()})
