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
import { nextTick, reactive } from 'vue'
import ElementPlus, { ElDialog, ElDropdown, ElMessage, ElSelect } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'
import type { QualityIssueItem } from '../../../api/admin/governance'

/** 可变的“当前路由”，让用例能把页面直接挂载在指定 Tab 上。 */
const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown>, push: vi.fn(), replace: vi.fn() }))
const reactiveRouteState = reactive(routeState)

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
  triggerQualityCheck: vi.fn(),
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
  getSnapshotDetail: vi.fn(),
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
  triggerQualityCheck: api.triggerQualityCheck,
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
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots, getSnapshotDetail: api.getSnapshotDetail }))
vi.mock('../../../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))
// 只替换页面用到的那两个组合式函数：`src/api/http.ts` 会在 401 时用真实 router 跳转，
// 整体替换模块会让它拿不到 createRouter。
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => reactiveRouteState,
    useRouter: () => ({ replace: routeState.replace, push: routeState.push }),
  }
})

import IssueList from './IssueList.vue'
import StatusEditor from './StatusEditor.vue'
import QualityRulesView from './QualityRulesView.vue'
import QualityDashboard from './QualityDashboard.vue'
import GovernanceFieldsView from './GovernanceFieldsView.vue'
import ScopeBar from '../../../components/admin/ScopeBar.vue'
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

function mountIssuePage(scope: { datasourceId?: number | null; snapshotId?: number | null } = {}) {
  const selectedDatasourceId = scope.datasourceId === null ? undefined : scope.datasourceId ?? DATASOURCE_ID
  const selectedSnapshotId = scope.snapshotId === null ? undefined : scope.snapshotId ?? 8
  const context = useAdminContextStore()
  context.$patch({
    datasources: selectedDatasourceId ? [{ id: selectedDatasourceId, name: '销售库', databaseName: 'sales' }] : [],
    snapshots: selectedSnapshotId ? [{ id: selectedSnapshotId, datasourceId: selectedDatasourceId, snapshotVersion: 1, status: 'PUBLISHED' }] : [],
    datasourceId: selectedDatasourceId,
    snapshotId: selectedSnapshotId,
    loading: false,
    routeSyncing: false,
    routeSyncError: '',
    initialized: true,
  })
  return mountPage(IssueList)
}

function mountScopeBar() {
  const wrapper = mount(ScopeBar, {
    props: {
      mode: 'datasource-snapshot',
      showControlLabels: true,
      trackRouteSync: true,
      preserveRouteContextOnRefresh: true,
    },
    global: { plugins: [ElementPlus] },
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
  reactiveRouteState.query = {}
  routeState.push.mockReset()
  routeState.replace.mockReset().mockImplementation(({ query }: { query: Record<string, unknown> }) => {
    reactiveRouteState.query = query
  })
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: DATASOURCE_ID, name: '销售库' }] })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 8, snapshotVersion: 1 }], total: 1 } })
  api.getSnapshotDetail.mockRejectedValue(new Error('快照不存在'))
  api.listSnapshotTables.mockResolvedValue({ data: [{ tableName: 'orders', tableComment: '订单表' }] })
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
  api.listSnapshotTables.mockResolvedValue({ data: [{ tableName: 'orders', tableComment: '订单表' }] })
  api.listMaskCandidates.mockResolvedValue({ data: [] })
  api.listReviewRecords.mockResolvedValue({ data: { records: [], total: 0 } })
})

