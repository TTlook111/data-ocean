// @vitest-environment happy-dom
/**
 * 批次 4 前端能力控制测试（数据治理工作区）
 *
 * 后端拒绝可以防越权，但前端仍会向无权限用户展示可操作按钮，产生大量必然失败的请求——
 * 这不符合 B4「页面与业务接入」的完成标准。本测试按页面断言：
 * - 无能力时不发那个必然 403 的请求；
 * - 有查看能力但没有操作能力时，按钮不可用并给出中文原因；
 * - 能力结论只来自 IAM-SIMPLE-1 能力摘要，且必须“功能 + 负责源”在同一绑定上成立。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus, { ElDialog, ElSelect } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'

/** 可变的“当前路由”，让用例能把页面直接挂载在指定 Tab 上。 */
const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))

const api = vi.hoisted(() => ({
  getIamS1Capabilities: vi.fn(),
  listQualityIssues: vi.fn(),
  handleIssue: vi.fn(),
  batchHandleIssues: vi.fn(),
  assignIssue: vi.fn(),
  listReviewRecords: vi.fn(),
  listQualityRules: vi.fn(),
  listQualityRuleTemplates: vi.fn(),
  createQualityRule: vi.fn(),
  editQualityRule: vi.fn(),
  copyQualityRule: vi.fn(),
  getQualityRule: vi.fn(),
  getQualityRuleHistory: vi.fn(),
  getQualityRuleRevisions: vi.fn(),
  deleteQualityRule: vi.fn(),
  restoreQualityRule: vi.fn(),
  getLatestQualityCheckBatch: vi.fn(),
  getQualityRuleSummary: vi.fn(),
  getSnapshotQualityIssueSummary: vi.fn(),
  updateRuleEnabled: vi.fn(),
  listSnapshotTables: vi.fn(),
  listSnapshotTableColumns: vi.fn(),
  updateTableGovernanceStatus: vi.fn(),
  updateColumnGovernanceStatus: vi.fn(),
  batchUpdateGovernanceStatus: vi.fn(),
  listMaskCandidates: vi.fn(),
  confirmMaskCandidate: vi.fn(),
  rejectMaskCandidate: vi.fn(),
  listSimpleDatasources: vi.fn(),
  listSnapshots: vi.fn(),
  listKnowledgeDocs: vi.fn(),
  listUsers: vi.fn(),
}))
const mountedPages: Array<{ unmount: () => void }> = []

vi.mock('../../../api/iamS1', () => ({ getIamS1Capabilities: api.getIamS1Capabilities }))
vi.mock('../../../api/admin/governance', () => ({
  listQualityIssues: api.listQualityIssues,
  handleIssue: api.handleIssue,
  batchHandleIssues: api.batchHandleIssues,
  assignIssue: api.assignIssue,
  listReviewRecords: api.listReviewRecords,
  listQualityRules: api.listQualityRules,
  listQualityRuleTemplates: api.listQualityRuleTemplates,
  createQualityRule: api.createQualityRule,
  editQualityRule: api.editQualityRule,
  copyQualityRule: api.copyQualityRule,
  getQualityRule: api.getQualityRule,
  getQualityRuleHistory: api.getQualityRuleHistory,
  getQualityRuleRevisions: api.getQualityRuleRevisions,
  deleteQualityRule: api.deleteQualityRule,
  restoreQualityRule: api.restoreQualityRule,
  getLatestQualityCheckBatch: api.getLatestQualityCheckBatch,
  getQualityRuleSummary: api.getQualityRuleSummary,
  getSnapshotQualityIssueSummary: api.getSnapshotQualityIssueSummary,
  updateRuleEnabled: api.updateRuleEnabled,
  listSnapshotTables: api.listSnapshotTables,
  listSnapshotTableColumns: api.listSnapshotTableColumns,
  updateTableGovernanceStatus: api.updateTableGovernanceStatus,
  updateColumnGovernanceStatus: api.updateColumnGovernanceStatus,
  batchUpdateGovernanceStatus: api.batchUpdateGovernanceStatus,
}))
vi.mock('../../../api/admin/catalog', () => ({
  listMaskCandidates: api.listMaskCandidates,
  confirmMaskCandidate: api.confirmMaskCandidate,
  rejectMaskCandidate: api.rejectMaskCandidate,
}))
vi.mock('../../../api/admin/user', () => ({ listUsers: api.listUsers }))
vi.mock('../../../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots }))
vi.mock('../../../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))
// 只替换页面用到的那两个组合式函数：`src/api/http.ts` 会在 401 时用真实 router 跳转，
// 整体替换模块会让它拿不到 createRouter。
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routeState,
    useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  }
})

