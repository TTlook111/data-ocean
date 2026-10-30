// @vitest-environment happy-dom
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const f = vi.hoisted(() => ({
  auth: { token: 'A', currentUser: { id: 1 } },
  route: { query: { tab: 'roles' } as Record<string, any> },
  router: { replace: vi.fn() },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    systemAdmin: true,
    hasGlobal: vi.fn(),
    canOnDatasource: vi.fn(),
  },
  api: {
    assignIamS1UserRole: vi.fn(),
    bindIamS1Datasources: vi.fn(),
    createIamS1Role: vi.fn(),
    deleteIamS1Role: vi.fn(),
    disableIamS1UserRole: vi.fn(),
    listIamS1Datasources: vi.fn(),
    listIamS1Functions: vi.fn(),
    listIamS1RoleTemplates: vi.fn(),
    listIamS1Roles: vi.fn(),
    listIamS1Subjects: vi.fn(),
    listIamS1UserRoles: vi.fn(),
    removeIamS1UserRole: vi.fn(),
    removeIamS1BindingDatasource: vi.fn(),
    updateIamS1Role: vi.fn(),
  },
}))
vi.mock('../user/UserList.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../user/DepartmentTree.vue', () => ({ default: { template: '<div />' } }))
vi.mock('vue-router', () => ({ useRoute: () => reactive(f.route), useRouter: () => f.router }))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(f.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(f.iam) }))
vi.mock('../../../api/iamS1', () => f.api)
import Panel from './IamS1OrganizationView.vue'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const state = (w: any) => w.vm.$.setupState as any,
  deferred = () => {
    let resolve!: (v: any) => void
    const promise = new Promise<any>((r) => (resolve = r))
    return { resolve, promise }
  }
const role = {
    id: 9,
    roleCode: 'REVIEWER',
    roleName: '审核员',
    enabled: true,
    protectedRole: false,
    builtIn: false,
    functionCodes: ['metadata:view'],
    capabilitySummary: '查看元数据',
    memberCount: 1,
  },
  source = { id: 1, name: '一源', enabled: true }