afterEach(() => {
  mountedPages.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('IssueList 的能力控制', () => {
  it('没有处理能力时不渲染任何写操作按钮，只显示原因', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    const wrapper = mountIssuePage()
    await settle()

    // 有查看权、无处理权：不应出现任何会触发写接口的按钮
    expect(wrapper.text()).toContain('无处理权限')
    await waitForRow(wrapper, 'orders')
    expect(rowButton(wrapper, 'orders', '确认')).toBeUndefined()
    expect(rowButton(wrapper, 'orders', '驳回')).toBeUndefined()
    expect(api.handleIssue).not.toHaveBeenCalled()
  })

  it('在负责源上拥有处理能力时才渲染操作按钮', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:issue:manage'] }))
    const wrapper = mountIssuePage()
    await settle()

    expect(wrapper.text()).not.toContain('无处理权限')
    const confirm = await waitForRowButton(wrapper, 'orders', '确认')

    await confirm!.trigger('click')
    await settle()
    // 处理动作按问题 ID 提交，后端会按该问题的真实归属再做一次判定
    expect(api.handleIssue).toHaveBeenCalledWith(1, { status: 'CONFIRMED' })
  })

  it('globalFunctions 有查看码但当前数据源无查看能力时不读取列表', async () => {
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
    const wrapper = mountIssuePage()
    await settle()

    expect(wrapper.text()).toContain('没有查看所选数据源质量问题的权限')
    expect(api.listQualityIssues).not.toHaveBeenCalled()
  })

  it('查看能力只绑定到其他数据源时当前源仍无权', async () => {
    api.getIamS1Capabilities.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        ...capability({}),
        datasourceCapabilities: [{
          datasourceId: 9,
          datasourceName: '财务库',
          functionCodes: ['governance:issue:view', 'governance:issue:manage'],
          functionNames: ['查看质量问题', '处理质量问题'],
        }],
      },
    })
    const wrapper = mountIssuePage()
    await settle()

    expect(wrapper.text()).toContain('没有查看所选数据源质量问题的权限')
    expect(api.listQualityIssues).not.toHaveBeenCalled()
  })

  it('受保护系统管理员通过当前源能力门禁', async () => {
    respond(capability({ systemAdmin: true }))
    const wrapper = mountIssuePage()
    await settle()

    await waitForRow(wrapper, 'orders')
    expect(api.listQualityIssues).toHaveBeenCalledWith(8, expect.any(Object))
  })

  it('没有发布域能力时不请求同表治理记录', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:issue:manage'] }))
    const wrapper = mountIssuePage()
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
      onDatasource: ['governance:issue:view', 'governance:issue:manage', 'metadata:release:view'],
    }))
    const wrapper = mountIssuePage()
    await settle()

    const detail = await waitForRowButton(wrapper, 'orders', '详情')
    await detail.trigger('click')
    await settle()

    expect(api.listReviewRecords).toHaveBeenCalledWith(8, expect.objectContaining({ tableName: 'orders' }))
  })
})

