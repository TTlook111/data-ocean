// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { reactive, nextTick } from 'vue'
import { beforeEach, it, expect, vi } from 'vitest'
const s = vi.hoisted(() => ({
  auth: { token: 'A' },
  iam: {
    loaded: true,
    errorMessage: '',
    snapshot: {},
    systemAdmin: false,
    datasourceCapabilities: [
      { datasourceId: 1, datasourceName: '销售源' },
      { datasourceId: 2, datasourceName: '供应源' },
    ],
    codes: ['security:permission:view', 'security:permission:manage'],
    canOnDatasource: vi.fn(),
    hasGlobal: vi.fn(),
    load: vi.fn(),
  },
  api: {
    listIamS1Datasources: vi.fn(),
    listIamS1Subjects: vi.fn(),
    listIamS1GrantTemplates: vi.fn(),
    listIamS1Snapshots: vi.fn(),
    listIamS1Tables: vi.fn(),
    listIamS1Columns: vi.fn(),
    listIamS1DataGrants: vi.fn(),
    createIamS1DataGrant: vi.fn(),
    revokeIamS1DataGrant: vi.fn(),
  },
}))
vi.mock('../../../../stores/auth', () => ({ useAuthStore: () => reactive(s.auth) }))
vi.mock('../../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(s.iam) }))
vi.mock('../../../../api/iamS1', () => s.api)
import Panel from './IamS1DataGrantPanel.vue'
import { pendingGovernanceWrites } from '../../../../composables/governanceWriteTracker'
const grant = {
  id: 71,
  datasourceId: 1,
  subjectType: 'DEPARTMENT',
  subjectId: 8,
  status: 'ACTIVE',
  summary: '销售部订单金额',
  columns: ['amount'],
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
function page(props: any = { defaultDatasourceId: 1 }) {
  return shallowMount(Panel, { props, global: { plugins: [ElementPlus] } })
}
function vm(w: any) {
  return w.vm.$.setupState as any
}
async function fill(w: any) {
  vm(w).openEditor()
  await settle()
  vm(w).form.subjectType = 'DEPARTMENT'
  vm(w).form.subjectId = 8
  vm(w).form.tableName = 'orders'
  await vm(w).loadColumns()
  vm(w).form.selectedColumns = [204]
}
beforeEach(() => {
  vi.restoreAllMocks()
  s.auth.token = 'A'
  s.iam.loaded = true
  s.iam.errorMessage = ''
  s.iam.snapshot = {}
  s.iam.datasourceCapabilities = [
    { datasourceId: 1, datasourceName: '销售源' },
    { datasourceId: 2, datasourceName: '供应源' },
  ]
  s.iam.systemAdmin = false
  s.iam.codes = ['security:permission:view', 'security:permission:manage']
  s.iam.canOnDatasource.mockImplementation(
    (code, id) => [1, 2].includes(id) && reactive(s.iam).codes.includes(code),
  )
  s.iam.hasGlobal.mockImplementation((code) => reactive(s.iam).codes.includes(code))
  s.iam.load.mockResolvedValue(undefined)
  Object.values(s.api).forEach((x) => x.mockReset())
  s.api.listIamS1Datasources.mockResolvedValue({
    data: [
      { id: 1, name: '销售源' },
      { id: 2, name: '供应源' },
    ],
  })
  s.api.listIamS1Subjects.mockResolvedValue({
    data: [
      { id: 8, name: '销售部', subjectType: 'DEPARTMENT' },
      { id: 8, name: '同号用户', subjectType: 'USER' },
    ],
  })
  s.api.listIamS1GrantTemplates.mockResolvedValue({ data: [] })
  s.api.listIamS1Snapshots.mockImplementation(async (id) => ({
    data: [{ id: id * 10 + 1, datasourceId: id, status: 'PUBLISHED' }],
  }))
  s.api.listIamS1Tables.mockResolvedValue({ data: [{ tableName: 'orders', selectable: true }] })
  s.api.listIamS1Columns.mockResolvedValue({
    data: [
      { columnMetaId: 204, columnName: 'amount', dataType: 'DECIMAL(12,2)', selectable: true },
      { columnMetaId: 205, columnName: 'flag', dataType: 'BOOLEAN', selectable: true },
      { columnMetaId: 206, columnName: 'order_id', dataType: 'BIGINT', selectable: true },
    ],
  })
  s.api.listIamS1DataGrants.mockImplementation(async ({ datasourceId }) => ({
    data: [{ ...grant, datasourceId }],
  }))
  s.api.createIamS1DataGrant.mockResolvedValue({ data: 72 })
  s.api.revokeIamS1DataGrant.mockResolvedValue({ data: null })
  pendingGovernanceWrites.value = []
})
it('最小授权查看角色只用同绑定cap源，不请求组织/元数据辅助权限', async () => {
  s.iam.codes = ['security:permission:view']
  const w = page()
  await settle()
  expect(s.api.listIamS1DataGrants).toHaveBeenCalledWith({ datasourceId: 1 })
  expect(s.api.listIamS1Datasources).not.toHaveBeenCalled()
  expect(s.api.listIamS1Subjects).not.toHaveBeenCalled()
  expect(vm(w).canManage).toBe(false)
  w.unmount()
})
it('不能把其他角色负责源与本角色功能相乘', async () => {
  s.iam.canOnDatasource.mockImplementation((code, id) => id === 1 && reactive(s.iam).codes.includes(code))
  const w = page({ defaultDatasourceId: 2 })
  await settle()
  expect(s.api.listIamS1DataGrants).not.toHaveBeenCalled()
  expect(vm(w).readError).toContain('同绑定')
  w.unmount()
})
it('源切换后旧列表/错误不能覆盖新源', async () => {
  const d = deferred()
  s.api.listIamS1DataGrants
    .mockReturnValueOnce(d.promise)
    .mockResolvedValue({ data: [{ ...grant, id: 72, datasourceId: 2 }] })
  const w = page()
  await settle()
  vm(w).datasourceId = 2
  await vm(w).onDatasourceChange()
  d.resolve({ data: [grant] })
  await settle()
  expect(vm(w).grants[0].id).toBe(72)
  w.unmount()
})
it('同源换账号旧授权列表失效', async () => {
  const d = deferred()
  s.api.listIamS1DataGrants.mockReturnValueOnce(d.promise).mockResolvedValue({ data: [{ ...grant, id: 72 }] })
  const w = page()
  await settle()
  reactive(s.auth).token = 'B'
  await settle()
  d.resolve({ data: [grant] })
  await settle()
  expect(vm(w).grants[0].id).toBe(72)
  w.unmount()
})
it('刷新保留合法源与完整人工草稿，不切回默认源', async () => {
  const w = page()
  await settle()
  vm(w).datasourceId = 2
  await vm(w).onDatasourceChange()
  await fill(w)
  vm(w).form.reason = '供应源人工说明'
  await vm(w).refresh()
  expect(vm(w).datasourceId).toBe(2)
  expect(vm(w).editorOpen).toBe(true)
  expect(vm(w).form.selectedColumns).toEqual([204])
  expect(vm(w).form.reason).toBe('供应源人工说明')
  w.unmount()
})
it('非法URL flag变化立即阻断旧偏好源，即使default仍undefined', async () => {
  const w = page({})
  await settle()
  await fill(w)
  await w.setProps({ invalidDefaultDatasource: true })
  await settle()
  await vm(w).save()
  expect(s.api.createIamS1DataGrant).not.toHaveBeenCalled()
  expect(vm(w).editorOpen).toBe(false)
  expect(vm(w).readError).toContain('标识无效')
  w.unmount()
})
it('模板/快照辅助失败不阻主列表与源级DENY，确认精确范围一次写', async () => {
  s.api.listIamS1Snapshots.mockRejectedValue(new Error('目录暂不可用'))
  s.api.listIamS1GrantTemplates.mockRejectedValue(new Error('模板失败'))
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  const w = page()
  await settle()
  vm(w).openEditor()
  await settle()
  vm(w).form.subjectId = 8
  vm(w).denyMode = true
  await vm(w).save()
  expect(s.api.createIamS1DataGrant).toHaveBeenCalledWith(
    expect.objectContaining({
      effect: 'DENY',
      resourceScope: 'DATASOURCE',
      datasourceId: 1,
      tableName: null,
      metadataSnapshotId: null,
      columns: [],
      rowConditions: [],
    }),
  )
  expect(s.api.listIamS1DataGrants).toHaveBeenCalled()
  w.unmount()
})
it('ANY摘要使用或，主体同号按类型精确命名', async () => {
  const w = page()
  await settle()
  await fill(w)
  vm(w).form.subjectType = 'USER'
  vm(w).form.matchType = 'ANY'
  vm(w).form.conditions = [
    { columnName: 'amount', operatorCode: 'GT', value: '100' },
    { columnName: 'amount', operatorCode: 'LT', value: '5' },
  ]
  expect(vm(w).summaryText).toContain('同号用户')
  expect(vm(w).summaryText).toContain('，或 ')
  expect(vm(w).summaryText).not.toContain('销售部')
  w.unmount()
})
it.each([
  ['flag', 'yes'],
  ['order_id', '9007199254740993'],
  ['amount', '0x10'],
  ['amount', '0.123456789012345678901'],
])('条件%s=%s不静默转值/舍入', async (name, value) => {
  const w = page()
  await settle()
  await fill(w)
  vm(w).form.conditions = [{ columnName: name, operatorCode: 'EQ', value }]
  await vm(w).save()
  expect(s.api.createIamS1DataGrant).not.toHaveBeenCalled()
  expect(vm(w).editorError).toBeTruthy()
  w.unmount()
})
it('可无损小数和0保留类型，双击一次且409留完整草稿', async () => {
  const d = deferred()
  s.api.createIamS1DataGrant.mockReturnValue(d.promise)
  const w = page()
  await settle()
  await fill(w)
  vm(w).form.conditions = [{ columnName: 'amount', operatorCode: 'GE', value: '0.00' }]
  vm(w).form.reason = '人工依据'
  const a = vm(w).save()
  await vm(w).save()
  expect(s.api.createIamS1DataGrant).toHaveBeenCalledTimes(1)
  expect(s.api.createIamS1DataGrant.mock.calls[0][0].rowConditions[0]).toMatchObject({
    valueType: 'DECIMAL',
    structuredValueJson: '0',
  })
  d.reject({ response: { status: 409, data: { message: '来源变化' } } })
  await a
  expect(vm(w).form.reason).toBe('人工依据')
  expect(vm(w).editorOpen).toBe(true)
  expect(vm(w).editorError).toBe('来源变化')
  w.unmount()
})
it('撤销确认期间撤权或换源不写，不能撤另源grant', async () => {
  const d = deferred(),
    confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as never)
  const w = page()
  await settle()
  const a = vm(w).revoke(grant)
  await vm(w).revoke(grant)
  expect(confirm).toHaveBeenCalledTimes(1)
  vm(w).datasourceId = 2
  await vm(w).onDatasourceChange()
  d.resolve('confirm')
  await a
  expect(s.api.revokeIamS1DataGrant).not.toHaveBeenCalled()
  await vm(w).revoke(grant)
  expect(confirm).toHaveBeenCalledTimes(1)
  w.unmount()
})
it('未知创建跨重挂载锁定精确对象，不重复发起', async () => {
  s.api.createIamS1DataGrant.mockRejectedValue(new Error('network unknown'))
  const w = page()
  await settle()
  await fill(w)
  await vm(w).save()
  w.unmount()
  const next = page()
  await settle()
  await fill(next)
  await vm(next).save()
  expect(s.api.createIamS1DataGrant).toHaveBeenCalledTimes(1)
  expect(pendingGovernanceWrites.value[0].state).toBe('unknown')
  next.unmount()
})
it('收起重开保留完整字段/条件草稿，旧helper不能回填新会话', async () => {
  const w = page()
  await settle()
  await fill(w)
  vm(w).form.conditions = [{ columnName: 'amount', operatorCode: 'GT', value: '100' }]
  vm(w).closeEditor()
  vm(w).openEditor()
  await settle()
  expect(vm(w).form.tableName).toBe('orders')
  expect(vm(w).form.selectedColumns).toEqual([204])
  expect(vm(w).form.conditions[0].value).toBe('100')
  w.unmount()
})
it('读取错误不当成零授权，主列表保留失败状态', async () => {
  s.api.listIamS1DataGrants.mockRejectedValue(new Error('read fail'))
  const w = page()
  await settle()
  expect(vm(w).readError).toContain('read fail')
  expect(vm(w).grants).toEqual([])
  expect(vm(w).loading).toBe(false)
  w.unmount()
})

it('关闭重开时旧模板失败不能覆盖新模板或错误', async () => {
  const d = deferred()
  s.api.listIamS1GrantTemplates
    .mockReturnValueOnce(d.promise)
    .mockResolvedValue({ data: [{ code: 'NEW', name: '新模板' }] })
  const w = page()
  await settle()
  vm(w).openEditor()
  await settle()
  vm(w).closeEditor()
  vm(w).openEditor()
  await settle()
  d.reject(new Error('旧模板失败'))
  await settle()
  expect(vm(w).templates[0].code).toBe('NEW')
  expect(vm(w).helperError).not.toContain('旧模板')
  w.unmount()
})
it('旧快照回包及finally不能清掉新会话的目录加载', async () => {
  const a = deferred(),
    b = deferred()
  s.api.listIamS1Snapshots.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
  const w = page()
  await settle()
  vm(w).openEditor()
  await settle()
  vm(w).closeEditor()
  vm(w).openEditor()
  await settle()
  a.resolve({ data: [{ id: 999, status: 'PUBLISHED' }] })
  await settle()
  expect(vm(w).snapshotId).toBeUndefined()
  expect(vm(w).reading.snapshots).toBe(true)
  b.resolve({ data: [{ id: 11, status: 'PUBLISHED' }] })
  await settle()
  expect(vm(w).snapshotId).toBe(11)
  expect(vm(w).reading.snapshots).toBe(false)
  w.unmount()
})
it('创建成功晚回保留等待期间新增的人工条件和理由，不自动二次保存', async () => {
  const d = deferred()
  s.api.createIamS1DataGrant.mockReturnValue(d.promise)
  const w = page()
  await settle()
  await fill(w)
  const a = vm(w).save()
  vm(w).form.reason = '等待时新增人工依据'
  vm(w).form.conditions = [{ columnName: 'amount', operatorCode: 'GT', value: '100' }]
  d.resolve({ data: 72 })
  await a
  expect(vm(w).editorOpen).toBe(true)
  expect(vm(w).form.reason).toBe('等待时新增人工依据')
  expect(vm(w).form.conditions[0].value).toBe('100')
  expect(vm(w).editorError).toContain('新输入仍保留')
  expect(s.api.createIamS1DataGrant).toHaveBeenCalledTimes(1)
  w.unmount()
})
it('HTTP成功但授权ID回执缺失仍保留未知锁和草稿', async () => {
  s.api.createIamS1DataGrant.mockResolvedValue({ data: null })
  const w = page()
  await settle()
  await fill(w)
  await vm(w).save()
  expect(vm(w).editorOpen).toBe(true)
  expect(pendingGovernanceWrites.value[0].state).toBe('unknown')
  expect(vm(w).editorError).toContain('回执')
  await vm(w).save()
  expect(s.api.createIamS1DataGrant).toHaveBeenCalledTimes(1)
  w.unmount()
})

it('403释放确定失败目标锁并保留人工草稿，不自动重发', async () => {
  s.api.createIamS1DataGrant.mockRejectedValue({
    response: { status: 403, data: { message: '当前源维护权已撤销' } },
  })
  const w = page()
  await settle()
  await fill(w)
  vm(w).form.reason = '原人工依据'
  await vm(w).save()
  expect(vm(w).editorError).toBe('当前源维护权已撤销')
  expect(vm(w).editorOpen).toBe(true)
  expect(vm(w).form.reason).toBe('原人工依据')
  expect(pendingGovernanceWrites.value).toEqual([])
  expect(s.api.createIamS1DataGrant).toHaveBeenCalledTimes(1)
  w.unmount()
})
it('系统管理员通过管理资源目录读取源，不依赖cap列表也不生成业务查询权', async () => {
  s.iam.systemAdmin = true
  s.iam.datasourceCapabilities = []
  s.iam.canOnDatasource.mockReturnValue(true)
  s.iam.hasGlobal.mockReturnValue(true)
  const w = page({})
  await settle()
  expect(s.api.listIamS1Datasources).toHaveBeenCalledTimes(1)
  expect(vm(w).datasources.map((x: any) => x.id)).toEqual([1, 2])
  expect(s.api.createIamS1DataGrant).not.toHaveBeenCalled()
  w.unmount()
})