import IssueList from './IssueList.vue'
import StatusEditor from './StatusEditor.vue'
import QualityRulesView from './QualityRulesView.vue'
import QualityDashboard from './QualityDashboard.vue'
import GovernanceFieldsView from './GovernanceFieldsView.vue'
import { useIamS1Store } from '../../../stores/iamS1'
import { useAdminContextStore } from '../../../stores/adminContext'

const DATASOURCE_ID = 5

/**
 * 构造能力摘要。
 *
 * 两处能力语义必须区分开，否则测试会自己骗自己：
 * - `global` 对应 Java `requireGlobalFunction`（只看角色是否授予该功能码，与负责源无关），
 *   注解上的 `@IamS1ScopedList` 与全局功能走的是它；
 * - `onDatasource` 对应 Java `requireDatasourceFunction`（功能与负责源必须在同一条绑定上），
 *   `@IamS1Resource` 走的是它。
 */
function capability(options: {
  global?: string[]
  onDatasource?: string[]
  systemAdmin?: boolean
}): IamS1CapabilitySnapshot {
  const codes = options.onDatasource ?? []
  return {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 7,
    systemAdmin: options.systemAdmin ?? false,
    globalFunctions: options.global ?? [],
    datasourceCapabilities: [
      { datasourceId: DATASOURCE_ID, datasourceName: '销售库', functionCodes: codes, functionNames: codes },
    ],
    queryUse: false,
    viewSql: false,
    export: false,
    permissionRevision: 1,
  }
}

function respond(snapshot: IamS1CapabilitySnapshot) {
  api.getIamS1Capabilities.mockResolvedValue({ code: 200, message: 'ok', data: snapshot })
}

function mountPage(component: unknown) {
  const wrapper = mount(component as never, {
    global: {
      plugins: [ElementPlus],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
  mountedPages.push(wrapper)
  return wrapper
}

/**
 * 等到页面异步链路与渲染全部落定。
 *
 * 不能用单次 `flushPromises`：Element Plus 的表格/标签页在随后的微任务和帧里才真正渲染出内容，
 * 机器负载高时会读到“还没渲染完”的 DOM，让断言随机失败。
 */
async function settle() {
  for (let round = 0; round < 5; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

/**
 * 等待某条数据行渲染出来。
 *
 * 页面要在若干次异步链路之后才渲染表格；只等固定轮次在机器负载高时会读到空 DOM，
 * 而「按钮不存在」这类断言在空 DOM 上会**空洞地通过**——先确认行存在，断言才有意义。
 */
async function waitForRow(wrapper: ReturnType<typeof mountPage>, rowText: string) {
  return vi.waitFor(() => {
    const row = wrapper.findAll('.el-table__row').find((item) => item.text().includes(rowText))
    if (!row) throw new Error(`数据行「${rowText}」尚未渲染`)
    return row
  })
}

/** 等待某行的某个按钮渲染出来，用于点击前的先决条件。 */
async function waitForRowButton(wrapper: ReturnType<typeof mountPage>, rowText: string, buttonText: string) {
  return vi.waitFor(() => {
    const button = rowButton(wrapper, rowText, buttonText)
    if (!button) throw new Error(`行「${rowText}」的按钮「${buttonText}」尚未渲染`)
    return button
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}
/**
 * 在**真实数据行**里找按钮。
 *
 * 不能直接 `findAll('button')`：`el-table` 会在隐藏列区域里再渲染一份列模板，
 * 那份的插槽数据是空对象，点它既不会走业务逻辑，也会让断言得出错误结论。
 */
function rowButton(wrapper: ReturnType<typeof mountPage>, rowText: string, buttonText: string) {
  const row = wrapper.findAll('.el-table__row').find((item) => item.text().includes(rowText))
  return row?.findAll('button').find((button) => button.text() === buttonText)
}

const issue = {
  id: 1,
  snapshotId: 8,
  datasourceId: DATASOURCE_ID,
  datasourceName: '销售库',
  dimension: 'COMPLETENESS',
  severity: 'HIGH',
  tableName: 'orders',
  issueDescription: '订单表存在空值',
  status: 'OPEN',
  createdAt: '2026-09-20 10:00:00',
}

beforeEach(() => {
  setActivePinia(createPinia())
  routeState.query = {}
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: DATASOURCE_ID, name: '销售库' }] })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 8, snapshotVersion: 1 }], total: 1 } })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listUsers.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listQualityIssues.mockResolvedValue({ data: { records: [issue], total: 1 } })
  api.listQualityRules.mockResolvedValue({ data: [] })
  api.listQualityRuleTemplates.mockResolvedValue({ data: [{
    code: 'COMP_COLUMN_COMMENT_MISSING', name: '字段注释缺失', dimension: 'COMPLETENESS',
    targetType: 'COLUMN', checkType: 'SCHEMA', description: '检查字段说明',
    allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'], parameters: [],
  }] })
  api.getQualityRule.mockImplementation(async (ruleId: number) => ({ data: { id: ruleId } }))
  api.getQualityRuleHistory.mockResolvedValue({ data: [] })
  api.getQualityRuleRevisions.mockResolvedValue({ data: [] })
  api.getLatestQualityCheckBatch.mockResolvedValue({ data: null })
  api.getQualityRuleSummary.mockResolvedValue({ data: { enabledCount: 0, applicableCount: 0, unsupportedCount: 0, overriddenCount: 0, executedCount: 0 } })
  api.getSnapshotQualityIssueSummary.mockResolvedValue({ data: { totalUnresolved: 0, statusCounts: { OPEN: 0, CONFIRMED: 0, REOPENED: 0 }, recentIssues: [] } })
  api.listSnapshotTables.mockResolvedValue({ data: [] })
  api.listMaskCandidates.mockResolvedValue({ data: [] })
  api.listReviewRecords.mockResolvedValue({ data: { records: [], total: 0 } })
})