describe('问题中心分派详情会话隔离', () => {
  const issueA = issue
  const issueB = { ...issue, id: 2, tableName: 'customers', issueDescription: '客户记录问题', assigneeId: 1 }

  async function openIssue(wrapper: ReturnType<typeof mountPage>, tableName: string) {
    const detail = await waitForRowButton(wrapper, tableName, '详情')
    await detail.trigger('click')
    await settle()
  }

  async function startAssignment(wrapper: ReturnType<typeof mountPage>, assigneeId: number) {
    const selects = wrapper.findAllComponents(ElSelect)
    const detailAssigneeSelect = selects[selects.length - 1]
    if (!detailAssigneeSelect) throw new Error('详情责任人选择器尚未渲染')
    await detailAssigneeSelect.vm.$emit('update:modelValue', assigneeId)
    await nextTick()
    const submit = wrapper.findAll('button').find((button) => button.text() === '分派')
    if (!submit) throw new Error('详情分派按钮尚未渲染')
    await submit.trigger('click')
    await vi.waitFor(() => expect(api.assignIssue).toHaveBeenCalled())
  }

  async function closeDrawer(wrapper: ReturnType<typeof mountPage>) {
    const drawer = wrapper.findComponent({ name: 'ElDrawer' })
    if (!drawer.exists()) throw new Error('问题详情抽屉尚未渲染')
    await drawer.vm.$emit('update:modelValue', false)
    await settle()
  }

  function currentDetailHeading(wrapper: ReturnType<typeof mountPage>) {
    const heading = wrapper.find('.issue-detail__headline strong')
    return heading.exists() ? heading.text() : undefined
  }

  it('A 的慢分派在关闭 A 并打开 B 后不能覆盖 B 详情', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:issue:manage'] }))
    api.listQualityIssues.mockResolvedValue({ data: { records: [issueA, issueB], total: 2 } })
    api.listUsers.mockResolvedValue({ data: { records: [{ id: 77, realName: '责任人A', status: 1 }], total: 1 } })
    const pendingA = deferred<{ data: null }>()
    api.assignIssue.mockReturnValue(pendingA.promise)
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = mountIssuePage()
    await settle()

    await openIssue(wrapper, 'orders')
    await startAssignment(wrapper, 77)
    expect(api.assignIssue).toHaveBeenCalledWith(1, 77)
    await closeDrawer(wrapper)
    await openIssue(wrapper, 'customers')
    expect(currentDetailHeading(wrapper)).toBe('customers')

    pendingA.resolve({ data: null })
    await settle()
    expect(currentDetailHeading(wrapper)).toBe('customers')
    expect(wrapper.text()).toContain('客户记录问题')
    expect(success).not.toHaveBeenCalled()
    success.mockRestore()
  })

  it('关闭详情后分派响应返回不重新打开抽屉', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:issue:manage'] }))
    api.listQualityIssues.mockResolvedValue({ data: { records: [issueA], total: 1 } })
    api.listUsers.mockResolvedValue({ data: { records: [{ id: 77, realName: '责任人A', status: 1 }], total: 1 } })
    const pending = deferred<{ data: null }>()
    api.assignIssue.mockReturnValue(pending.promise)
    const wrapper = mountIssuePage()
    await settle()

    await openIssue(wrapper, 'orders')
    await startAssignment(wrapper, 77)
    await closeDrawer(wrapper)
    expect(currentDetailHeading(wrapper)).toBeUndefined()

    pending.resolve({ data: null })
    await settle()
    expect(currentDetailHeading(wrapper)).toBeUndefined()
    expect(wrapper.text()).not.toContain('分派失败')
  })

  it('切换数据源或快照期间的分派响应不刷新新范围', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:issue:manage'] }))
    api.listQualityIssues.mockResolvedValue({ data: { records: [issueA], total: 1 } })
    api.listUsers.mockResolvedValue({ data: { records: [{ id: 77, realName: '责任人A', status: 1 }], total: 1 } })
    const pending = deferred<{ data: null }>()
    api.assignIssue.mockReturnValue(pending.promise)
    const wrapper = mountIssuePage()
    await settle()

    await openIssue(wrapper, 'orders')
    await startAssignment(wrapper, 77)
    reactiveRouteState.query = { datasourceId: '5', snapshotId: '9' }
    useAdminContextStore().$patch({
      snapshots: [{ id: 9, datasourceId: 5, snapshotVersion: 2, status: 'PUBLISHED' }],
      snapshotId: 9,
    })
    await vi.waitFor(() => expect(api.listQualityIssues).toHaveBeenLastCalledWith(9, expect.any(Object)))
    const requestsAtNewScope = api.listQualityIssues.mock.calls.length

    pending.resolve({ data: null })
    await settle()
    expect(api.listQualityIssues).toHaveBeenCalledTimes(requestsAtNewScope)
    expect(currentDetailHeading(wrapper)).toBeUndefined()
  })

  it('旧分派 finally 不会清除 B 的新分派加载状态', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:issue:manage'] }))
    api.listQualityIssues.mockResolvedValue({ data: { records: [issueA, issueB], total: 2 } })
    api.listUsers.mockResolvedValue({ data: { records: [
      { id: 77, realName: '责任人A', status: 1 }, { id: 88, realName: '责任人B', status: 1 },
    ], total: 2 } })
    const pendingA = deferred<{ data: null }>()
    const pendingB = deferred<{ data: null }>()
    api.assignIssue.mockImplementation((issueId: number) => issueId === 1 ? pendingA.promise : pendingB.promise)
    const wrapper = mountIssuePage()
    await settle()

    await openIssue(wrapper, 'orders')
    await startAssignment(wrapper, 77)
    await closeDrawer(wrapper)
    await openIssue(wrapper, 'customers')
    await startAssignment(wrapper, 88)
    const submitB = wrapper.findAll('button').find((button) => button.text() === '分派')!
    expect(submitB.attributes('disabled')).toBeDefined()

    pendingA.resolve({ data: null })
    await settle()
    expect(currentDetailHeading(wrapper)).toBe('customers')
    expect(submitB.attributes('disabled')).toBeDefined()

    pendingB.resolve({ data: null })
    await settle()
    expect(currentDetailHeading(wrapper)).toBe('customers')
  })
})

