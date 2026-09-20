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
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
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

vi.mock('../../../api/iamS1', () => ({ getIamS1Capabilities: api.getIamS1Capabilities }))
vi.mock('../../../api/admin/governance', () => ({
  listQualityIssues: api.listQualityIssues,
  handleIssue: api.handleIssue,
  batchHandleIssues: api.batchHandleIssues,
  assignIssue: api.assignIssue,
  listReviewRecords: api.listReviewRecords,
  listQualityRules: api.listQualityRules,
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
import GovernanceFieldsView from './GovernanceFieldsView.vue'
import { useIamS1Store } from '../../../stores/iamS1'

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
  return mount(component as never, {
    global: {
      plugins: [ElementPlus],
      stubs: { RouterLink: { template: '<a><slot /></a>' } },
    },
  })
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
  api.listSnapshotTables.mockResolvedValue({ data: [] })
  api.listMaskCandidates.mockResolvedValue({ data: [] })
  api.listReviewRecords.mockResolvedValue({ data: { records: [], total: 0 } })
})

describe('IssueList 的能力控制', () => {
  it('没有处理能力时不渲染任何写操作按钮，只显示原因', async () => {
    respond(capability({ global: ['governance:issue:view'] }))
    const wrapper = mountPage(IssueList)
    await settle()

    // 有查看权、无处理权：不应出现任何会触发写接口的按钮
    expect(wrapper.text()).toContain('无处理权限')
    expect(rowButton(wrapper, 'orders', '确认')).toBeUndefined()
    expect(rowButton(wrapper, 'orders', '驳回')).toBeUndefined()
    expect(api.handleIssue).not.toHaveBeenCalled()
  })

  it('在负责源上拥有处理能力时才渲染操作按钮', async () => {
    respond(capability({ global: ['governance:issue:view'], onDatasource: ['governance:issue:manage'] }))
    const wrapper = mountPage(IssueList)
    await settle()

    expect(wrapper.text()).not.toContain('无处理权限')
    const confirm = rowButton(wrapper, 'orders', '确认')
    expect(confirm).toBeTruthy()

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

    expect(wrapper.text()).toContain('无处理权限')
    expect(rowButton(wrapper, 'orders', '确认')).toBeUndefined()
  })

  it('没有发布域能力时不请求同表治理记录', async () => {
    respond(capability({ global: ['governance:issue:view'], onDatasource: ['governance:issue:manage'] }))
    const wrapper = mountPage(IssueList)
    await settle()

    await rowButton(wrapper, 'orders', '详情')!.trigger('click')
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

    await rowButton(wrapper, 'orders', '详情')!.trigger('click')
    await settle()

    expect(api.listReviewRecords).toHaveBeenCalledWith(8, expect.objectContaining({ tableName: 'orders' }))
  })
})

describe('StatusEditor 的能力控制', () => {
  it('没有 governance:rule:view 时不请求规则列表', async () => {
    respond(capability({ global: [] }))
    const wrapper = mountPage(StatusEditor)
    await settle()

    expect(api.listQualityRules).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('governance:rule:view')
  })

  it('规则是全局定义：非系统管理员只能查看，开关不可用', async () => {
    respond(capability({ global: ['governance:rule:view'], onDatasource: ['metadata:view', 'governance:rule:manage'] }))
    api.listQualityRules.mockResolvedValue({
      data: [{ id: 3, ruleName: '空值率', dimension: 'COMPLETENESS', severity: 'HIGH', deductionPoints: 5, description: '', enabled: 1 }],
    })
    const wrapper = mountPage(StatusEditor)
    await settle()

    expect(api.listQualityRules).toHaveBeenCalled()
    // 全局规则启停只允许受保护系统管理员：非管理员即使在某负责源上有 governance:rule:manage 也不能改
    expect(wrapper.text()).toContain('只能由受保护的系统管理员执行')
    await wrapper.find('.el-switch').trigger('click')
    expect(api.updateRuleEnabled).not.toHaveBeenCalled()
  })

  it('系统管理员可以启停全局规则', async () => {
    respond(capability({ global: ['governance:rule:view'], systemAdmin: true }))
    api.listQualityRules.mockResolvedValue({
      data: [{ id: 3, ruleName: '空值率', dimension: 'COMPLETENESS', severity: 'HIGH', deductionPoints: 5, description: '', enabled: 1 }],
    })
    const wrapper = mountPage(StatusEditor)
    await settle()

    expect(wrapper.text()).not.toContain('只能由受保护的系统管理员执行')
    expect(wrapper.find('.el-switch').attributes('disabled')).toBeUndefined()
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

  it('没有 metadata:view 时不自行请求快照列表', async () => {
    respond(capability({ onDatasource: ['governance:rule:manage'] }))
    const wrapper = mountPage(StatusEditor)
    await settle()

    // adminContext.initialize() 自己会取一次快照列表；页面在无能力时不得再补一次
    expect(api.listSnapshots).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('metadata:view')
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
