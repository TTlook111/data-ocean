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
      'governance:rule:manage',
    ],
    globals: [] as string[],
    systemAdmin: false,
    datasourcesWithFunction: vi.fn(),
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
    listSnapshots: vi.fn(),
    listSnapshotTableColumns: vi.fn(),
    listQualityRules: vi.fn(),
    listQualityRuleTemplates: vi.fn(),
    getQualityRule: vi.fn(),
    submitQualityRuleOperation: vi.fn(),
    getQualityRuleOperation: vi.fn(),
    resolveQualityRuleOperation: vi.fn(),
    getQualityRuleHistory: vi.fn(),
    getQualityRuleRevisions: vi.fn(),
    updateTableGovernanceStatus: vi.fn(),
    updateColumnGovernanceStatus: vi.fn(),
    batchUpdateGovernanceStatus: vi.fn(),
  },
  router: { push: vi.fn(), replace: vi.fn() },
}))
vi.mock('vue-router', () => ({ useRoute: () => reactive(state.route), useRouter: () => state.router }))
vi.mock('../../../stores/adminContext', () => ({ useAdminContextStore: () => reactive(state.context) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(state.iam) }))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(state.auth) }))
vi.mock('../../../api/admin/governance', () => state.api)
vi.mock('../../../api/admin/qualityRuleOperations', () => state.api)
vi.mock('../../../api/admin/user', () => ({ listUsers: state.api.listUsers }))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: state.api.listSnapshots }))
import Rules from './QualityRulesView.vue'
import Status from './StatusEditor.vue'
import { pendingGovernanceWrites } from '../../../composables/governanceWriteTracker'
import { clearQualityRuleDrafts } from './rules/qualityRuleDrafts'
function deferred() {
  let resolve!: (x: any) => void, reject!: (x: any) => void
  const promise = new Promise<any>((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
const rule = {
  id: 71,
  ruleName: '字段说明完整性',
  templateCode: 'COMP_COLUMN_COMMENT_MISSING',
  scopeType: 'DATASOURCE',
  datasourceId: 5,
  enabled: 1,
  supported: true,
  parameters: {},
  configVersion: 2,
  severity: 'MEDIUM',
  deductionPoints: 3,
}
async function settle() {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await nextTick()
  }
}
function page(component: any) {
  return shallowMount(component, {
    global: { plugins: [ElementPlus], stubs: { RouterLink: { template: '<a><slot/></a>' } } },
  })
}
beforeEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  clearQualityRuleDrafts()
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
    'governance:rule:manage',
  ]
  state.iam.globals = []
  state.iam.canOnDatasource
    .mockReset()
    .mockImplementation((code, id) => id === 5 && reactive(state.iam).codes.includes(code))
  state.iam.datasourcesWithFunction.mockImplementation((code) =>
    reactive(state.iam).codes.includes(code) ? [5] : [],
  )
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
  state.api.listQualityIssues.mockResolvedValue({ data: { records: [], total: 1 } })
  state.api.listSnapshotTables.mockResolvedValue({ data: [] })
  state.api.listUsers.mockResolvedValue({ data: { records: [], total: 0 } })
  state.api.handleIssue.mockResolvedValue({ data: null })
  state.api.batchHandleIssues.mockResolvedValue({
    data: { updated: 0, skipped: 1, skippedIssues: [{ issueId: 1, reason: '需最新复查及依据' }] },
  })
  state.api.listQualityRules.mockResolvedValue({ data: { records: [rule], total: 1 } })
  state.api.listQualityRuleTemplates.mockResolvedValue({
    data: [
      {
        code: 'COMP_COLUMN_COMMENT_MISSING',
        name: '字段说明完整性',
        dimension: 'COMPLETENESS',
        targetType: 'COLUMN',
        checkType: 'SCHEMA',
        allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'],
        parameters: [],
      },
    ],
  })
  state.api.getQualityRuleHistory.mockResolvedValue({ data: [] })
  state.api.getQualityRuleRevisions.mockResolvedValue({ data: [] })
  state.api.listSnapshotTables.mockResolvedValue({
    data: [{ id: 11, tableName: 'orders', governanceStatus: 'NORMAL' }],
  })
  state.api.listSnapshotTableColumns.mockResolvedValue({
    data: [{ id: 101, columnName: 'amount', governanceStatus: 'NORMAL' }],
  })
  state.api.batchUpdateGovernanceStatus.mockResolvedValue({ data: { updated: 0, excluded: 2 } })
  state.api.submitQualityRuleOperation.mockImplementation(async request => ({ data: { operationId: request.operationId, action: request.action, outcome: 'APPLIED', ruleId: request.ruleId || 81, configVersion: (request.expectedVersion || 0) + 1 } }))
  state.api.getQualityRuleOperation.mockImplementation(async operationId => ({ data: { operationId, outcome: 'NOT_FOUND' } }))
  pendingGovernanceWrites.value = []
})
afterEach(() => vi.restoreAllMocks())