afterEach(() => {
  mountedPages.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('IssueList 的能力控制', () => {
  it('没有处理能力时不渲染任何写操作按钮，只显示原因', async () => {
    respond(capability({ global: ['governance:issue:view'] }))
    const wrapper = mountPage(IssueList)
    await settle()

    // 有查看权、无处理权：不应出现任何会触发写接口的按钮
    expect(wrapper.text()).toContain('无处理权限')
    await waitForRow(wrapper, 'orders')
    expect(rowButton(wrapper, 'orders', '确认')).toBeUndefined()
    expect(rowButton(wrapper, 'orders', '驳回')).toBeUndefined()
    expect(api.handleIssue).not.toHaveBeenCalled()
  })

  it('在负责源上拥有处理能力时才渲染操作按钮', async () => {
    respond(capability({ global: ['governance:issue:view'], onDatasource: ['governance:issue:manage'] }))
    const wrapper = mountPage(IssueList)
    await settle()

    expect(wrapper.text()).not.toContain('无处理权限')
    const confirm = await waitForRowButton(wrapper, 'orders', '确认')

    await confirm!.trigger('click')
    await settle()
    // 处理动作按问题 ID 提交，后端会按该问题的真实归属再做一次判定
    expect(api.handleIssue).toHaveBeenCalledWith(1, { status: 'CONFIRMED' })
  })

  it('功能与负责源不在同一绑定时不渲染操作按钮', async () => {
    // 功能码授予在另一个数据源上：服务端能力摘要不会把它挂到 5 上
    respond(capability({ global: ['governance:issue:view'] }))
    api.getIamS1Capabilities.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        ...capability({ global: ['governance:issue:view'] }),
        datasourceCapabilities: [
          {
            datasourceId: 9,
            datasourceName: '财务库',
            functionCodes: ['governance:issue:manage'],
            functionNames: ['处理质量问题'],
          },
        ],
      },
    })
    const wrapper = mountPage(IssueList)
    await settle()

    await waitForRow(wrapper, 'orders')
    expect(wrapper.text()).toContain('无处理权限')
    expect(rowButton(wrapper, 'orders', '确认')).toBeUndefined()
  })

  it('没有发布域能力时不请求同表治理记录', async () => {
    respond(capability({ global: ['governance:issue:view'], onDatasource: ['governance:issue:manage'] }))
    const wrapper = mountPage(IssueList)
    await settle()

    const detail = await waitForRowButton(wrapper, 'orders', '详情')
    await detail.trigger('click')
    await settle()

    // 审核记录属于 metadata:release:view，治理查看权不自动包含它
    expect(api.listReviewRecords).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('审核记录属于发布域')
  })

  it('拥有发布域能力时才请求同表治理记录', async () => {
    respond(capability({
      global: ['governance:issue:view'],
      onDatasource: ['governance:issue:manage', 'metadata:release:view'],
    }))
    const wrapper = mountPage(IssueList)
    await settle()

    const detail = await waitForRowButton(wrapper, 'orders', '详情')
    await detail.trigger('click')
    await settle()

    expect(api.listReviewRecords).toHaveBeenCalledWith(8, expect.objectContaining({ tableName: 'orders' }))
  })
})

