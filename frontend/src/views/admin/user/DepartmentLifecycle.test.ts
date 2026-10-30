// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const f = vi.hoisted(() => ({
  auth: { token: 'A', currentUser: { id: 1 } },
  iam: { loaded: true, errorMessage: '', snapshot: {}, hasGlobal: vi.fn(), canOnDatasource: vi.fn() },
  api: {
    createDepartment: vi.fn(),
    deleteDepartment: vi.fn(),
    listDepartments: vi.fn(),
    listUsers: vi.fn(),
    updateDepartment: vi.fn(),
  },
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(f.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(f.iam) }))
vi.mock('../../../api/admin/user', () => f.api)
import Panel from './DepartmentTree.vue'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
const state = (w: any) => w.vm.$.setupState as any,
  deferred = () => {
    let resolve!: (v: any) => void
    const promise = new Promise<any>((r) => (resolve = r))
    return { resolve, promise }
  }
type FixtureNode = {
  id: number
  parentId?: number
  deptName: string
  deptCode: string
  sortOrder: number
  children: FixtureNode[]
}
const tree = (): FixtureNode[] => [
  {
    id: 8,
    deptName: '销售',
    deptCode: 'SALES',
    sortOrder: 1,
    children: [{ id: 9, parentId: 8, deptName: '分析', deptCode: 'ANALYSIS', sortOrder: 2, children: [] }],
  },
  { id: 10, deptName: '研发', deptCode: 'RND', sortOrder: 0, children: [] },
]
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function page() {
  return shallowMount(Panel, { global: { plugins: [ElementPlus] } })
}
async function edit(w: any, id = 9) {
  await settle()
  state(w).openEdit(state(w).nodeMap.get(id))
  state(w).form.deptName = '人工分析部'
}
beforeEach(() => {
  vi.restoreAllMocks()
  Object.values(f.api).forEach((x) => x.mockReset())
  f.auth.token = 'A'
  f.auth.currentUser.id = 1
  f.iam.loaded = true
  f.iam.errorMessage = ''
  f.iam.snapshot = {}
  f.iam.hasGlobal.mockReset().mockReturnValue(true)
  pendingGovernanceWrites.value = []
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
  f.api.listDepartments.mockImplementation(async () => ({ data: tree() }))
  f.api.listUsers.mockResolvedValue({ data: { records: [], total: 0 } })
  f.api.updateDepartment.mockResolvedValue({ data: null })
  f.api.createDepartment.mockResolvedValue({ data: { id: 11 } })
  f.api.deleteDepartment.mockResolvedValue({ data: null })
})
it('最小department:view不请求额外user目录，直属人数保持未知而非0', async () => {
  f.iam.hasGlobal.mockImplementation((c) => c === 'organization:department:view')
  const w = page()
  await settle()
  expect(state(w).treeVerified).toBe(true)
  expect(state(w).nodeMap.size).toBe(3)
  expect(f.api.listUsers).not.toHaveBeenCalled()
  expect(state(w).memberCounts).toEqual({})
  expect(state(w).canManageDepartments).toBe(false)
  w.unmount()
})
it('真实0、读取失败和未返回父节点分别呈现，搜索保留父路径', async () => {
  f.api.listUsers.mockImplementation(async ({ departmentId }) => {
    if (departmentId === 8) throw new Error('人数403')
    return { data: { records: [], total: 0 } }
  })
  const w = page()
  await settle()
  expect(state(w).memberCounts[9]).toBe(0)
  expect(state(w).memberCounts[8]).toBeUndefined()
  expect(state(w).memberErrors[8]).toContain('403')
  state(w).keyword = 'ANALYSIS'
  expect(state(w).visibleTree[0].id).toBe(8)
  expect(state(w).visibleTree[0].children[0].id).toBe(9)
  expect(state(w).parentLabel({ id: 99, parentId: 88 })).toContain('本次启用树未返回')
  w.unmount()
})
it('人数最多6路并发，旧账号回包不混入新会话', async () => {
  f.api.listDepartments.mockResolvedValue({
    data: Array.from({ length: 12 }, (_, i) => ({
      id: i + 1,
      deptName: '部门' + i,
      deptCode: 'D' + i,
      sortOrder: i,
      children: [],
    })),
  })
  const d = deferred()
  let active = 0,
    max = 0
  f.api.listUsers.mockImplementation(async () => {
    active++
    max = Math.max(max, active)
    await d.promise
    active--
    return { data: { records: [], total: 7 } }
  })
  const w = page()
  await settle()
  expect(max).toBe(6)
  reactive(f.iam).loaded = false
  f.iam.hasGlobal.mockImplementation((c) => c === 'organization:department:view')
  reactive(f.auth).token = 'B'
  reactive(f.iam).loaded = true
  d.resolve({})
  await settle()
  expect(state(w).memberCounts).toEqual({})
  w.unmount()
})
it('树读取错误不把总数补0，重复ID/非法结构不渲染成正常树', async () => {
  f.api.listDepartments.mockRejectedValue(new Error('树503'))
  const w = page()
  await settle()
  expect(state(w).treeVerified).toBe(false)
  expect(state(w).errorMessage).toContain('503')
  f.api.listDepartments.mockResolvedValue({ data: [...tree(), tree()[0]] })
  await state(w).fetchDepartments()
  expect(state(w).treeVerified).toBe(false)
  expect(state(w).errorMessage).toContain('重复')
  w.unmount()
})
it('新建/编辑以真实原节点为目标，筛选克隆只用于展示不冒认写归属', async () => {
  const w = page()
  await settle()
  state(w).keyword = '分析'
  const clone = state(w).visibleTree[0].children[0]
  state(w).openEdit(clone)
  expect(state(w).dialogVisible).toBe(false)
  state(w).choose(clone)
  expect(state(w).selected.id).toBe(9)
  state(w).openEdit(state(w).selected)
  expect(state(w).editingId).toBe(9)
  w.unmount()
})
it('编辑保存前重读精确部门，冻结原父/排序，双击一次', async () => {
  const w = page()
  await edit(w)
  const d = deferred()
  f.api.updateDepartment.mockReturnValueOnce(d.promise)
  const a = state(w).saveDepartment()
  await settle()
  await state(w).saveDepartment()
  expect(f.api.updateDepartment).toHaveBeenCalledTimes(1)
  expect(f.api.updateDepartment).toHaveBeenCalledWith(9, {
    parentId: 8,
    deptName: '人工分析部',
    deptCode: 'ANALYSIS',
    sortOrder: 2,
  })
  d.resolve({})
  await a
  w.unmount()
})
it('409保留名称/父/排序草稿，写前读取失败不创建未知写锁', async () => {
  const w = page()
  await edit(w)
  f.api.updateDepartment.mockRejectedValueOnce(
    Object.assign(new Error('409冲突'), { response: { status: 409 } }),
  )
  await state(w).saveDepartment()
  expect(state(w).form.deptName).toBe('人工分析部')
  expect(state(w).form.parentId).toBe(8)
  expect(state(w).dialogVisible).toBe(true)
  expect(pendingGovernanceWrites.value).toEqual([])
  f.api.listDepartments.mockRejectedValueOnce(new Error('预读503'))
  await state(w).saveDepartment()
  expect(f.api.updateDepartment).toHaveBeenCalledTimes(1)
  expect(pendingGovernanceWrites.value).toEqual([])
  w.unmount()
})
it('预读发现名称/父变化或目标不在启用树，不自动重新启用/换原稿', async () => {
  const w = page()
  await edit(w)
  f.api.listDepartments.mockResolvedValueOnce({ data: tree().filter((n) => n.id !== 8) })
  await state(w).saveDepartment()
  expect(f.api.updateDepartment).not.toHaveBeenCalled()
  expect(state(w).writeError).toContain('其他会话变化')
  expect(state(w).form.deptName).toBe('人工分析部')
  w.unmount()
})
it('自己/下级不能选作父，已有父清空暂不提交，未知排序不自动补0', async () => {
  const w = page()
  await edit(w, 8)
  state(w).form.parentId = 9
  await state(w).saveDepartment()
  expect(f.api.updateDepartment).not.toHaveBeenCalled()
  state(w).closeEditor()
  await edit(w, 9)
  state(w).form.parentId = undefined
  await state(w).saveDepartment()
  expect(state(w).writeError).toContain('暂不直接清空')
  expect(f.api.updateDepartment).not.toHaveBeenCalled()
  state(w).form.parentId = 8
  state(w).form.sortOrder = undefined
  await state(w).saveDepartment()
  expect(state(w).writeError).toContain('明确32位整数')
  w.unmount()
})
it('真实排序0保存，创建顶层可未指定父且须正部门ID回执', async () => {
  const w = page()
  await settle()
  state(w).openCreate()
  Object.assign(state(w).form, { deptName: '新部门', deptCode: 'NEW', sortOrder: 0 })
  await state(w).saveDepartment()
  expect(f.api.createDepartment).toHaveBeenCalledWith({
    parentId: undefined,
    deptName: '新部门',
    deptCode: 'NEW',
    sortOrder: 0,
  })
  expect(pendingGovernanceWrites.value).toEqual([])
  w.unmount()
})
it('调整上级明确原/新父和含下级主体匹配，确认期间换账号零写', async () => {
  const w = page()
  await edit(w)
  state(w).form.parentId = 10
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).saveDepartment()
  await settle()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('部门 #9 分析（ANALYSIS）：父部门从 #8 改为 #10'),
    expect.any(String),
    expect.any(Object),
  )
  reactive(f.auth).token = 'B'
  d.resolve('confirm')
  await a
  expect(f.api.updateDepartment).not.toHaveBeenCalled()
  w.unmount()
})
it('非空部门不能删除，不声明级联删除；成员未知仍由Java最终核对', async () => {
  const w = page()
  await settle()
  await state(w).removeDepartment(state(w).nodeMap.get(8))
  expect(f.api.deleteDepartment).not.toHaveBeenCalled()
  expect(ElMessageBox.confirm).not.toHaveBeenCalled()
  expect(state(w).writeError).toContain('不会级联')
  w.unmount()
})
it('空部门删除精确ID/父/编码确认，预读发现新增下级后零写', async () => {
  const w = page()
  await settle()
  const changed = tree()
  changed[0]!.children[0]!.children = [
    { id: 11, parentId: 9, deptName: '新下级', deptCode: 'NEW', sortOrder: 0, children: [] } as any,
  ]
  f.api.listDepartments.mockResolvedValueOnce({ data: changed })
  await state(w).removeDepartment(state(w).nodeMap.get(9))
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(
    expect.stringContaining('只删除部门 #9 分析（ANALYSIS）'),
    expect.any(String),
    expect.any(Object),
  )
  expect(f.api.deleteDepartment).not.toHaveBeenCalled()
  expect(state(w).writeError).toContain('出现下级')
  w.unmount()
})
it('删除确认期间切选部门或撤权，不写原对象', async () => {
  const w = page()
  await settle()
  state(w).selectedId = 9
  const d = deferred()
  vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(d.promise)
  const a = state(w).removeDepartment(state(w).nodeMap.get(9))
  state(w).selectedId = 10
  d.resolve('confirm')
  await a
  expect(f.api.deleteDepartment).not.toHaveBeenCalled()
  w.unmount()
})
it('创建残缺回执跨挂载留树锁，不能补造新部门或删除', async () => {
  const w = page()
  await settle()
  state(w).openCreate()
  Object.assign(state(w).form, { deptName: '新部', deptCode: 'NEW' })
  f.api.createDepartment.mockResolvedValueOnce({ data: {} })
  await state(w).saveDepartment()
  expect(state(w).locked).toBe(true)
  w.unmount()
  const w2 = page()
  await settle()
  await state(w2).removeDepartment(state(w2).nodeMap.get(9))
  expect(f.api.deleteDepartment).not.toHaveBeenCalled()
  expect(f.api.createDepartment).toHaveBeenCalledTimes(1)
  w2.unmount()
})
it('保存期间新的手动草稿不被旧成功清空；树晚回不覆盖新身份', async () => {
  const w = page()
  await edit(w)
  const d = deferred()
  f.api.updateDepartment.mockReturnValueOnce(d.promise)
  const a = state(w).saveDepartment()
  await settle()
  state(w).form.deptName = '新的人工稿'
  d.resolve({})
  await a
  expect(state(w).form.deptName).toBe('新的人工稿')
  expect(state(w).dialogVisible).toBe(true)
  const old = deferred()
  f.api.listDepartments.mockReturnValueOnce(old.promise)
  const b = state(w).fetchDepartments()
  f.api.listDepartments.mockResolvedValue({ data: [] })
  reactive(f.auth).token = 'B'
  await settle()
  old.resolve({ data: tree() })
  await b
  expect(state(w).departments).toEqual([])
  w.unmount()
})