const binding = {
  userRoleId: 88,
  userId: 8,
  roleId: 9,
  roleCode: 'REVIEWER',
  roleName: '审核员',
  roleEnabled: true,
  bindingEnabled: true,
  protectedRole: false,
  responsibleDatasources: [source],
  capabilitySummary: '查看元数据',
}
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function page() {
  return shallowMount(Panel, { global: { plugins: [ElementPlus] } })
}
async function edit(w: any) {
  await settle()
  await state(w).startEdit(state(w).roles[0])
  state(w).roleForm.reason = '人工功能依据'
}
async function bound(w: any) {
  await settle()
  state(w).activeTab = 'bindings'
  await state(w).loadActive()
  state(w).selectedUserId = 8
  await state(w).loadBindings()
}
beforeEach(() => {
  vi.restoreAllMocks()
  role.protectedRole = false
  role.enabled = true
  Object.values(f.api).forEach((x) => x.mockReset())
  f.auth.token = 'A'
  f.auth.currentUser.id = 1
  f.route.query = { tab: 'roles' }
  f.iam.loaded = true
  f.iam.errorMessage = ''
  f.iam.snapshot = {}
  f.iam.systemAdmin = true
  f.iam.hasGlobal.mockReset().mockReturnValue(true)
  f.iam.canOnDatasource.mockReset().mockReturnValue(true)
  f.router.replace.mockReset().mockResolvedValue({})
  pendingGovernanceWrites.value = []
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
  f.api.listIamS1Roles.mockResolvedValue({ data: [role] })
  f.api.listIamS1RoleTemplates.mockResolvedValue({
    data: [
      {
        code: 'QUERY_USER',
        name: '普通问数',
        description: '问数',
        functionCodes: ['query:use'],
        systemAdminOnly: false,
      },
    ],
  })
  f.api.listIamS1Functions.mockResolvedValue({
    data: [
      {
        code: 'metadata:view',
        name: '查看元数据',
        description: '说明',
        domain: '元数据',
        dependencies: [],
        systemAdminOnly: false,
        active: true,
      },
    ],
  })
  f.api.listIamS1Subjects.mockResolvedValue({
    data: [
      { id: 8, name: '目标用户', subjectType: 'USER' },
      { id: 1, name: '本人', subjectType: 'USER' },
    ],
  })
  f.api.listIamS1Datasources.mockResolvedValue({ data: [source, { id: 2, name: '二源', enabled: true }] })
  f.api.listIamS1UserRoles.mockImplementation(async (userId) => ({ data: [{ ...binding, userId }] }))
  f.api.updateIamS1Role.mockResolvedValue({})
  f.api.createIamS1Role.mockResolvedValue({ data: 10 })
  f.api.deleteIamS1Role.mockResolvedValue({})
  f.api.assignIamS1UserRole.mockResolvedValue({ data: 89 })
  f.api.bindIamS1Datasources.mockResolvedValue({})
  f.api.removeIamS1BindingDatasource.mockResolvedValue({})
  f.api.removeIamS1UserRole.mockResolvedValue({})
  f.api.disableIamS1UserRole.mockResolvedValue({})
})
it('最小role:view列表不请求模板/功能/用户/源目录，不增辅助权限', async () => {
  f.iam.systemAdmin = false
  f.iam.hasGlobal.mockImplementation((c) => c === 'organization:role:view')
  const w = page()
  await settle()
  expect(state(w).roles).toHaveLength(1)
  for (const api of [
    'listIamS1RoleTemplates',
    'listIamS1Functions',
    'listIamS1Subjects',
    'listIamS1Datasources',
  ] as const)
    expect(f.api[api]).not.toHaveBeenCalled()
  expect(state(w).canManageRoles).toBe(false)
  w.unmount()
})
it('最小catalog-only只读固定目录，不读角色或组织目录', async () => {
  f.route.query = { tab: 'catalog' }
  f.iam.systemAdmin = false
  f.iam.hasGlobal.mockImplementation((c) => c === 'organization:permission:view')
  const w = page()
  await settle()
  expect(f.api.listIamS1Functions).toHaveBeenCalledTimes(1)
  expect(f.api.listIamS1Roles).not.toHaveBeenCalled()
  expect(f.api.listIamS1Subjects).not.toHaveBeenCalled()
  w.unmount()
})
it('角色编辑保留原functionCodes和停用状态，功能目录失败不清已有选项', async () => {
  f.api.listIamS1Roles.mockResolvedValue({
    data: [{ ...role, enabled: false, functionCodes: ['metadata:view', 'unknown:retained'] }],
  })
  f.api.listIamS1Functions.mockRejectedValue(new Error('目录403'))
  const w = page()
  await edit(w)
  await state(w).saveRole()
  expect(f.api.updateIamS1Role).toHaveBeenCalledWith(
    9,
    expect.objectContaining({
      status: 0,
      functionCodes: ['metadata:view', 'unknown:retained'],
      reason: '人工功能依据',
    }),
  )
  w.unmount()
})
it('角色保存409保留依据/选择，重复只写一次', async () => {
  const w = page()
  await edit(w)
  const d = deferred()
  f.api.updateIamS1Role.mockReturnValueOnce(d.promise)
  const a = state(w).saveRole()
  await state(w).saveRole()
  expect(f.api.updateIamS1Role).toHaveBeenCalledTimes(1)
  d.resolve(Promise.reject(Object.assign(new Error('409冲突'), { response: { status: 409 } })))
  await a
  expect(state(w).roleForm.reason).toBe('人工功能依据')
  expect(state(w).roleForm.functionCodes).toEqual(['metadata:view'])
  expect(pendingGovernanceWrites.value).toEqual([])
  w.unmount()
})
it('角色搜索刷新不丢已打开精确编辑对象，保存期间新输入保留', async () => {
  const w = page()
  await edit(w)
  f.api.listIamS1Roles.mockResolvedValue({ data: [] })
  await state(w).loadRoles()
  const d = deferred()
  f.api.updateIamS1Role.mockReturnValueOnce(d.promise)
  const a = state(w).saveRole()
  state(w).roleForm.description = '新的人工用途'
  d.resolve({})
  await a
  expect(f.api.updateIamS1Role).toHaveBeenCalledWith(9, expect.any(Object))
  expect(state(w).roleFormVisible).toBe(true)
  expect(state(w).roleForm.description).toBe('新的人工用途')
  w.unmount()
})
it('创建残缺回执留未知角色锁，保护角色不能编辑/删/按名字恢复特权', async () => {
  const w = page()
  await settle()
  await state(w).openRole()
  state(w).roleForm.roleCode = 'NEW_ROLE'
  state(w).roleForm.roleName = '新角色'
  state(w).roleForm.functionCodes = ['metadata:view']
  f.api.createIamS1Role.mockResolvedValue({ data: null })
  await state(w).saveRole()
  expect(pendingGovernanceWrites.value[0]?.state).toBe('unknown')
  await state(w).saveRole()
  expect(f.api.createIamS1Role).toHaveBeenCalledTimes(1)
  state(w).roles[0].protectedRole = true
  await state(w).removeRole(state(w).roles[0])
  expect(f.api.deleteIamS1Role).not.toHaveBeenCalled()
  w.unmount()
})
it('删除角色精确确认，确认期间撤权/换账号零写', async () => {
  const w = page()
  await settle()
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).removeRole(state(w).roles[0])
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('角色 #9'),
    expect.any(String),
    expect.any(Object),
  )
  reactive(f.auth).token = 'B'
  d.resolve('confirm')
  await a
  expect(f.api.deleteIamS1Role).not.toHaveBeenCalled()
  w.unmount()
})
it('后台绑定只给系统管理员写，不能修改本人，即使有全局user:manage', async () => {
  f.iam.systemAdmin = false
  const w = page()
  await bound(w)
  await state(w).assignRole(9)
  expect(f.api.assignIamS1UserRole).not.toHaveBeenCalled()
  w.unmount()
  f.iam.systemAdmin = true
  const own = page()
  await bound(own)
  state(own).selectedUserId = 1
  await state(own).loadBindings()
  await state(own).assignRole(9)
  expect(f.api.assignIamS1UserRole).not.toHaveBeenCalled()
  own.unmount()
})
it('每个绑定使用自己的userRoleId/负责源，不把另一角色源拼入功能', async () => {
  f.api.listIamS1UserRoles.mockResolvedValue({
    data: [
      binding,
      {
        ...binding,
        userRoleId: 99,
        roleId: 10,
        responsibleDatasources: [{ id: 2, name: '二源', enabled: true }],
      },
    ],
  })
  const w = page()
  await bound(w)
  state(w).bindingDatasourceDraft[88] = [2]
  await state(w).saveBindingDatasources(state(w).bindings[0])
  expect(f.api.bindIamS1Datasources).toHaveBeenCalledWith(88, [2], expect.any(String))
  expect(f.api.bindIamS1Datasources).not.toHaveBeenCalledWith(99, expect.anything(), expect.anything())
  w.unmount()
})
it('追加接口不假装替换，单条移除使用精确binding/source已有DELETE', async () => {
  const w = page()
  await bound(w)
  state(w).bindingDatasourceDraft[88] = [2]
  await state(w).saveBindingDatasources(state(w).bindings[0])
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('不移除其他源'),
    expect.any(String),
    expect.any(Object),
  )
  const b = state(w).bindings[0]
  await state(w).removeSource(b, b.responsibleDatasources[0])
  expect(f.api.removeIamS1BindingDatasource).toHaveBeenCalledWith(88, 1, expect.any(String))
  expect(f.api.bindIamS1Datasources).toHaveBeenCalledTimes(1)
  w.unmount()
})
it('绑定禁用/解除与负责源确认期间换用户后不写旧目标', async () => {
  const w = page()
  await bound(w)
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).removeBinding(state(w).bindings[0])
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('用户 #8 / 角色 #9'),
    expect.any(String),
    expect.any(Object),
  )
  state(w).selectedUserId = 1
  d.resolve('confirm')
  await a
  expect(f.api.removeIamS1UserRole).not.toHaveBeenCalled()
  w.unmount()
})
it('绑定读取晚回不混新用户，错属绑定不展示', async () => {
  const w = page()
  await bound(w)
  const d = deferred()
  f.api.listIamS1UserRoles.mockReturnValueOnce(d.promise)
  const a = state(w).loadBindings()
  state(w).selectedUserId = 1
  await state(w).loadBindings()
  d.resolve({ data: [binding] })
  await a
  expect(state(w).bindings[0].userId).toBe(1)
  f.api.listIamS1UserRoles.mockResolvedValue({ data: [binding] })
  await state(w).loadBindings()
  expect(state(w).errors.bindings).toContain('不符')
  expect(state(w).bindings).toEqual([])
  w.unmount()
})
it('源目录失败不阻精确已有绑定读取及单源移除，禁止旧源选项追加', async () => {
  f.api.listIamS1Datasources.mockRejectedValue(new Error('目录503'))
  const w = page()
  await bound(w)
  expect(state(w).bindings).toHaveLength(1)
  state(w).bindingDatasourceDraft[88] = [2]
  await state(w).saveBindingDatasources(state(w).bindings[0])
  expect(f.api.bindIamS1Datasources).not.toHaveBeenCalled()
  const b = state(w).bindings[0]
  await state(w).removeSource(b, b.responsibleDatasources[0])
  expect(f.api.removeIamS1BindingDatasource).toHaveBeenCalledWith(88, 1, expect.any(String))
  w.unmount()
})
it('负责源409保留具体绑定草稿，未知跨挂载阻止重复', async () => {
  const w = page()
  await bound(w)
  state(w).bindingDatasourceDraft[88] = [2]
  state(w).bindingReason[88] = '本绑定依据'
  f.api.bindIamS1Datasources.mockRejectedValueOnce(
    Object.assign(new Error('409原绑定变化'), { response: { status: 409 } }),
  )
  await state(w).saveBindingDatasources(state(w).bindings[0])
  expect(state(w).bindingDatasourceDraft[88]).toEqual([2])
  expect(state(w).bindingReason[88]).toBe('本绑定依据')
  f.api.bindIamS1Datasources.mockRejectedValueOnce(new Error('network unknown'))
  await state(w).saveBindingDatasources(state(w).bindings[0])
  w.unmount()
  const w2 = page()
  await bound(w2)
  state(w2).bindingDatasourceDraft[88] = [2]
  await state(w2).saveBindingDatasources(state(w2).bindings[0])
  expect(f.api.bindIamS1Datasources).toHaveBeenCalledTimes(2)
  w2.unmount()
})
it('受保护/停用角色或绑定不维护负责源，解除停用绑定仍可核对后发已有接口', async () => {
  const w = page()
  await bound(w)
  const b = state(w).bindings[0]
  b.protectedRole = true
  state(w).bindingDatasourceDraft[88] = [2]
  await state(w).saveBindingDatasources(b)
  await state(w).removeSource(b, b.responsibleDatasources[0])
  expect(f.api.bindIamS1Datasources).not.toHaveBeenCalled()
  expect(f.api.removeIamS1BindingDatasource).not.toHaveBeenCalled()
  b.bindingEnabled = false
  await state(w).disableBinding(b)
  expect(f.api.disableIamS1UserRole).not.toHaveBeenCalled()
  await state(w).removeBinding(b)
  expect(f.api.removeIamS1UserRole).toHaveBeenCalledWith(8, 9, expect.any(String))
  w.unmount()
})
it('分配角色显式确认/重复只一次，选择本身不自动写', async () => {
  const w = page()
  await bound(w)
  state(w).assignRoleId = 9
  expect(f.api.assignIamS1UserRole).not.toHaveBeenCalled()
  const d = deferred()
  f.api.assignIamS1UserRole.mockReturnValueOnce(d.promise)
  const a = state(w).assignRole()
  await settle()
  await state(w).assignRole()
  expect(f.api.assignIamS1UserRole).toHaveBeenCalledTimes(1)
  d.resolve({ data: 89 })
  await a
  expect(state(w).assignRoleId).toBeUndefined()
  w.unmount()
})