it('规则核心列表不因无metadata辅助权或公共快照而消失', async () => {
  state.iam.codes = ['governance:rule:view']
  state.context.snapshotId = undefined
  const w = page(Rules)
  await settle()
  expect(state.api.listQualityRules).toHaveBeenCalled()
  expect(state.api.listSnapshots).not.toHaveBeenCalled()
  expect(state.api.getQualityRuleSummary).not.toHaveBeenCalled()
  w.unmount()
})
// 草稿属于人工输入；关闭编辑器只隐藏工作面，不能把重新打开变成隐式放弃。
it('关闭再打开同规则保留人工草稿与原版本', async () => {
  const w = page(Rules)
  await settle()
  await (w.vm as any).editRule(rule)
  ;(w.vm as any).form.description = '尚未保存的处理建议'
  ;(w.vm as any).editorOpen = false
  await settle()
  await (w.vm as any).editRule({ ...rule, configVersion: 3 })
  expect((w.vm as any).form.description).toBe('尚未保存的处理建议')
  expect((w.vm as any).editingRule.configVersion).toBe(2)
  w.unmount()
})

// 目录是可选辅助读取，不能让主编辑工作面等待一个没有结束的目录请求。
it('表规则打开不等待辅助目录且晚响应不覆盖人工输入', async () => {
  const directory = deferred()
  state.api.listSnapshots.mockReturnValue(directory.promise)
  const w = page(Rules)
  await settle()
  const opening = (w.vm as any).editRule({ ...rule, scopeType: 'TABLE', targetTableName: 'orders' })
  await settle()
  expect((w.vm as any).editorOpen).toBe(true)
  ;(w.vm as any).form.description = '目录等待中的草稿'
  directory.resolve({ data: { records: [] } })
  await opening
  expect((w.vm as any).form.description).toBe('目录等待中的草稿')
  w.unmount()
})
it('非法显式源不使用偏好源读规则', async () => {
  state.route.query.datasourceId = 'bad'
  const w = page(Rules)
  await settle()
  expect(state.api.listQualityRules).not.toHaveBeenCalled()
  w.unmount()
})
it('缺失权威total的列表回执显示读取失败，不猜测为空成功', async () => {
  state.api.listQualityRules.mockResolvedValue({ data: { records: [] } })
  const w = page(Rules)
  await settle()
  expect((w.vm as any).rulesError).toContain('读取失败')
  w.unmount()
})
it('规则同源账号切换旧列表和错误失效', async () => {
  const a = deferred(),
    b = deferred()
  state.api.listQualityRules.mockReturnValueOnce(a.promise).mockReturnValue(b.promise)
  const w = page(Rules)
  await settle()
  reactive(state.auth).token = 'B'
  await settle()
  a.resolve({ data: { records: [rule], total: 9 } })
  await settle()
  expect((w.vm as any).rules).toEqual([])
  b.resolve({ data: { records: [{ ...rule, id: 72 }], total: 1 } })
  await settle()
  expect((w.vm as any).rules[0].id).toBe(72)
  w.unmount()
})
it('校验等待换稿不能保存新目标，下一次须显式提交', async () => {
  const d = deferred()
  const w = page(Rules)
  await settle()
  ;(w.vm as any).createRule()
  ;(w.vm as any).form.templateCode = rule.templateCode
  ;(w.vm as any).form.ruleName = '原稿'
  ;(w.vm as any).formRef = { validate: () => d.promise, clearValidate: vi.fn() }
  const save = (w.vm as any).saveRule()
  ;(w.vm as any).form.ruleName = '新草稿'
  d.resolve(true)
  await save
  expect(state.api.submitQualityRuleOperation).not.toHaveBeenCalled()
  expect((w.vm as any).editorError).toContain('草稿已变化')
  w.unmount()
})
it('规则删除确认后撤权零写，双击只一确认', async () => {
  const d = deferred(),
    confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as never)
  const w = page(Rules)
  await settle()
  const a = (w.vm as any).removeRule(rule)
  await (w.vm as any).removeRule(rule)
  expect(confirm).toHaveBeenCalledTimes(1)
  reactive(state.iam).codes = ['governance:rule:view']
  reactive(state.iam).snapshot = { revision: 2 }
  d.resolve('confirm')
  await a
  expect(state.api.submitQualityRuleOperation).not.toHaveBeenCalled()
  w.unmount()
})
it('规则409保留原表单并显示原因', async () => {
  state.api.submitQualityRuleOperation.mockRejectedValue({
    response: { status: 409, data: { message: '配置已变化' } },
  })
  const w = page(Rules)
  await settle()
  ;(w.vm as any).createRule()
  ;(w.vm as any).form.templateCode = rule.templateCode
  ;(w.vm as any).form.ruleName = '保留原稿'
  ;(w.vm as any).formRef = { validate: async () => true, clearValidate: vi.fn() }
  await (w.vm as any).saveRule()
  expect((w.vm as any).form.ruleName).toBe('保留原稿')
  expect((w.vm as any).editorOpen).toBe(true)
  expect((w.vm as any).editorError).toBe('配置已变化')
  w.unmount()
})
it('规则网络未知保留锁，重进页面也不重复复制', async () => {
  state.api.submitQualityRuleOperation.mockRejectedValue(new Error('网络未知'))
  const w = page(Rules)
  await settle()
  await (w.vm as any).copyRule(rule)
  w.unmount()
  const next = page(Rules)
  await settle()
  await (next.vm as any).copyRule(rule)
  expect(state.api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
  expect((next.vm as any).pendingOperations).toHaveLength(1)
  next.unmount()
})
it('状态高影响确认撤权/同源换账号后不写', async () => {
  const d = deferred()
  vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(d.promise as never)
  const w = page(Status)
  await settle()
  const write = (w.vm as any).changeTableStatus('orders', 'BLOCKED')
  reactive(state.auth).token = 'B'
  await settle()
  d.resolve('confirm')
  await write
  expect(state.api.updateTableGovernanceStatus).not.toHaveBeenCalled()
  w.unmount()
})
it('批量状态真实0与排除回执不混淆；重复写一次', async () => {
  const d = deferred()
  state.api.batchUpdateGovernanceStatus.mockReturnValue(d.promise)
  const w = page(Status)
  await settle()
  await (w.vm as any).fetchColumns('orders')
  const a = (w.vm as any).batchSetColumns('NORMAL')
  await (w.vm as any).batchSetColumns('NORMAL')
  expect(state.api.batchUpdateGovernanceStatus).toHaveBeenCalledTimes(1)
  d.resolve({ data: { updated: 0, excluded: 2 } })
  await a
  expect((w.vm as any).receipt).toContain('更新 0')
  expect((w.vm as any).receipt).toContain('排除 2')
  w.unmount()
})
it('状态未知数量不显示为0；无metadata不读取对象', async () => {
  state.api.batchUpdateGovernanceStatus.mockResolvedValue({ data: {} })
  const w = page(Status)
  await settle()
  await (w.vm as any).fetchColumns('orders')
  await (w.vm as any).batchSetColumns('NORMAL')
  expect((w.vm as any).receipt).toContain('更新 未知')
  reactive(state.iam).codes = ['governance:rule:manage']
  reactive(state.iam).snapshot = { revision: 2 }
  await settle()
  expect((w.vm as any).tables).toEqual([])
  w.unmount()
})

it('保存发送原版本由服务端裁决冲突，保留原草稿', async () => {
  state.api.submitQualityRuleOperation.mockImplementation(async request => ({ data: { operationId: request.operationId,
    action: request.action, outcome: 'CONFLICT', errorMessage: '配置已变化' } }))
  const w = page(Rules)
  await settle()
  await (w.vm as any).editRule(rule)
  ;(w.vm as any).form.description = '待保存建议'
  ;(w.vm as any).formRef = { validate: async () => true, clearValidate: vi.fn() }
  await (w.vm as any).saveRule()
  expect(state.api.submitQualityRuleOperation).toHaveBeenCalledWith(expect.objectContaining({ action: 'UPDATE', ruleId: 71, expectedVersion: 2 }))
  expect((w.vm as any).editorError).toContain('配置已变化')
  expect((w.vm as any).form.description).toBe('待保存建议')
  expect((w.vm as any).editingRule.configVersion).toBe(2)
  expect((w.vm as any).editorOpen).toBe(true)
  w.unmount()
})

it('无效显式快照不展示偏好规划但源级规则仍可读', async () => {
  state.route.query.snapshotId = 'bad'
  const w = page(Rules)
  await settle()
  expect(state.api.getQualityRuleSummary).not.toHaveBeenCalled()
  expect(state.api.listQualityRules).toHaveBeenCalled()
  w.unmount()
})
it('显式快照失效后旧规划晚回不能显示', async () => {
  const d = deferred()
  state.api.getQualityRuleSummary.mockReturnValue(d.promise)
  const w = page(Rules)
  await settle()
  reactive(state.route).query.snapshotId = 'bad'
  await settle()
  d.resolve({ data: { snapshotId: 8, datasourceId: 5, enabledCount: 99 } })
  await settle()
  expect((w.vm as any).applicability).toBeNull()
  w.unmount()
})

it('启停发送列表原版本，不把前端预读当作并发保护', async () => {
  const w = page(Rules)
  await settle()
  await (w.vm as any).toggleRule(rule)
  expect(state.api.submitQualityRuleOperation).toHaveBeenCalledWith(expect.objectContaining({ action: 'DISABLE', ruleId: 71, expectedVersion: 2 }))
  expect(state.api.getQualityRule).not.toHaveBeenCalled()
  w.unmount()
})

// 原操作回执与当前编辑器分别归属；迟到的无权错误只能清原对象的草稿。
it('A源保存迟到403不会清除已切换到B源的有权草稿', async () => {
  state.iam.canOnDatasource.mockImplementation((code, id) => [5, 6].includes(id) && reactive(state.iam).codes.includes(code))
  const response = deferred()
  state.api.submitQualityRuleOperation.mockReturnValue(response.promise)
  const w = page(Rules)
  await settle()
  await (w.vm as any).editRule(rule)
  ;(w.vm as any).form.description = 'A源输入'
  ;(w.vm as any).formRef = { validate: async () => true, clearValidate: vi.fn() }
  const saving = (w.vm as any).saveRule()
  await settle()
  reactive(state.route).query.datasourceId = '6'
  reactive(state.context).datasourceId = 6
  await settle()
  await (w.vm as any).editRule({ ...rule, id: 72, datasourceId: 6 })
  ;(w.vm as any).form.description = 'B源的有权草稿'
  response.reject({ response: { status: 403 } })
  await saving
  expect((w.vm as any).editorOpen).toBe(true)
  expect((w.vm as any).editingRuleId).toBe(72)
  expect((w.vm as any).form.description).toBe('B源的有权草稿')
  w.unmount()
})

it('创建切全局范围后迟到成功，按稳定草稿标识消费且不会再次CREATE', async () => {
  state.iam.systemAdmin = true
  state.iam.globals = ['governance:rule:view']
  const response = deferred()
  state.api.submitQualityRuleOperation.mockReturnValue(response.promise)
  const w = page(Rules)
  await settle()
  ;(w.vm as any).createRule()
  ;(w.vm as any).form.templateCode = rule.templateCode
  ;(w.vm as any).form.ruleName = '跨范围新规则'
  await (w.vm as any).changeFormScope('GLOBAL')
  ;(w.vm as any).formRef = { validate: async () => true, clearValidate: vi.fn() }
  const saving = (w.vm as any).saveRule()
  await settle()
  ;(w.vm as any).editorOpen = false
  const submitted = state.api.submitQualityRuleOperation.mock.calls[0]![0]
  response.resolve({ data: { operationId: submitted.operationId, action: 'CREATE', outcome: 'APPLIED', ruleId: 81, configVersion: 1 } })
  await saving
  expect((w.vm as any).pendingOperations[0].draftKey).toBe('create:5')
  ;(w.vm as any).createRule()
  expect((w.vm as any).form.ruleName).toBe('跨范围新规则')
  expect((w.vm as any).editorPending).toBe(true)
  state.api.getQualityRule.mockResolvedValue({ data: { ...rule, id: 81, ruleName: '跨范围新规则', scopeType: 'GLOBAL', datasourceId: undefined, configVersion: 1 } })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  await (w.vm as any).acknowledgeOperation((w.vm as any).pendingOperations[0])
  expect((w.vm as any).editingRuleId).toBe(81)
  expect((w.vm as any).pendingOperations).toHaveLength(0)
  expect(state.api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
  w.unmount()
  state.iam.systemAdmin = false
})

it('保存成功时的后续输入保留并阻止原新建草稿重复提交', async () => {
  const response = deferred()
  state.api.submitQualityRuleOperation.mockReturnValue(response.promise)
  const w = page(Rules)
  await settle()
  ;(w.vm as any).createRule()
  ;(w.vm as any).form.templateCode = rule.templateCode
  ;(w.vm as any).form.ruleName = '已提交正文'
  ;(w.vm as any).formRef = { validate: async () => true, clearValidate: vi.fn() }
  const saving = (w.vm as any).saveRule()
  await settle()
  ;(w.vm as any).form.ruleName = '等待期间的后续输入'
  const submitted = state.api.submitQualityRuleOperation.mock.calls[0]![0]
  response.resolve({ data: { operationId: submitted.operationId, action: 'CREATE', outcome: 'APPLIED', ruleId: 81, configVersion: 1 } })
  await saving
  expect((w.vm as any).form.ruleName).toBe('等待期间的后续输入')
  expect((w.vm as any).editorPending).toBe(true)
  await (w.vm as any).saveRule()
  expect(state.api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
  w.unmount()
})

it.each(['resolve','reject'])('首次初始化挂起换账号旧%s不能堵塞或覆盖新账号',async(mode)=>{const d=deferred();state.context.initialize.mockReturnValueOnce(d.promise).mockResolvedValue(undefined);const w=page(Rules);await settle();expect(state.api.listQualityRules).not.toHaveBeenCalled();reactive(state.auth).token='B';await settle();expect(state.api.listQualityRules).toHaveBeenCalled();if(mode==='resolve')d.resolve(undefined);else d.reject(new Error('旧初始化失败'));await settle();expect((w.vm as any).pageReady).toBe(true);expect((w.vm as any).pageError).toBe('');expect((w.vm as any).rules[0].id).toBe(71);w.unmount()})