it('旧成员请求失败后换身份不再续发旧树剩余部门请求', async () => {
  f.api.listDepartments.mockResolvedValue({
    data: Array.from({ length: 12 }, (_, i) => ({
      id: i + 1,
      deptName: '部' + i,
      deptCode: 'D' + i,
      sortOrder: 0,
      children: [],
    })),
  })
  const d = deferred()
  f.api.listUsers.mockImplementation(async () => {
    await d.promise
    throw new Error('旧请求失败')
  })
  const w = page()
  await settle()
  expect(f.api.listUsers).toHaveBeenCalledTimes(6)
  reactive(f.iam).loaded = false
  reactive(f.auth).token = 'B'
  d.resolve({})
  await settle()
  expect(f.api.listUsers).toHaveBeenCalledTimes(6)
  w.unmount()
})
it('嵌套节点父ID错属和null回执不当作正常启用树/真实0', async () => {
  const wrong = tree()
  wrong[0]!.children[0]!.parentId = 99
  f.api.listDepartments.mockResolvedValue({ data: wrong })
  const w = page()
  await settle()
  expect(state(w).treeVerified).toBe(false)
  expect(state(w).errorMessage).toContain('父级错属')
  f.api.listDepartments.mockResolvedValue({ data: null })
  await state(w).fetchDepartments()
  expect(state(w).treeVerified).toBe(false)
  expect(state(w).errorMessage).toContain('不能确认为空')
  w.unmount()
})
it('删除已发出后切选其他部门，成功后独立重读树并保留新选择', async () => {
  const w = page()
  await settle()
  state(w).selectedId = 9
  const d = deferred()
  f.api.deleteDepartment.mockReturnValueOnce(d.promise)
  const a = state(w).removeDepartment(state(w).nodeMap.get(9))
  await settle()
  expect(f.api.deleteDepartment).toHaveBeenCalledTimes(1)
  state(w).selectedId = 10
  const fresh = tree()
  fresh[0]!.children = []
  f.api.listDepartments.mockResolvedValue({ data: fresh })
  d.resolve({})
  await a
  expect(state(w).nodeMap.has(9)).toBe(false)
  expect(state(w).selectedId).toBe(10)
  expect(state(w).treeVerified).toBe(true)
  w.unmount()
})

it('新建顶层预读null不是合法空树，零POST并保留草稿', async () => {
  const w = page()
  await settle()
  state(w).openCreate()
  Object.assign(state(w).form, { deptName: '人工新部门', deptCode: 'MANUAL' })
  f.api.listDepartments.mockResolvedValueOnce({ data: null })
  await state(w).saveDepartment()
  expect(f.api.createDepartment).not.toHaveBeenCalled()
  expect(state(w).form.deptName).toBe('人工新部门')
  expect(state(w).writeError).toContain('不是数组')
  expect(pendingGovernanceWrites.value).toEqual([])
  w.unmount()
})