it('实际复选框分组增选功能保留已有数组，不触发自动保存', async () => {
  f.api.listIamS1Functions.mockResolvedValue({
    data: [
      { code: 'metadata:view', name: '查看元数据', domain: '元数据', dependencies: [], active: true },
      { code: 'query:use', name: '使用问数', domain: '业务', dependencies: [], active: true },
    ],
  })
  const w = mount(Panel, { global: { plugins: [ElementPlus] } })
  await edit(w)
  const input = w
    .findAll('.function-row')
    .find((x) => x.text().includes('使用问数'))!
    .find('input[type=checkbox]')
  await input.setValue(true)
  expect(state(w).roleForm.functionCodes).toEqual(['metadata:view', 'query:use'])
  expect(f.api.updateIamS1Role).not.toHaveBeenCalled()
  w.unmount()
})

it('目录筛选不隐藏角色编辑的其他域，角色搜索不缩窄绑定角色选择', async () => {
  const w = page()
  await edit(w)
  state(w).catalogKeyword = '问数'
  expect(state(w).catalogDomains).toEqual([])
  expect(state(w).editorDomains[0].items[0].code).toBe('metadata:view')
  state(w).roleKeyword = '原搜索'
  await state(w).loadRoles()
  await bound(w)
  expect(f.api.listIamS1Roles).toHaveBeenLastCalledWith()
  expect(state(w).bindingRoles).toHaveLength(1)
  w.unmount()
})
it('已选停用/受限功能可显式移除，未选不可新增且不自动丢码', async () => {
  f.iam.systemAdmin = false
  f.api.listIamS1Roles.mockResolvedValue({
    data: [{ ...role, functionCodes: ['inactive:code', 'restricted:code'] }],
  })
  f.api.listIamS1Functions.mockResolvedValue({
    data: [
      { code: 'inactive:code', name: '旧功能', domain: '旧', active: false, dependencies: [] },
      {
        code: 'restricted:code',
        name: '受限功能',
        domain: '旧',
        active: true,
        systemAdminOnly: true,
        dependencies: [],
      },
    ],
  })
  const w = mount(Panel, { global: { plugins: [ElementPlus] } })
  await edit(w)
  const inputs = w.findAll('.function-row input[type=checkbox]')
  expect(inputs[0]!.attributes('disabled')).toBeUndefined()
  await inputs[0]!.setValue(false)
  expect(state(w).roleForm.functionCodes).toEqual(['restricted:code'])
  expect(inputs[0]!.attributes('disabled')).toBeDefined()
  await inputs[1]!.setValue(false)
  expect(state(w).roleForm.functionCodes).toEqual([])
  expect(inputs[1]!.attributes('disabled')).toBeDefined()
  w.unmount()
})

