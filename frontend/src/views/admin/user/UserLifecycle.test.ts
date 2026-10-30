// @vitest-environment happy-dom
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { createRequire } from 'node:module'
// Use the installed CJS component build for real form validation in Vitest; native ESM imports of
// async-validator otherwise yield a namespace instead of its constructor. No validator is stubbed.
const FormRuntime = createRequire(import.meta.url)('element-plus')
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { afterEach, beforeEach, it, expect, vi } from 'vitest'
const f = vi.hoisted(() => ({
  auth: { token: 'A', currentUser: { id: 1 } },
  iam: { loaded: true, errorMessage: '', snapshot: {}, hasGlobal: vi.fn(), canOnDatasource: vi.fn() },
  api: {
    createUser: vi.fn(),
    deleteUser: vi.fn(),
    downloadUserImportTemplate: vi.fn(),
    exportUsers: vi.fn(),
    getUser: vi.fn(),
    importUsers: vi.fn(),
    listDepartments: vi.fn(),
    listUsers: vi.fn(),
    resetUserPassword: vi.fn(),
    updateUser: vi.fn(),
    updateUserStatus: vi.fn(),
  },
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(f.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(f.iam) }))
vi.mock('../../../api/admin/user', () => f.api)
import Panel from './UserList.vue'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const state = (w: any) => w.vm.$.setupState as any,
  deferred = () => {
    let resolve!: (v: any) => void
    const promise = new Promise<any>((r) => (resolve = r))
    return { resolve, promise }
  }
const row = {
  id: 8,
  username: 'analyst',
  realName: '分析员',
  departmentId: 8,
  departmentName: '销售',
  status: 1,
  roleIds: [],
  roleNames: [],
  roleCodes: [],
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
  state(w).openEdit(state(w).users[0])
  state(w).formRef = { validate: vi.fn().mockResolvedValue(true), clearValidate: vi.fn() }
  state(w).form.realName = '新的分析员'
}
beforeEach(() => {
  vi.restoreAllMocks()
  Object.values(f.api).forEach((x) => x.mockReset())
  f.auth.token = 'A'
  f.auth.currentUser.id = 1
  f.iam.snapshot = {}
  f.iam.loaded = true
  f.iam.errorMessage = ''
  f.iam.hasGlobal.mockReset().mockReturnValue(true)
  pendingGovernanceWrites.value = []
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
  f.api.listUsers.mockResolvedValue({ data: { records: [{ ...row }], total: 1 } })
  f.api.listDepartments.mockResolvedValue({
    data: [
      { id: 8, deptName: '销售', deptCode: 'SALES', children: [] },
      { id: 9, deptName: '经营', deptCode: 'OPS', children: [] },
    ],
  })
  f.api.getUser.mockImplementation(async (id) => ({ data: { ...row, id } }))
  f.api.updateUser.mockResolvedValue({ data: null })
  f.api.createUser.mockResolvedValue({ data: { id: 9 } })
  f.api.deleteUser.mockResolvedValue({})
  f.api.updateUserStatus.mockResolvedValue({})
  f.api.resetUserPassword.mockResolvedValue({ data: { tempPassword: 'SyntheticOnly22' } })
  f.api.importUsers.mockResolvedValue({ data: { success: 1, failed: 1, errors: ['第3行：合成字段错误'] } })
  f.api.exportUsers.mockResolvedValue(new Blob(['synthetic']))
  f.api.downloadUserImportTemplate.mockResolvedValue(new Blob(['synthetic']))
  vi.stubGlobal(
    'URL',
    Object.assign(URL, {
      createObjectURL: vi.fn().mockReturnValue('blob:synthetic'),
      revokeObjectURL: vi.fn(),
    }),
  )
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})
it('最小user:view不读部门/角色辅助，不开放维护或导出', async () => {
  f.iam.hasGlobal.mockImplementation((c) => c === 'organization:user:view')
  const w = page()
  await settle()
  expect(f.api.listUsers).toHaveBeenCalledTimes(1)
  expect(f.api.listDepartments).not.toHaveBeenCalled()
  expect(state(w).departmentText(state(w).users[0])).toBe('销售')
  expect(state(w).canManageUsers).toBe(false)
  expect(state(w).canExportUsers).toBe(false)
  w.unmount()
})
it('部门辅助错误不阻列表、详情或保留原部门的资料维护', async () => {
  f.api.listDepartments.mockRejectedValue(new Error('部门403'))
  const w = page()
  await edit(w)
  expect(state(w).users).toHaveLength(1)
  expect(state(w).optionError).toContain('403')
  await state(w).saveUser()
  expect(f.api.updateUser).toHaveBeenCalledWith(
    8,
    expect.objectContaining({ departmentId: 8, realName: '新的分析员' }),
  )
  expect(f.api.updateUser.mock.calls[0]![1]).not.toHaveProperty('roleIds')
  w.unmount()
})
afterEach(() => vi.useRealTimers())
it('部门辅助挂起时过滤照常发请求，晚列表不覆盖新筛选', async () => {
  vi.useFakeTimers()
  const d = deferred()
  f.api.listDepartments.mockReturnValueOnce(d.promise)
  const old = deferred()
  f.api.listUsers.mockReturnValueOnce(old.promise)
  const w = page()
  await settle()
  state(w).query.username = 'new'
  await vi.advanceTimersByTimeAsync(320)
  await settle()
  expect(f.api.listUsers).toHaveBeenCalledTimes(2)
  old.resolve({ data: { records: [{ ...row, id: 99 }], total: 1 } })
  await settle()
  expect(state(w).users[0].id).toBe(8)
  expect(f.api.listUsers).toHaveBeenLastCalledWith(expect.objectContaining({ username: 'new' }))
  d.resolve({ data: [] })
  await settle()
  w.unmount()
})
it('详情关闭/换目标/换账号后晚回不重开，不展示错ID', async () => {
  const w = page()
  await settle()
  const d = deferred()
  f.api.getUser.mockReturnValueOnce(d.promise)
  const a = state(w).openDetail(state(w).users[0])
  state(w).closeDetail()
  d.resolve({ data: row })
  await a
  expect(state(w).detail).toBeNull()
  expect(state(w).detailVisible).toBe(false)
  f.api.getUser.mockResolvedValue({ data: { ...row, id: 99 } })
  await state(w).openDetail(state(w).users[0])
  expect(state(w).detailError).toContain('不符')
  w.unmount()
})
it('保存校验等待期间换账号不发写，重复校验只一次', async () => {
  const w = page()
  await edit(w)
  const d = deferred(),
    validate = vi.fn().mockReturnValue(d.promise)
  state(w).formRef = { validate }
  const a = state(w).saveUser()
  await state(w).saveUser()
  expect(validate).toHaveBeenCalledTimes(1)
  reactive(f.auth).token = 'B'
  d.resolve(true)
  await a
  expect(f.api.updateUser).not.toHaveBeenCalled()
  w.unmount()
})
it('用户409保姓名/主部门草稿，不清旧角色数组来伪装授权变更', async () => {
  const w = page()
  await edit(w)
  f.api.updateUser.mockRejectedValueOnce(
    Object.assign(new Error('409资料冲突'), { response: { status: 409 } }),
  )
  await state(w).saveUser()
  expect(state(w).form.realName).toBe('新的分析员')
  expect(state(w).form.departmentId).toBe(8)
  expect(state(w).dialogVisible).toBe(true)
  expect(f.api.updateUser).toHaveBeenCalledTimes(1)
  expect(pendingGovernanceWrites.value).toEqual([])
  w.unmount()
})
it('变更主部门精确确认，确认期间撤权零写；辅助未知不新选部门', async () => {
  const w = page()
  await edit(w)
  state(w).form.departmentId = 9
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).saveUser()
  await settle()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('用户 #8 analyst：主部门从 #8 改为 #9'),
    expect.any(String),
    expect.any(Object),
  )
  reactive(f.iam).snapshot = { new: true } as any
  f.iam.hasGlobal.mockReturnValue(false)
  d.resolve('confirm')
  await a
  expect(f.api.updateUser).not.toHaveBeenCalled()
  w.unmount()
})
it('新建允许未分配部门，与后端DTO相符，且不写角色/数据权', async () => {
  f.iam.hasGlobal.mockImplementation((c) => c !== 'organization:department:view')
  const w = page()
  await settle()
  state(w).openCreate()
  state(w).formRef = { validate: vi.fn().mockResolvedValue(true) }
  Object.assign(state(w).form, { username: 'new_account', password: 'SyntheticOnly22', realName: '新账号' })
  await state(w).saveUser()
  expect(f.api.createUser).toHaveBeenCalledWith(
    expect.objectContaining({ username: 'new_account', departmentId: undefined }),
  )
  expect(f.api.createUser.mock.calls[0]![0]).not.toHaveProperty('roleIds')
  w.unmount()
})
it('账号状态/删除确认精确对象，换筛选导致原行失效时零写', async () => {
  const w = page()
  await settle()
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).changeStatus(state(w).users[0], 2)
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('用户 #8 analyst'),
    expect.any(String),
    expect.any(Object),
  )
  state(w).query.username = 'other'
  d.resolve('confirm')
  await a
  expect(f.api.updateUserStatus).not.toHaveBeenCalled()
  w.unmount()
})
it('未知保存跨挂载锁精确账号，禁止重复但不会冒称真实结果失败', async () => {
  const w = page()
  await edit(w)
  f.api.updateUser.mockRejectedValueOnce(new Error('unknown network'))
  await state(w).saveUser()
  w.unmount()
  const w2 = page()
  await edit(w2)
  await state(w2).saveUser()
  expect(f.api.updateUser).toHaveBeenCalledTimes(1)
  expect(state(w2).locked(8)).toBe(true)
  w2.unmount()
})
it('临时密码不自动复制，关闭即清除且不能从旧回执重开', async () => {
  const w = page()
  await settle()
  await state(w).startResetPassword(state(w).users[0])
  expect(state(w).tempPassword).toBe('SyntheticOnly22')
  expect(state(w).passwordVisible).toBe(true)
  expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
  await state(w).copyPassword()
  expect(navigator.clipboard.writeText).toHaveBeenCalledWith('SyntheticOnly22')
  state(w).clearPassword()
  expect(state(w).tempPassword).toBe('')
  expect(state(w).passwordVisible).toBe(false)
  w.unmount()
})
it('密码重置回包换账号后不展示，重复点击只一次', async () => {
  const w = page()
  await settle()
  const d = deferred()
  f.api.resetUserPassword.mockReturnValueOnce(d.promise)
  const a = state(w).startResetPassword(state(w).users[0])
  await settle()
  await state(w).startResetPassword(state(w).users[0])
  expect(f.api.resetUserPassword).toHaveBeenCalledTimes(1)
  reactive(f.auth).token = 'B'
  d.resolve({ data: { tempPassword: 'OldSynthetic22' } })
  await a
  expect(state(w).tempPassword).toBe('')
  expect(state(w).passwordVisible).toBe(false)
  w.unmount()
})
it('重置残缺回执保未知锁，不显示假成功密码', async () => {
  const w = page()
  await settle()
  f.api.resetUserPassword.mockResolvedValueOnce({ data: {} })
  await state(w).startResetPassword(state(w).users[0])
  expect(state(w).passwordVisible).toBe(false)
  expect(state(w).tempPassword).toBe('')
  expect(state(w).locked(8)).toBe(true)
  w.unmount()
})
it('导入部分成功/真实0完整回执可见，重复不补导，失败详情不console', async () => {
  const warn = vi.spyOn(console, 'warn')
  const w = page()
  await settle()
  f.api.importUsers.mockResolvedValue({ data: { success: 0, failed: 1, errors: ['第2行：合成错误'] } })
  const input = { files: [new File(['synthetic'], 'synthetic.csv')], value: 'x' }
  await state(w).handleImportFile({ target: input })
  expect(state(w).importReport).toEqual({
    filename: 'synthetic.csv',
    success: 0,
    failed: 1,
    errors: ['第2行：合成错误'],
  })
  expect(f.api.importUsers).toHaveBeenCalledTimes(1)
  expect(warn).not.toHaveBeenCalledWith('User import errors:', expect.anything())
  w.unmount()
})
it('导入未知锁阻止盲目重导/账号写入，未增批量授权', async () => {
  const w = page()
  await settle()
  f.api.importUsers.mockResolvedValueOnce({ data: { success: 1 } })
  const input = { files: [new File(['synthetic'], 'synthetic.csv')], value: 'x' }
  await state(w).handleImportFile({ target: input })
  expect(state(w).importLocked).toBe(true)
  await state(w).handleImportFile({ target: input })
  await state(w).removeUser(state(w).users[0])
  expect(f.api.importUsers).toHaveBeenCalledTimes(1)
  expect(f.api.deleteUser).not.toHaveBeenCalled()
  w.unmount()
})
it('导出筛选冻结，回包期间换筛选不下载旧范围；模板只需查看能力', async () => {
  const w = page()
  await settle()
  const d = deferred()
  f.api.exportUsers.mockReturnValueOnce(d.promise)
  const a = state(w).exportCurrentUsers()
  state(w).query.username = 'changed'
  d.resolve(new Blob(['old']))
  await a
  expect(URL.createObjectURL).not.toHaveBeenCalled()
  expect(state(w).writeError).toContain('旧文件未下载')
  f.iam.hasGlobal.mockImplementation((c) => c === 'organization:user:view')
  await state(w).downloadTemplate()
  expect(f.api.downloadUserImportTemplate).toHaveBeenCalledTimes(1)
  w.unmount()
})