describe('资产状态维护的能力控制', () => {
  it('资产状态页面不再读取质量规则列表', async () => {
    respond(capability({ global: [] }))
    const wrapper = mountPage(StatusEditor)
    await settle()

    expect(api.listQualityRules).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('资产状态')
  })

  it('没有 metadata:view 时不自行请求快照列表', async () => {
    respond(capability({ onDatasource: ['governance:rule:manage'] }))
    const wrapper = mountPage(StatusEditor)
    await settle()

    expect(api.listSnapshots).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('metadata:view')
  })
})

describe('质量规则管理的能力控制', () => {
  const globalRule = {
    id: 3, ruleCode: 'QR-3', templateCode: 'COMP_COLUMN_COMMENT_MISSING', ruleName: '字段说明规则',
    dimension: 'COMPLETENESS', severity: 'HIGH', description: '', checkTarget: 'COLUMN', checkType: 'SCHEMA',
    scopeType: 'GLOBAL', parameters: {}, configVersion: 1, enabled: 1, deductionPoints: 5,
    builtin: 1, supported: true,
  }

  it('没有治理规则查看权限时不请求规则或模板列表', async () => {
    respond(capability({ onDatasource: ['metadata:view'] }))
    const wrapper = mountPage(QualityRulesView)
    await settle()

    expect(api.listQualityRules).not.toHaveBeenCalled()
    expect(api.listQualityRuleTemplates).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('没有当前范围的“查看治理规则”权限')
  })

  it('规则列表读取失败显示错误而不是空列表', async () => {
    respond(capability({ onDatasource: ['governance:rule:view'] }))
    api.listQualityRules.mockRejectedValue(new Error('network unavailable'))
    const wrapper = mountPage(QualityRulesView)
    await settle()

    expect(wrapper.text()).toContain('质量规则读取失败，请重试。')
    expect(wrapper.text()).not.toContain('当前筛选范围没有质量规则')
  })

  it('普通管理员可读全局规则但不能编辑或启用', async () => {
    respond(capability({
      global: ['governance:rule:view'],
      onDatasource: ['metadata:view', 'governance:rule:view', 'governance:rule:manage'],
    }))
    api.listQualityRules.mockResolvedValue({
      data: [globalRule],
    })
    const wrapper = mountPage(QualityRulesView)
    await settle()

    expect(api.listQualityRules).toHaveBeenCalled()
    expect(wrapper.text()).toContain('字段说明规则')
    const toggle = wrapper.find('.el-switch')
    expect(toggle.classes()).toContain('is-disabled')
    await toggle.trigger('click')
    expect(api.updateRuleEnabled).not.toHaveBeenCalled()
  })

  it('受保护系统管理员可进入全局范围并启停全局规则', async () => {
    respond(capability({ global: ['governance:rule:view'], systemAdmin: true }))
    api.listQualityRules.mockResolvedValue({
      data: [globalRule],
    })
    const wrapper = mountPage(QualityRulesView)
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '全局规则')!.trigger('click')
    await settle()
    expect(wrapper.find('.el-switch').classes()).not.toContain('is-disabled')
  })

  it('有数据源规则管理权时复制现有规则，新副本由后端建立为停用状态', async () => {
    respond(capability({ onDatasource: ['governance:rule:view', 'governance:rule:manage'] }))
    api.listQualityRules.mockResolvedValue({ data: [{
      id: 30, ruleCode: 'QR-30', templateCode: 'COMP_COLUMN_COMMENT_MISSING', ruleName: '字段说明',
      dimension: 'COMPLETENESS', severity: 'MEDIUM', checkTarget: 'COLUMN', checkType: 'SCHEMA',
      scopeType: 'DATASOURCE', datasourceId: DATASOURCE_ID, parameters: {}, configVersion: 1,
      enabled: 1, deductionPoints: 2, builtin: 0, supported: true,
    }] })
    api.copyQualityRule.mockResolvedValue({ data: { id: 31, enabled: 0 } })
    const wrapper = mountPage(QualityRulesView)
    await settle()

    const row = await waitForRow(wrapper, '字段说明')
    await row.findAll('button').find((button) => button.text().includes('复制'))!.trigger('click')
    await settle()

    expect(api.copyQualityRule).toHaveBeenCalledWith(30)
    expect(api.listQualityRules).toHaveBeenCalledTimes(3)
    expect(api.updateRuleEnabled).not.toHaveBeenCalled()
  })

  it('新增表单从可执行模板读取参数定义并发送结构化规则配置', async () => {
    respond(capability({ onDatasource: ['metadata:view', 'governance:rule:view', 'governance:rule:manage'] }))
    api.listQualityRuleTemplates.mockResolvedValue({ data: [{
      code: 'DATA_NULL_RATE_HIGH', name: '列空值率过高', dimension: 'COMPLETENESS',
      targetType: 'COLUMN', checkType: 'DATA', description: '样本检查',
      allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'],
      parameters: [{ key: 'threshold', label: '空值率阈值', type: 'NUMBER', minimum: 0, maximum: 1, defaultValue: 0.3 }],
    }] })
    api.createQualityRule.mockResolvedValue({ data: { id: 51 } })
    const wrapper = mountPage(QualityRulesView)
    await settle()
    await wrapper.findAll('button').find((button) => button.text().includes('新增规则'))!.trigger('click')
    await settle()

    const templateSelect = wrapper.findAllComponents(ElSelect)
      .find((select) => select.props('modelValue') === '' && !select.props('placeholder'))
    expect(templateSelect).toBeTruthy()
    await templateSelect!.vm.$emit('update:modelValue', 'DATA_NULL_RATE_HIGH')
    await templateSelect!.vm.$emit('change', 'DATA_NULL_RATE_HIGH')
    await settle()
    expect(document.body.textContent).toContain('空值率阈值')

    const nameInput = document.body.querySelector<HTMLInputElement>('.el-dialog input[maxlength="200"]')
    expect(nameInput).toBeTruthy()
    nameInput!.value = '订单金额空值率'
    nameInput!.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    const saveButton = document.body.querySelector<HTMLButtonElement>('.el-dialog__footer .el-button--primary')
    expect(saveButton).toBeTruthy()
    saveButton!.click()
    await settle()

    expect(api.createQualityRule).toHaveBeenCalledWith(expect.objectContaining({
      ruleName: '订单金额空值率',
      templateCode: 'DATA_NULL_RATE_HIGH',
      scopeType: 'DATASOURCE',
      datasourceId: DATASOURCE_ID,
      parameters: { threshold: 0.3 },
      enabled: true,
    }))
  })

  it('规则保存失败保留表单并显示后端冲突原因', async () => {
    respond(capability({ onDatasource: ['governance:rule:view', 'governance:rule:manage'] }))
    api.createQualityRule.mockRejectedValueOnce({ response: { data: { message: '同一检查模板、同一目标和同一级范围已有启用规则' } } })
    const wrapper = mountPage(QualityRulesView)
    await settle()
    await wrapper.findAll('button').find((button) => button.text().includes('新增规则'))!.trigger('click')
    await settle()
    const templateSelect = wrapper.findAllComponents(ElSelect)
      .find((select) => select.props('modelValue') === '' && !select.props('placeholder'))
    await templateSelect!.vm.$emit('update:modelValue', 'COMP_COLUMN_COMMENT_MISSING')
    await templateSelect!.vm.$emit('change', 'COMP_COLUMN_COMMENT_MISSING')
    await settle()
    const nameInput = document.body.querySelector<HTMLInputElement>('.el-dialog input[maxlength="200"]')
    nameInput!.value = '重复规则'
    nameInput!.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    document.body.querySelector<HTMLButtonElement>('.el-dialog__footer .el-button--primary')!.click()
    await settle()

    expect(api.createQualityRule).toHaveBeenCalled()
    expect(document.body.querySelector('.el-dialog')).toBeTruthy()
    expect(document.body.textContent).toContain('同一检查模板、同一目标和同一级范围已有启用规则')
  })

  it('编辑 A 慢、B 快时保存仍使用 B 的规则 ID、范围和目标', async () => {
    respond(capability({ onDatasource: ['metadata:view', 'governance:rule:view', 'governance:rule:manage'] }))
    api.listQualityRuleTemplates.mockResolvedValue({ data: [{
      code: 'DATA_NULL_RATE_HIGH', name: '列空值率过高', dimension: 'COMPLETENESS',
      targetType: 'COLUMN', checkType: 'DATA', description: '样本检查',
      allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'],
      parameters: [{ key: 'threshold', label: '空值率阈值', type: 'NUMBER', minimum: 0, maximum: 1, defaultValue: 0.3 }],
    }] })
    const ruleA = {
      id: 101, ruleCode: 'QR-A', templateCode: 'DATA_NULL_RATE_HIGH', ruleName: '规则 A',
      dimension: 'COMPLETENESS', severity: 'MEDIUM', description: '', checkTarget: 'COLUMN', checkType: 'DATA',
      scopeType: 'COLUMN', datasourceId: DATASOURCE_ID, targetTableName: 'orders', targetColumnName: 'amount',
      parameters: { threshold: 0.4 }, configVersion: 1, enabled: 0, deductionPoints: 3, builtin: 0, supported: true,
    }
    const ruleB = {
      ...ruleA, id: 102, ruleCode: 'QR-B', ruleName: '规则 B',
      targetTableName: 'customers', targetColumnName: 'customer_id',
    }
    api.listQualityRules.mockResolvedValue({ data: [ruleA, ruleB] })
    api.listSnapshotTables.mockResolvedValue({ data: [{ tableName: 'orders' }, { tableName: 'customers' }] })
    const slowAColumns = deferred<{ data: Array<{ columnName: string }> }>()
    api.listSnapshotTableColumns.mockImplementation((_snapshotId: number, tableName: string) => {
      if (tableName === 'orders') return slowAColumns.promise
      return Promise.resolve({ data: [{ columnName: 'customer_id' }] })
    })
    api.editQualityRule.mockResolvedValue({ data: ruleB })
    const wrapper = mountPage(QualityRulesView)
    await settle()

    const editA = await waitForRowButton(wrapper, '规则 A', '编辑')
    void editA!.trigger('click')
    await vi.waitFor(() => expect(api.listSnapshotTableColumns).toHaveBeenCalledWith(8, 'orders'))
    const editB = await waitForRowButton(wrapper, '规则 B', '编辑')
    void editB!.trigger('click')
    await vi.waitFor(() => expect(api.listSnapshotTableColumns).toHaveBeenCalledWith(8, 'customers'))
    await settle()

    expect(document.body.querySelector<HTMLInputElement>('.el-dialog input[maxlength="200"]')?.value).toBe('规则 B')
    slowAColumns.resolve({ data: [{ columnName: 'amount' }] })
    await settle()
    expect(document.body.querySelector<HTMLInputElement>('.el-dialog input[maxlength="200"]')?.value).toBe('规则 B')

    document.body.querySelector<HTMLButtonElement>('.el-dialog__footer .el-button--primary')!.click()
    await settle()

    expect(api.editQualityRule).toHaveBeenCalledWith(102, expect.objectContaining({
      scopeType: 'COLUMN', datasourceId: DATASOURCE_ID,
      targetTableName: 'customers', targetColumnName: 'customer_id',
    }))
  })

  it('切换到 B 后关闭编辑器，A 的旧字段请求返回不能重新打开弹窗或保存', async () => {
    respond(capability({ onDatasource: ['metadata:view', 'governance:rule:view', 'governance:rule:manage'] }))
    api.listQualityRuleTemplates.mockResolvedValue({ data: [{
      code: 'DATA_NULL_RATE_HIGH', name: '列空值率过高', dimension: 'COMPLETENESS',
      targetType: 'COLUMN', checkType: 'DATA', description: '样本检查',
      allowedScopes: ['GLOBAL', 'DATASOURCE', 'TABLE', 'COLUMN'],
      parameters: [{ key: 'threshold', label: '空值率阈值', type: 'NUMBER', minimum: 0, maximum: 1, defaultValue: 0.3 }],
    }] })
    const ruleA = {
      id: 103, ruleCode: 'QR-C', templateCode: 'DATA_NULL_RATE_HIGH', ruleName: '规则 C',
      dimension: 'COMPLETENESS', severity: 'MEDIUM', description: '', checkTarget: 'COLUMN', checkType: 'DATA',
      scopeType: 'COLUMN', datasourceId: DATASOURCE_ID, targetTableName: 'orders', targetColumnName: 'amount',
      parameters: { threshold: 0.4 }, configVersion: 1, enabled: 0, deductionPoints: 3, builtin: 0, supported: true,
    }
    const ruleB = { ...ruleA, id: 104, ruleCode: 'QR-D', ruleName: '规则 D', targetTableName: 'customers', targetColumnName: 'customer_id' }
    api.listQualityRules.mockResolvedValue({ data: [ruleA, ruleB] })
    api.listSnapshotTables.mockResolvedValue({ data: [{ tableName: 'orders' }, { tableName: 'customers' }] })
    const delayedColumns = deferred<{ data: Array<{ columnName: string }> }>()
    api.listSnapshotTableColumns.mockImplementation((_snapshotId: number, tableName: string) => tableName === 'orders'
      ? delayedColumns.promise
      : Promise.resolve({ data: [{ columnName: 'customer_id' }] }))
    const wrapper = mountPage(QualityRulesView)
    await settle()

    const editA = await waitForRowButton(wrapper, '规则 C', '编辑')
    void editA!.trigger('click')
    await vi.waitFor(() => expect(api.listSnapshotTableColumns).toHaveBeenCalledWith(8, 'orders'))
    const editB = await waitForRowButton(wrapper, '规则 D', '编辑')
    void editB!.trigger('click')
    await vi.waitFor(() => expect(api.listSnapshotTableColumns).toHaveBeenCalledWith(8, 'customers'))
    await settle()

    expect(document.body.querySelector('.el-dialog')).toBeTruthy()
    const openEditor = wrapper.findAllComponents(ElDialog).find((dialog) => dialog.props('modelValue') === true)
    expect(openEditor).toBeTruthy()
    await openEditor!.vm.$emit('update:modelValue', false)
    await settle()
    expect(openEditor!.props('modelValue')).toBe(false)
    delayedColumns.resolve({ data: [{ columnName: 'amount' }] })
    await settle()

    expect(openEditor!.props('modelValue')).toBe(false)
    expect(api.editQualityRule).not.toHaveBeenCalled()
  })

  it('没有 governance:rule:manage 时表状态下拉不可用', async () => {
    respond(capability({ global: [], onDatasource: ['metadata:view'] }))
    api.listSnapshotTables.mockResolvedValue({
      data: [{ id: 1, tableName: 'orders', governanceStatus: 'NORMAL' }],
    })
    const wrapper = mountPage(StatusEditor)
    await settle()

    expect(wrapper.text()).toContain('没有“维护治理规则”能力')
    // 表列表里的状态下拉必须禁用
    const statusSelect = wrapper.findAll('.el-select').find((select) => select.text().includes('正常'))
    expect(statusSelect).toBeTruthy()
    expect(statusSelect!.find('.el-select__wrapper').classes()).toContain('is-disabled')
  })

})