it('角色状态或保护标记未知不转停用/普通角色，不发保存或删除', async () => {
  f.api.listIamS1Roles.mockResolvedValue({ data: [{ ...role, enabled: undefined }] })
  const w = page()
  await settle()
  await state(w).startEdit(state(w).roles[0])
  expect(state(w).roleFormVisible).toBe(false)
  expect(state(w).writeError).toContain('未确认')
  expect(state(w).roleStatus(state(w).roles[0])).toBe('状态未确认')
  await state(w).removeRole(state(w).roles[0])
  expect(f.api.updateIamS1Role).not.toHaveBeenCalled()
  expect(f.api.deleteIamS1Role).not.toHaveBeenCalled()
  w.unmount()
})

it('绑定用户远程搜索发送keyword，保留当前已核验用户，不被首50条限制', async () => {
  const w = page()
  await bound(w)
  f.api.listIamS1Subjects.mockResolvedValue({ data: [{ id: 88, name: '首批外账号', subjectType: 'USER' }] })
  await state(w).loadUsers('首批外')
  expect(f.api.listIamS1Subjects).toHaveBeenLastCalledWith('ORGANIZATION', undefined, 'USER', '首批外')
  expect(state(w).users.map((u: any) => u.id)).toEqual([8, 88])
  expect(state(w).canMutateUser).toBe(true)
  w.unmount()
})