describe('问题中心的范围、筛选和结果表达', () => {
  it('范围由公共上下文提供，页面只有问题筛选且刷新进入标题操作区', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    const wrapper = mountIssuePage()
    await settle()

    const filters = wrapper.find('.issue-workbench__filters')
    expect(filters.findAllComponents(ElSelect)).toHaveLength(6)
    expect(filters.findAll('input').map((input) => input.attributes('aria-label'))).toEqual([
      '按问题域筛选', '按表筛选', '按维度筛选', '按级别筛选', '按状态筛选', '按责任人筛选',
    ])
    expect(filters.text()).not.toContain('数据源')
    expect(filters.text()).not.toContain('快照')
    expect(filters.text()).toContain('分配给我的')
    expect(filters.text()).toContain('清除筛选')
    expect(wrapper.find('.page-actions').exists()).toBe(false)
    expect(wrapper.find('.task-page-header__actions').text()).toContain('刷新问题')
    expect(api.listSimpleDatasources).not.toHaveBeenCalled()
    expect(api.listSnapshots).not.toHaveBeenCalled()
    expect(api.listQualityIssues).toHaveBeenCalledWith(8, expect.objectContaining({ page: 1, size: 20, assigneeId: undefined }))
  })

  it('按 SOURCE / SEMANTIC 问题域把筛选下推到后端，并展示后端分域总数', async () => {
    reactiveRouteState.query = { issueDomain: 'SEMANTIC' }
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    api.getSnapshotQualityIssueSummary.mockResolvedValue({ data: {
      totalUnresolved: 9, statusCounts: { OPEN: 7, CONFIRMED: 1, REOPENED: 1 },
      domainCounts: { SOURCE: 6, SEMANTIC: 3 }, recentIssues: [],
    } })
    api.listQualityIssues.mockResolvedValue({ data: { records: [{ ...issue, issueDomain: 'SEMANTIC' }], total: 3 } })
    const wrapper = mountIssuePage()
    await settle()
    expect(api.listQualityIssues).toHaveBeenCalledWith(8, expect.objectContaining({ issueDomain: 'SEMANTIC' }))
    expect(wrapper.text()).toContain('来源质量 6')
    expect(wrapper.text()).toContain('字段解释 3')
  })

  it('显示接口 total 而非当前页记录数，也不显示当前页分类汇总', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    api.getSnapshotQualityIssueSummary.mockResolvedValue({ data: {
      totalUnresolved: 23, statusCounts: { OPEN: 20, CONFIRMED: 2, REOPENED: 1 },
      domainCounts: { SOURCE: 20, SEMANTIC: 3 }, recentIssues: [],
    } })
    api.listQualityIssues.mockResolvedValue({ data: { records: [issue], total: 37 } })
    const wrapper = mountIssuePage()
    await settle()

    expect(wrapper.text().replace(/\s/g, '')).toContain('筛选结果共37条')
    expect(wrapper.text()).toContain('全部 23')
    expect(wrapper.text()).toContain('字段解释 3')
    expect(wrapper.text()).not.toContain('高危 1')
    expect(wrapper.text()).not.toContain('待处理 1')
    expect(wrapper.text()).not.toContain('Total 0')
    expect(wrapper.find('.issue-workbench__pagination').exists()).toBe(true)

    await wrapper.find('.btn-next').trigger('click')
    await settle()
    expect(api.listQualityIssues).toHaveBeenLastCalledWith(8, expect.objectContaining({ page: 2, size: 20 }))
  })

  it('清除问题筛选会回到第一页并保留数据源和快照上下文', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    reactiveRouteState.query = {
      datasourceId: '5', snapshotId: '8', tableName: 'orders', dimension: 'COMPLETENESS',
      severity: 'HIGH', status: 'OPEN', assigneeId: '7', page: '3',
    }
    const wrapper = mountIssuePage()
    await settle()
    expect(api.listQualityIssues).toHaveBeenLastCalledWith(8, expect.objectContaining({
      tableName: 'orders', dimension: 'COMPLETENESS', severity: 'HIGH', status: 'OPEN', assigneeId: 7, page: 3,
    }))

    const clear = wrapper.findAll('button').find((button) => button.text() === '清除筛选')
    expect(clear).toBeTruthy()
    await clear!.trigger('click')
    await settle()

    expect(useAdminContextStore().datasourceId).toBe(5)
    expect(useAdminContextStore().snapshotId).toBe(8)
    expect(api.listQualityIssues).toHaveBeenLastCalledWith(8, expect.objectContaining({
      tableName: undefined, dimension: undefined, severity: undefined, status: undefined, assigneeId: undefined, page: 1,
    }))
    expect(routeState.replace).toHaveBeenLastCalledWith({ query: { datasourceId: '5', snapshotId: '8' } })
  })

  it('切换公共快照后使用新范围并清理旧选择', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    const wrapper = mountIssuePage()
    await settle()
    const table = wrapper.findComponent({ name: 'ElTable' })
    await table.vm.$emit('selection-change', [issue])
    await nextTick()
    expect(wrapper.text()).toContain('已选 1 项')

    reactiveRouteState.query = { datasourceId: '5', snapshotId: '9' }
    const context = useAdminContextStore()
    context.$patch({ snapshots: [{ id: 9, datasourceId: 5, snapshotVersion: 2, status: 'PUBLISHED' }], snapshotId: 9 })
    await settle()

    expect(api.listQualityIssues).toHaveBeenLastCalledWith(9, expect.objectContaining({ page: 1 }))
    expect(wrapper.text()).not.toContain('已选 1 项')
  })

  it('未选择有效数据源和快照时不请求问题列表', async () => {
    respond(capability({ onDatasource: [] }))
    const noScope = mountIssuePage({ datasourceId: null, snapshotId: null })
    await settle()
    expect(noScope.text()).toContain('请在顶部范围栏选择有效的数据源和快照')
    expect(api.listQualityIssues).not.toHaveBeenCalled()
  })

  it('没有当前数据源查看权时不请求列表或伪造零结果', async () => {
    respond(capability({ onDatasource: [] }))
    const noAccess = mountIssuePage()
    await settle()
    expect(noAccess.text()).toContain('没有查看所选数据源质量问题的权限')
    expect(noAccess.text()).not.toContain('筛选结果共')
    expect(api.listQualityIssues).not.toHaveBeenCalled()
  })

  it('读取失败保留错误与重试，不显示零结果', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    api.listQualityIssues.mockRejectedValueOnce(new Error('network unavailable'))
    const failed = mountIssuePage()
    await settle()
    expect(failed.text()).toContain('读取失败，未取得筛选结果总数')
    expect(failed.text()).not.toContain('筛选结果共 0 条')
    const retry = failed.find('button.error-retry')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    await settle()
    expect(api.listQualityIssues).toHaveBeenCalledTimes(2)
  })

  it('成功读取到零记录时显示准确空状态且隐藏分页', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    api.listQualityIssues.mockResolvedValue({ data: { records: [], total: 0 } })
    const noRecords = mountIssuePage()
    await settle()
    expect(noRecords.text()).toContain('当前快照暂无问题记录')
    expect(noRecords.text()).not.toContain('当前快照已通过质量校验')
    expect(noRecords.find('.issue-workbench__pagination').exists()).toBe(false)
  })

  it('业务筛选无匹配时提供清除筛选入口', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    api.listQualityIssues.mockResolvedValue({ data: { records: [], total: 0 } })
    reactiveRouteState.query = { dimension: 'ACCURACY' }
    const noMatches = mountIssuePage()
    await settle()
    expect(noMatches.text()).toContain('没有符合筛选条件的问题')
    expect(noMatches.find('button.empty-action').text()).toBe('清除筛选')
  })

  it('加载中不闪现空态；完整读取后真实零值仍显示', async () => {
    respond(capability({ onDatasource: ['governance:issue:view'] }))
    const pending = deferred<{ data: { records: QualityIssueItem[]; total: number } }>()
    api.listQualityIssues.mockReturnValue(pending.promise)
    const wrapper = mountIssuePage()
    await vi.waitFor(() => expect(api.listQualityIssues).toHaveBeenCalled())
    expect(wrapper.text()).toContain('正在读取问题记录')
    expect(wrapper.text()).not.toContain('当前快照暂无问题记录')
    expect(wrapper.text()).not.toContain('筛选结果共')

    pending.resolve({ data: { records: [], total: 0 } })
    await settle()
    expect(wrapper.text().replace(/\s/g, '')).toContain('筛选结果共0条')
    expect(wrapper.text()).toContain('当前快照暂无问题记录')
  })

  it('只允许选择当前范围快照；刷新不会把已恢复的历史快照替换成最新快照', async () => {
    const context = useAdminContextStore()
    context.$patch({
      datasources: [{ id: 5, name: '销售库', databaseName: 'sales' }],
      snapshots: [{ id: 100, datasourceId: 5, snapshotVersion: 9, status: 'PUBLISHED' }],
      datasourceId: 5, snapshotId: 100, initialized: true, loading: false,
    })
    reactiveRouteState.query = { datasourceId: '5', snapshotId: '8' }
    api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 100, datasourceId: 5, snapshotVersion: 9, status: 'PUBLISHED' }], total: 1 } })
    api.getSnapshotDetail.mockResolvedValue({ data: { datasourceName: '销售库', snapshot: { id: 8, datasourceId: 5, snapshotVersion: 1, status: 'PUBLISHED' } } })
    const scope = mountScopeBar()
    await settle()
    expect(context.snapshotId).toBe(8)
    expect(api.getSnapshotDetail).toHaveBeenCalledWith(8)
    expect(scope.find('button[aria-label="刷新范围"]').exists()).toBe(true)

    await scope.find('button[aria-label="刷新范围"]').trigger('click')
    await settle()
    expect(context.snapshotId).toBe(8)
    expect(context.routeSyncError).toBe('')
    expect(api.getSnapshotDetail).toHaveBeenCalledTimes(2)
  })

  it('URL 指定的不可用快照显示错误且不回落到另一个快照', async () => {
    const context = useAdminContextStore()
    context.$patch({
      datasources: [{ id: 5, name: '销售库', databaseName: 'sales' }],
      snapshots: [{ id: 100, datasourceId: 5, snapshotVersion: 9, status: 'PUBLISHED' }],
      datasourceId: 5, snapshotId: 100, initialized: true, loading: false,
    })
    reactiveRouteState.query = { datasourceId: '5', snapshotId: '8' }
    api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 100, datasourceId: 5, snapshotVersion: 9, status: 'PUBLISHED' }], total: 1 } })
    api.getSnapshotDetail.mockRejectedValue(new Error('快照不存在'))
    const scope = mountScopeBar()
    await settle()

    expect(context.snapshotId).toBeUndefined()
    expect(context.routeSyncError).toContain('未切换到其他快照')
    expect(scope.text()).toContain('指定的快照无法加载')
  })

  it('批量部分成功保留跳过原因，且无选中项时不显示批量动作', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:issue:manage'] }))
    const secondIssue = { ...issue, id: 2, status: 'CONFIRMED' }
    api.listQualityIssues.mockResolvedValue({ data: { records: [issue, secondIssue], total: 2 } })
    api.batchHandleIssues.mockResolvedValue({ data: {
      updated: 1, skipped: 1, skippedIssues: [{ id: 2, reason: '当前状态不允许流转' }],
    } })
    const warning = vi.spyOn(ElMessage, 'warning')
    const wrapper = mountIssuePage()
    await settle()
    expect(wrapper.text()).not.toContain('批量确认')

    await wrapper.findComponent({ name: 'ElTable' }).vm.$emit('selection-change', [issue, secondIssue])
    await nextTick()
    const confirm = wrapper.findAll('button').find((button) => button.text() === '批量确认')
    expect(confirm).toBeTruthy()
    expect(confirm!.attributes('disabled')).toBeUndefined()
    await confirm!.trigger('click')
    await settle()

    expect(api.batchHandleIssues).toHaveBeenCalledWith({ issueIds: [1, 2], status: 'CONFIRMED' })
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('当前状态不允许流转'))
    expect(wrapper.text()).not.toContain('已选 2 项')
    warning.mockRestore()
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
    row.findComponent(ElDropdown).vm.$emit('command', 'copy')
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
  const overviewPermissions = ['governance:view', 'governance:check', 'governance:issue:view', 'governance:rule:view']

  it.each([100, 0])('没有批次时将历史评分 %i 分与快照状态放在次要上下文中', async (qualityScore) => {
    respond(capability({ onDatasource: overviewPermissions }))
    api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 8, snapshotVersion: 1, qualityScore, status: 'PUBLISHED' }], total: 1 } })
    api.getQualityRuleSummary.mockResolvedValue({ data: {
      enabledCount: 15, applicableCount: 14, unsupportedCount: 0, overriddenCount: 1, executedCount: 0,
    } })
    const wrapper = mountPage(QualityDashboard)
    await settle()

    const text = wrapper.text().replace(/\s/g, '')
    expect(text).toContain('尚无可核验的校验批次')
    expect(text).toContain('已有历史评分记录，尚无法确认对应的规则覆盖。')
    const scopeContext = wrapper.find('.quality-page__scope-context').text().replace(/\s/g, '')
    expect(scopeContext).toContain('快照v1')
    expect(scopeContext).toContain('已发布')
    expect(scopeContext).toContain(`历史评分${qualityScore}分`)
    expect(wrapper.find('[aria-label="评分与批次信息"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="最近批次执行情况"]').exists()).toBe(false)
    expect(text).not.toContain('校验明细')
    expect(text).not.toContain('总数来自服务端统计')
    expect(text).not.toContain('0项实际执行')
    expect(text).toContain('当前无待处理问题记录')
  })

  it('没有选择快照时给出范围提示并且不请求快照统计', async () => {
    respond(capability({ onDatasource: overviewPermissions }))
    api.listSnapshots.mockResolvedValue({ data: { records: [], total: 0 } })
    const wrapper = mountPage(QualityDashboard)
    await settle()

    expect(wrapper.text()).toContain('等待选择校验范围')
    expect(wrapper.text()).toContain('选择数据源和快照后查看问题。')
    expect(api.getLatestQualityCheckBatch).not.toHaveBeenCalled()
    expect(api.getSnapshotQualityIssueSummary).not.toHaveBeenCalled()
    expect(api.getQualityRuleSummary).not.toHaveBeenCalled()
  })

  it('批次仍在读取时显示加载状态，不提前宣称没有批次', async () => {
    respond(capability({ onDatasource: overviewPermissions }))
    const pendingBatch = deferred<{ data: null }>()
    api.getLatestQualityCheckBatch.mockReturnValue(pendingBatch.promise)
    const wrapper = mountPage(QualityDashboard)
    await vi.waitFor(() => expect(api.getLatestQualityCheckBatch).toHaveBeenCalledWith(8))

    expect(wrapper.text()).toContain('正在读取校验状态')
    expect(wrapper.text()).not.toContain('尚无可核验的校验批次')
    pendingBatch.resolve({ data: null })
    await settle()
    expect(wrapper.text()).toContain('尚无可核验的校验批次')
    expect(wrapper.text()).not.toContain('历史评分')
    expect(wrapper.find('[aria-label="评分与批次信息"]').exists()).toBe(false)
  })

  it('没有治理总览查看权限时显示无权状态而不是无批次', async () => {
    respond(capability({ onDatasource: ['governance:issue:view', 'governance:rule:view'] }))
    const wrapper = mountPage(QualityDashboard)
    await settle()

    expect(wrapper.text()).toContain('无权查看校验状态')
    expect(wrapper.text()).not.toContain('尚无可核验的校验批次')
    expect(api.getLatestQualityCheckBatch).not.toHaveBeenCalled()
  })

  it('批次读取失败与无批次分开显示，且不丢历史评分', async () => {
    respond(capability({ onDatasource: overviewPermissions }))
    api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 8, snapshotVersion: 1, qualityScore: 100 }], total: 1 } })
    api.getLatestQualityCheckBatch.mockRejectedValue(new Error('network unavailable'))
    const wrapper = mountPage(QualityDashboard)
    await settle()

    expect(wrapper.text()).toContain('校验状态读取失败')
    expect(wrapper.text()).toContain('最近一次校验结果读取失败。')
    expect(wrapper.text()).toContain('历史评分')
    expect(wrapper.text()).toContain('100分')
    expect(wrapper.text()).not.toContain('尚无可核验的校验批次')
    expect(wrapper.text()).toContain('读取失败，无法核对实际执行数。')
  })

  it('批次中真实执行数为零时保留零，并直接展示部分失败与覆盖原因', async () => {
    respond(capability({ onDatasource: overviewPermissions }))
    api.getLatestQualityCheckBatch.mockResolvedValue({ data: {
      id: 24, datasourceId: DATASOURCE_ID, snapshotId: 8, requestedScope: 'FULL',
      requestedDimensions: [], requestedTableNames: [], status: 'PARTIAL', complete: false,
      ruleCount: 1, executedRuleCount: 0, failedRuleCount: 1, notApplicableRuleCount: 0,
      errorSummary: '时效字段被阻断，未执行该规则。', rules: [{
        batchId: 24, ruleId: 77, ruleName: '订单时效检查', dimension: 'TIMELINESS', severity: 'HIGH',
        deductionPoints: 5, scopeType: 'TABLE', targetTableName: 'orders', parameters: {},
        status: 'FAILURE', reason: 'updated_at 字段处于 BLOCKED 状态。', targetCount: 1,
        checkedTargetCount: 0, issueCount: 0, ruleVersion: 2,
      }],
      dimensionScores: {}, startedAt: '2026-10-20T10:00:00',
    } })
    const wrapper = mountPage(QualityDashboard)
    await settle()

    expect(wrapper.text()).toContain('校验部分完成')
    expect(wrapper.text().replace(/\s/g, '')).toContain('0项实际执行')
    expect(wrapper.text()).toContain('时效字段被阻断，未执行该规则。')
    const details = wrapper.find('details')
    expect(details.exists()).toBe(true)
    ;(details.element as HTMLDetailsElement).open = true
    await nextTick()
    expect(wrapper.text()).toContain('updated_at 字段处于 BLOCKED 状态。')
    expect(wrapper.text()).not.toContain('校验已完整完成')
  })

  it.each([
    { status: 'RUNNING', complete: false, legacyLimited: false, label: '正在执行质量校验' },
    { status: 'SUCCESS', complete: true, legacyLimited: false, label: '校验已完整完成' },
    { status: 'FAILED', complete: false, legacyLimited: false, label: '校验未完成' },
    { status: 'LEGACY', complete: false, legacyLimited: true, label: '历史结果覆盖有限' },
  ])('$status 状态以独立的批次证据表达', async ({ status, complete, legacyLimited, label }) => {
    respond(capability({ onDatasource: overviewPermissions }))
    api.getLatestQualityCheckBatch.mockResolvedValue({ data: {
      id: 25, datasourceId: DATASOURCE_ID, snapshotId: 8, requestedScope: 'FULL',
      requestedDimensions: [], requestedTableNames: [], status, complete, legacyLimited,
      ruleCount: legacyLimited ? null : 1, executedRuleCount: legacyLimited ? null : 1,
      failedRuleCount: legacyLimited ? null : 0, notApplicableRuleCount: legacyLimited ? null : 0,
      rules: [], dimensionScores: {}, startedAt: '2026-10-20T10:00:00',
    } })
    const wrapper = mountPage(QualityDashboard)
    await settle()

    expect(wrapper.text()).toContain(label)
    if (legacyLimited) expect(wrapper.text()).toContain('实际执行数不可核验')
  })

  it('保留校验动作和问题中心的当前范围跳转', async () => {
    respond(capability({ onDatasource: overviewPermissions }))
    api.triggerQualityCheck.mockResolvedValue({ data: {
      snapshotId: 8, status: 'SUCCESS', complete: true, requestedScope: 'FULL', qualityScore: 91,
      snapshotStatus: 'APPROVED', dimensionScores: {}, totalIssues: 0, issueCount: {},
      executedRuleCount: 1, failedRuleCount: 0, notApplicableRuleCount: 0,
    } })
    const wrapper = mountPage(QualityDashboard)
    await settle()

    const runButton = wrapper.findAll('button').find((button) => button.text().includes('执行质量校验'))
    expect(runButton).toBeTruthy()
    await runButton!.trigger('click')
    await settle()
    expect(api.triggerQualityCheck).toHaveBeenCalledWith(8)

    const issueCenterButton = wrapper.findAll('button').find((button) => button.text().includes('问题中心'))
    expect(issueCenterButton).toBeTruthy()
    await issueCenterButton!.trigger('click')
    expect(routeState.push).toHaveBeenCalledWith(expect.objectContaining({
      path: '/admin/governance/issues',
      query: expect.objectContaining({ datasourceId: '5', snapshotId: '8' }),
    }))
  })

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
    expect(wrapper.text()).toContain('校验部分完成')

    resolveOld({ data: {
      id: 11, datasourceId: DATASOURCE_ID, snapshotId: 8, requestedScope: 'FULL',
      requestedDimensions: [], requestedTableNames: [], status: 'FAILED', complete: false,
      ruleCount: 1, executedRuleCount: 0, failedRuleCount: 1, notApplicableRuleCount: 0,
      rules: [], dimensionScores: {}, startedAt: '2026-10-18T10:00:00',
    } })
    await settle()
    expect(wrapper.text()).toContain('校验部分完成')
    expect(wrapper.text()).not.toContain('校验未完成')
  })
})

describe('GovernanceFieldsView 的能力控制', () => {
  beforeEach(() => {
    // 这三个 Tab 的后端端点属于尚未迁移的批次，这里只验证本页自有的脱敏候选 Tab。
    reactiveRouteState.query = { tab: 'mask' }
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