describe('治理总览的批次恢复与范围隔离', () => {
  it('问题统计读取失败时不显示零问题', async () => {
    respond(capability({ onDatasource: [
      'governance:view', 'governance:check', 'governance:issue:view', 'governance:rule:view',
    ] }))
    api.getSnapshotQualityIssueSummary.mockRejectedValue(new Error('network unavailable'))
    const wrapper = mountPage(QualityDashboard)
    await settle()

    expect(wrapper.text()).toContain('待处理问题统计读取失败，无法确认问题数量。')
    expect(wrapper.text()).not.toContain('待处理 0')
  })

  it('快照切换后较晚返回的旧批次不能覆盖当前结果', async () => {
    respond(capability({ onDatasource: [
      'governance:view', 'governance:check', 'governance:issue:view', 'governance:rule:view',
    ] }))
    let resolveOld!: (value: { data: Record<string, unknown> }) => void
    api.getLatestQualityCheckBatch.mockImplementation((snapshotId: number) => {
      if (snapshotId === 8) return new Promise((resolve) => { resolveOld = resolve })
      return Promise.resolve({ data: {
        id: 22, datasourceId: DATASOURCE_ID, snapshotId, requestedScope: 'FULL',
        requestedDimensions: [], requestedTableNames: [], status: 'PARTIAL', complete: false,
        ruleCount: 2, executedRuleCount: 1, failedRuleCount: 1, notApplicableRuleCount: 0,
        rules: [], dimensionScores: {}, startedAt: '2026-10-19T10:00:00',
      } })
    })
    const wrapper = mountPage(QualityDashboard)
    await vi.waitFor(() => expect(api.getLatestQualityCheckBatch).toHaveBeenCalledWith(8))
    const adminContext = useAdminContextStore()
    adminContext.selectSnapshot(9)
    await settle()
    expect(api.getLatestQualityCheckBatch).toHaveBeenCalledWith(9)
    expect(wrapper.text()).toContain('部分失败')

    resolveOld({ data: {
      id: 11, datasourceId: DATASOURCE_ID, snapshotId: 8, requestedScope: 'FULL',
      requestedDimensions: [], requestedTableNames: [], status: 'FAILED', complete: false,
      ruleCount: 1, executedRuleCount: 0, failedRuleCount: 1, notApplicableRuleCount: 0,
      rules: [], dimensionScores: {}, startedAt: '2026-10-18T10:00:00',
    } })
    await settle()
    expect(wrapper.text()).toContain('部分失败')
    expect(wrapper.text()).not.toContain('执行失败')
  })
})