it('本人重置不发失效旧token的管理接口，保留已有个人修改密码入口', async () => {
  f.api.listUsers.mockResolvedValue({ data: { records: [{ ...row, id: 1 }], total: 1 } })
  const w = page()
  await settle()
  await state(w).startResetPassword(state(w).users[0])
  expect(f.api.resetUserPassword).not.toHaveBeenCalled()
  expect(state(w).writeError).toContain('修改本人密码')
  expect(w.find('a[href="/change-password"]').exists()).toBe(true)
  w.unmount()
})

it('真实表单按DTO校验用户名4–50和姓名2–50，不额外限制联系号码格式', async () => {
  const w = mount(Panel, { attachTo: document.body, global: { plugins: [FormRuntime] } })
  await settle()
  state(w).openCreate()
  await settle()
  Object.assign(state(w).form, {
    username: 'abc',
    password: 'SyntheticOnly22',
    realName: '姓名'.repeat(25),
    phone: '+1 555 0123',
  })
  await settle()
  expect(state(w).formRef.fields.map((x: any) => x.prop)).toContain('username')
  expect(
    await state(w)
      .formRef.validate()
      .catch(() => false),
  ).toBe(false)
  state(w).form.username = 'abcd'
  await settle()
  expect(await state(w).formRef.validate()).toBe(true)
  state(w).form.realName = '长'.repeat(51)
  await settle()
  expect(
    await state(w)
      .formRef.validate()
      .catch(() => false),
  ).toBe(false)
  w.unmount()
})
it.each([
  { success: 1, failed: 2, errors: ['一条'] },
  { success: 1, failed: 1, errors: [{}] },
])('导入不完整逐项回执%j保未知而非确认部分成功', async (receipt) => {
  const w = page()
  await settle()
  f.api.importUsers.mockResolvedValue({ data: receipt })
  await state(w).handleImportFile({ target: { files: [new File(['synthetic'], 'sample.csv')], value: 'x' } })
  expect(state(w).importReport).toBeUndefined()
  expect(state(w).importLocked).toBe(true)
  w.unmount()
})
it('已有部门清空持久化未验证，前端不把null更新当作已完成，保留其他草稿', async () => {
  const w = page()
  await edit(w)
  state(w).form.departmentId = undefined
  await state(w).saveUser()
  expect(f.api.updateUser).not.toHaveBeenCalled()
  expect(state(w).writeError).toContain('暂不支持直接清空')
  expect(state(w).form.realName).toBe('新的分析员')
  w.unmount()
})

it('保存前重读发现其他会话改部门则阻止旧资料回写，保留人工草稿', async () => {
  const w = page()
  await edit(w)
  f.api.getUser.mockResolvedValue({ data: { ...row, departmentId: 9 } })
  await state(w).saveUser()
  expect(f.api.getUser).toHaveBeenCalledWith(8)
  expect(f.api.updateUser).not.toHaveBeenCalled()
  expect(state(w).form.realName).toBe('新的分析员')
  expect(state(w).form.departmentId).toBe(8)
  expect(state(w).writeError).toContain('其他会话变化')
  w.unmount()
})
it('编辑已有短用户名不重验新建格式，不发送只读username/password', async () => {
  f.api.listUsers.mockResolvedValue({ data: { records: [{ ...row, username: 'ab' }], total: 1 } })
  const w = mount(Panel, { global: { plugins: [FormRuntime] } })
  await settle()
  state(w).openEdit(state(w).users[0])
  await settle()
  expect(await state(w).formRef.validate()).toBe(true)
  w.unmount()
})