describe('GovernanceFieldsView 的能力控制', () => {
  beforeEach(() => {
    // 这三个 Tab 的后端端点属于尚未迁移的批次，这里只验证本页自有的脱敏候选 Tab。
    routeState.query = { tab: 'mask' }
    api.listMaskCandidates.mockResolvedValue({
      data: [{
        entityId: 11,
        name: 'phone',
        displayName: '手机号',
        fqn: 'db.t.phone',
        pendingMask: { mask_strategy: 'PHONE', tag_fqn: 'PII.PHONE' },
      }],
    })
  })

  it('没有 security:mask:view 时不请求脱敏候选', async () => {
    respond(capability({ global: [], onDatasource: ['metadata:view'] }))
    const wrapper = mountPage(GovernanceFieldsView)
    await settle()

    expect(api.listMaskCandidates).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('security:mask:view')
  })

  it('有查看权无维护权时确认与拒绝都不可用', async () => {
    respond(capability({ onDatasource: ['security:mask:view'] }))
    const wrapper = mountPage(GovernanceFieldsView)
    await settle()

    expect(api.listMaskCandidates).toHaveBeenCalledWith(DATASOURCE_ID)
    const confirm = wrapper.findAll('button').find((button) => button.text().includes('确认生效'))
    expect(confirm).toBeTruthy()
    expect(confirm!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('security:mask:manage')
  })

  it('拥有 security:mask:manage 时确认按钮可用', async () => {
    respond(capability({ onDatasource: ['security:mask:view', 'security:mask:manage'] }))
    const wrapper = mountPage(GovernanceFieldsView)
    await settle()

    const confirm = wrapper.findAll('button').find((button) => button.text().includes('确认生效'))
    expect(confirm!.attributes('disabled')).toBeUndefined()
  })
})

describe('能力摘要读取失败时', () => {
  it('保持无能力，不发任何治理写请求', async () => {
    api.getIamS1Capabilities.mockRejectedValue(new Error('服务不可用'))
    const store = useIamS1Store()
    await store.load()

    expect(store.errorMessage).toContain('服务不可用')
    expect(store.canOnDatasource('governance:issue:manage', DATASOURCE_ID)).toBe(false)
    expect(store.hasGlobal('governance:rule:view')).toBe(false)
  })
})
