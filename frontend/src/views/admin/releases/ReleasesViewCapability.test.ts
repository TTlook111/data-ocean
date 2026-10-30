// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import ElementPlus, { ElCheckbox, ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'
import { useAdminContextStore } from '../../../stores/adminContext'

const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))
const routerState = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }))

const api = vi.hoisted(() => ({
  getIamS1Capabilities: vi.fn(),
  getSnapshotDetail: vi.fn(),
  listVersionHistory: vi.fn(),
  getPublishedSnapshot: vi.fn(),
  listSnapshotAuditLogs: vi.fn(),
  listQualityIssues: vi.fn(),
  triggerQualityCheck: vi.fn(),
  changeSnapshotStatus: vi.fn(),
  publishSnapshot: vi.fn(),
  revokeSnapshot: vi.fn(),
  getReleaseGate: vi.fn(),
  listSimpleDatasources: vi.fn(),
  listSnapshots: vi.fn(),
  listKnowledgeDocs: vi.fn(),
}))

vi.mock('../../../api/iamS1', () => ({ getIamS1Capabilities: api.getIamS1Capabilities }))
vi.mock('../../../api/admin/versioning', () => ({
  listVersionHistory: api.listVersionHistory,
  getPublishedSnapshot: api.getPublishedSnapshot,
  listSnapshotAuditLogs: api.listSnapshotAuditLogs,
  changeSnapshotStatus: api.changeSnapshotStatus,
  compareSnapshots: vi.fn(),
  publishSnapshot: api.publishSnapshot,
  revokeSnapshot: api.revokeSnapshot,
  getReleaseGate: api.getReleaseGate,
}))
vi.mock('../../../api/admin/governance', () => ({
  triggerQualityCheck: api.triggerQualityCheck,
  listQualityIssues: api.listQualityIssues,
}))
vi.mock('../../../api/admin/metadata', () => ({
  listSnapshots: api.listSnapshots,
  getSnapshotDetail: api.getSnapshotDetail,
}))
vi.mock('../../../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('../../../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => reactiveRouteState,
    useRouter: () => routerState,
  }
})

import ReleasesView from './ReleasesView.vue'
import { useAuthStore } from '../../../stores/auth'
import { useIamS1Store } from '../../../stores/iamS1'
import { pendingReleaseWrites } from '../../../composables/releaseWriteTracker'
const reactiveRouteState=reactive(routeState)

const DATASOURCE_ID = 5

function capability(functionCodes: string[], datasourceId = DATASOURCE_ID): IamS1CapabilitySnapshot {
  return {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 7,
    systemAdmin: false,
    globalFunctions: [],
    datasourceCapabilities: [
      { datasourceId, datasourceName: datasourceId === DATASOURCE_ID ? '销售库' : '财务库', functionCodes, functionNames: functionCodes },
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

function version(snapshotId: number, snapshotVersion: number, status = 'DRAFT', datasourceId = DATASOURCE_ID) {
  return {
    snapshotId,
    datasourceId,
    datasourceName: datasourceId === DATASOURCE_ID ? '销售库' : '财务库',
    snapshotVersion,
    status,
    qualityScore: 88,
    tableCount: 3,
    columnCount: 12,
    createdAt: '2026-09-20T10:00:00',
    reviewedBy: '审校用户',
  }
}

function snapshotDetail(snapshotId: number, snapshotVersion: number, datasourceId = DATASOURCE_ID) {
  const tableId = snapshotId * 10
  return {
    snapshot: {
      id: snapshotId,
      snapshotVersion,
      datasourceId,
      datasourceName: datasourceId === DATASOURCE_ID ? '销售库' : '财务库',
      tableCount: 1,
      columnCount: 2,
      qualityScore: 92,
      status: 'PUBLISHED',
      createdAt: '2026-09-20T10:00:00',
    },
    datasourceName: datasourceId === DATASOURCE_ID ? '销售库' : '财务库',
    tables: [{ id: tableId, tableName: `orders_${snapshotVersion}`, tableComment: '订单表', tableType: 'TABLE', rowCountEstimate: 3, governanceStatus: 'NORMAL' }],
    columns: [{
      id: tableId + 1,
      tableMetaId: tableId,
      tableName: `orders_${snapshotVersion}`,
      columnName: 'order_id',
      columnComment: '订单编号',
      dataType: 'BIGINT',
      isNullable: 0,
      isPrimaryKey: 1,
      ordinalPosition: 1,
      governanceStatus: 'NORMAL',
    }],
  }
}

function releaseGate(snapshotId: number, overrides: Record<string, unknown> = {}) {
  return {
    snapshotId,
    datasourceId: DATASOURCE_ID,
    snapshotStatus: 'APPROVED',
    approvable: true,
    publishable: true,
    blockingCount: 0,
    warningCount: 0,
    unresolvedSourceHigh: 0,
    unresolvedSemanticHigh: 0,
    validTableCount: 2,
    evaluatedAt: '2026-10-22T12:00:00',
    items: [
      { code: 'SOURCE_HIGH_UNRESOLVED', category: 'SOURCE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的来源高风险问题' },
      { code: 'NO_NORMAL_RECOMMENDED_TABLE', category: 'STRUCTURE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 2, message: '存在 2 张 NORMAL/RECOMMENDED 状态的表' },
      { code: 'SEMANTIC_HIGH_UNRESOLVED', category: 'SEMANTIC', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的语义高风险问题' },
    ],
    ...overrides,
  }
}

function blockedReleaseGate(snapshotId: number) {
  return releaseGate(snapshotId, {
    approvable: false,
    publishable: false,
    blockingCount: 1,
    unresolvedSourceHigh: 2,
    items: [
      { code: 'SOURCE_HIGH_UNRESOLVED', category: 'SOURCE', severity: 'HIGH', status: 'FAIL', blocksApproval: true, blocksPublish: true, count: 2, message: '无法发布：存在 2 个未解决的高风险问题' },
      { code: 'NO_NORMAL_RECOMMENDED_TABLE', category: 'STRUCTURE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 2, message: '存在 2 张 NORMAL/RECOMMENDED 状态的表' },
      { code: 'SEMANTIC_HIGH_UNRESOLVED', category: 'SEMANTIC', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的语义高风险问题' },
    ],
  })
}

function mountPage() {
  return mount(ReleasesView as never, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        ScopeBar: true,
        TaskPageHeader: {
          props: ['title'],
          template: '<header><h1>{{ title }}</h1></header>',
        },
        ElDialog: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: `<section v-if="modelValue" class="test-version-dialog">
            <header><slot name="header" :title-id="'test-version-title'" :title-class="'test-version-title'" /></header>
            <slot />
            <footer><slot name="footer" /><button class="test-dialog-close" @click="$emit('update:modelValue', false)">测试关闭</button></footer>
          </section>`,
        },
        BusinessStatusBadge: {
          props: ['label'],
          template: '<span class="test-status">{{ label }}</span>',
        },
      },
    },
  })
}

async function settle() {
  for (let round = 0; round < 6; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

function rowForVersion(wrapper: ReturnType<typeof mountPage>, versionNumber: number) {
  return wrapper.findAll('.el-table__row').find((row) => row.text().includes(`v${versionNumber}`))
}

function rowButton(wrapper: ReturnType<typeof mountPage>, versionNumber: number, label: string) {
  return rowForVersion(wrapper, versionNumber)?.findAll('button').find((button) => button.text() === label)
}

function versionButton(wrapper: ReturnType<typeof mountPage>, versionNumber: number) {
  return wrapper.findAll('.release-version__link').find((button) => button.text() === `v${versionNumber}`)
}

beforeEach(() => {
  pendingReleaseWrites.value=[]
  setActivePinia(createPinia())
  routeState.query = {}
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  routerState.push.mockReset()
  routerState.replace.mockReset()
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: DATASOURCE_ID, name: '销售库', databaseName: 'sales' }] })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 8, snapshotVersion: 1 }], total: 1 } })
  api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1)], total: 1 } })
  api.getPublishedSnapshot.mockResolvedValue({ data: null })
  api.getSnapshotDetail.mockImplementation((id: number) => ({ data: snapshotDetail(id, id === 9 ? 2 : 1) }))
  api.listSnapshotAuditLogs.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listQualityIssues.mockResolvedValue({ data: { records: [], total: 0 } })
  api.triggerQualityCheck.mockResolvedValue({ data: { qualityScore: 88 } })
  api.changeSnapshotStatus.mockResolvedValue({ data: null })
  api.publishSnapshot.mockResolvedValue({ data: null })
  api.revokeSnapshot.mockResolvedValue({ data: null })
  api.getReleaseGate.mockImplementation((snapshotId: number) => ({ data: releaseGate(snapshotId) }))
})

describe('元数据版本工作区', () => {
  it.each([
    { status: 'PARTIAL', complete: false, executedRuleCount: 2, failedRuleCount: 1, notApplicableRuleCount: 0, qualityScore: null, feedback: 'warning', message: '质量校验部分完成；本次评分不作为完整结果。' },
    { status: 'FAILED', complete: false, executedRuleCount: 0, failedRuleCount: 1, notApplicableRuleCount: 0, qualityScore: null, feedback: 'error', message: '质量校验未完成，请查看失败覆盖说明。' },
    { status: 'FAILED', complete: false, executedRuleCount: 0, failedRuleCount: 0, notApplicableRuleCount: 3, qualityScore: null, feedback: 'warning', message: '本次检查没有可执行的适用项；未生成完整校验结果。' },
    { status: 'SUCCESS', complete: false, executedRuleCount: 2, failedRuleCount: 0, notApplicableRuleCount: 0, qualityScore: null, feedback: 'warning', message: '质量校验部分完成；本次评分不作为完整结果。' },
    { status: 'SUCCESS', complete: true, executedRuleCount: 3, failedRuleCount: 0, notApplicableRuleCount: 0, qualityScore: 88, feedback: 'success', message: '质量校验已完整完成，综合得分 88。' },
    { status: 'SUCCESS', complete: true, executedRuleCount: 3, failedRuleCount: 0, notApplicableRuleCount: 0, qualityScore: 0, feedback: 'success', message: '质量校验已完整完成，综合得分 0。' },
  ] as const)('$status / complete=$complete 按实际检查覆盖反馈，不伪造评分或状态', async (result) => {
    respond(capability(['metadata:release:view', 'governance:check']))
    const { feedback, message, ...batch } = result
    const snapshotStatus = batch.complete ? 'ISSUE_FOUND' : 'DRAFT'
    let currentStatus = 'DRAFT'
    api.triggerQualityCheck.mockImplementation(async () => {
      currentStatus = snapshotStatus
      return { data: { snapshotId: 8, snapshotStatus, requestedScope: 'FULL', ...batch } }
    })
    api.listVersionHistory.mockImplementation(() => ({ data: { records: [version(8, 1, currentStatus)], total: 1 } }))
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }) as never)
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }) as never)
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }) as never)
    const wrapper = mountPage()
    try {
      await settle()
      const initialReads = api.listVersionHistory.mock.calls.length
      await rowButton(wrapper, 1, '开始检查')!.trigger('click')
      await settle()

      expect(api.triggerQualityCheck).toHaveBeenCalledWith(8)
      expect({ success, warning, error }[feedback]).toHaveBeenCalledWith(message)
      if (feedback !== 'success') expect(success).not.toHaveBeenCalled()
      if (feedback !== 'warning') expect(warning).not.toHaveBeenCalled()
      if (feedback !== 'error') expect(error).not.toHaveBeenCalled()
      expect(api.changeSnapshotStatus).not.toHaveBeenCalled()
      expect(api.listVersionHistory).toHaveBeenCalledTimes(initialReads + 1)
      if (!batch.complete) expect(rowButton(wrapper, 1, '开始检查')).toBeDefined()
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
      success.mockRestore()
      warning.mockRestore()
      error.mockRestore()
    }
  })

  it('主列表以快照 ID 标记当前正式版，并展示真实发布时间而不显示审校人为发布人', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, 'PUBLISHED'), version(9, 2, 'PUBLISHED')], total: 2 } })
    api.getPublishedSnapshot.mockResolvedValue({
      data: { ...version(9, 2, 'PUBLISHED'), publishedAt: '2026-09-22T11:30:00' },
    })
    respond(capability(['metadata:release:view']))
    const wrapper = mountPage()
    await settle()

    expect(wrapper.text()).toContain('当前正式版')
    expect(wrapper.text()).toContain('发布时间 2026-09-22 11:30')
    expect(wrapper.text()).toContain('审校用户')
    expect(wrapper.text()).not.toContain('发布人')
    const baseRow = rowForVersion(wrapper, 1)!
    const officialRow = rowForVersion(wrapper, 2)!
    expect(baseRow.find('.release-current-tag').exists()).toBe(false)
    expect(officialRow.find('.release-current-tag').exists()).toBe(true)
    wrapper.unmount()
  })

  it('没有正式版时仍可从两个列表版本手选比较', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1), version(9, 2)], total: 2 } })
    respond(capability(['metadata:release:view']))
    const wrapper = mountPage()
    await settle()

    const checkboxes = wrapper.findAllComponents(ElCheckbox)
    expect(checkboxes.length).toBe(2)
    await checkboxes[0].vm.$emit('change', true)
    await checkboxes[1].vm.$emit('change', true)
    await settle()
    expect(wrapper.text()).toContain('基准 v1 → 对比 v2')
    await wrapper.findAll('button').find((button) => button.text() === '比较版本')!.trigger('click')
    expect(routerState.push).toHaveBeenCalledWith('/admin/releases/snapshots/8/diff/9')
    wrapper.unmount()
  })

  it('跨数据源选择不能启用比较或跳转差异页', async () => {
    api.listVersionHistory.mockResolvedValue({
      data: { records: [version(8, 1, 'DRAFT', DATASOURCE_ID), version(9, 2, 'DRAFT', 9)], total: 2 },
    })
    respond(capability(['metadata:release:view']))
    const wrapper = mountPage()
    await settle()
    await rowForVersion(wrapper, 1)!.findComponent(ElCheckbox).vm.$emit('change', true)
    await rowForVersion(wrapper, 2)!.findComponent(ElCheckbox).vm.$emit('change', true)
    await settle()

    expect(wrapper.text()).toContain('只能比较同一数据源中的版本')
    const compareButton = wrapper.findAll('button').find((button) => button.text() === '比较版本')!
    expect(compareButton.attributes('disabled')).toBeDefined()
    expect(routerState.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('当前正式版比较按真实 snapshotId 设置基准，不猜测版本先后', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 9, 'APPROVED'), version(9, 2, 'PUBLISHED')], total: 2 } })
    api.getPublishedSnapshot.mockResolvedValue({ data: { ...version(9, 2, 'PUBLISHED'), publishedAt: '2026-09-22T11:30:00' } })
    respond(capability(['metadata:release:view']))
    const wrapper = mountPage()
    await settle()

    await rowButton(wrapper, 9, '与当前正式版比较')!.trigger('click')
    expect(routerState.push).toHaveBeenCalledWith('/admin/releases/snapshots/9/diff/8')
    wrapper.unmount()
  })

  it('版本号直接打开统一详情弹窗，字段结构在同一弹窗内展开', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1), version(9, 2)], total: 2 } })
    // 结构接口独立要求metadata:view；保留此场景的原字段/错误/隔离断言。
    respond(capability(['metadata:release:view', 'metadata:view', 'governance:view']))
    const wrapper = mountPage()
    await settle()
    const rowBody = wrapper.find('.el-table__body-wrapper').element as HTMLElement
    rowBody.scrollTop = 42
    const firstCheckbox = wrapper.findAllComponents(ElCheckbox)[0]
    await firstCheckbox.vm.$emit('change', true)
    await settle()
    const replaceCalls = routerState.replace.mock.calls.length

    await versionButton(wrapper, 1)!.trigger('click')
    await settle()
    expect(api.getSnapshotDetail).toHaveBeenCalledWith(8)
    expect(wrapper.find('.test-version-dialog').text()).toContain('版本 v1')
    expect(wrapper.find('.test-version-dialog').text()).toContain('销售库')
    expect(wrapper.find('.test-version-dialog').text()).toContain('查看治理情况')
    expect(wrapper.find('.test-version-dialog').text()).toContain('采集时间')
    expect(wrapper.find('.test-version-dialog').text()).toContain('约 3 行')
    expect(wrapper.find('.el-tabs__item.is-active').text()).toBe('版本内容')
    expect(api.listSnapshotAuditLogs).not.toHaveBeenCalled()

    const expand = wrapper.find('.test-version-dialog .el-table__expand-icon')
    await expand.trigger('click')
    await settle()
    expect(wrapper.find('.test-version-dialog').text()).toContain('order_id')
    expect(wrapper.find('.test-version-dialog').text()).toContain('BIGINT')
    expect(routerState.replace).toHaveBeenCalledTimes(replaceCalls)

    await wrapper.find('.test-dialog-close').trigger('click')
    await settle()
    expect(wrapper.find('.test-version-dialog').exists()).toBe(false)
    expect(wrapper.find('.el-table__body-wrapper').element).toBe(rowBody)
    expect(rowBody.scrollTop).toBe(42)
    expect(wrapper.findAllComponents(ElCheckbox)[0].props('modelValue')).toBe(true)
    expect(routerState.replace).toHaveBeenCalledTimes(replaceCalls)
    wrapper.unmount()
  })

  it('操作记录入口打开同一弹窗的操作记录标签，只展示接口实际返回的记录', async () => {
    respond(capability(['metadata:release:view']))
    api.listSnapshotAuditLogs.mockResolvedValue({
      data: {
        records: [{ id: 1, action: 'PUBLISH', operatorName: '真实操作人', reason: '校验后发布', createdAt: '2026-09-22T11:30:00' }],
        total: 1,
      },
    })
    const wrapper = mountPage()
    await settle()
    await rowButton(wrapper, 1, '操作记录')!.trigger('click')
    await settle()

    expect(wrapper.find('.test-version-dialog').exists()).toBe(true)
    expect(wrapper.find('.test-version-dialog').text()).toContain('真实操作人')
    expect(wrapper.find('.test-version-dialog').text()).toContain('发布')
    expect(wrapper.find('.test-version-dialog').text()).toContain('校验后发布')
    expect(api.listSnapshotAuditLogs).toHaveBeenCalledWith(8, { page: 1, size: 20 })
    expect(wrapper.findAll('.el-dialog').length).toBe(0)
    wrapper.unmount()
  })

  it('操作记录读取失败不伪装成空记录，重试读取同一快照', async () => {
    respond(capability(['metadata:release:view']))
    api.listSnapshotAuditLogs
      .mockRejectedValueOnce(new Error('审计服务暂不可用'))
      .mockResolvedValueOnce({ data: { records: [], total: 0 } })
    const wrapper = mountPage()
    await settle()
    await rowButton(wrapper, 1, '操作记录')!.trigger('click')
    await settle()

    expect(wrapper.find('.test-version-dialog').text()).toContain('审计服务暂不可用')
    expect(wrapper.find('.test-version-dialog').text()).not.toContain('暂无操作记录')
    const retry = wrapper.findAll('button').find((button) => button.text() === '重试')!
    await retry.trigger('click')
    await settle()
    expect(api.listSnapshotAuditLogs).toHaveBeenNthCalledWith(1, 8, { page: 1, size: 20 })
    expect(api.listSnapshotAuditLogs).toHaveBeenNthCalledWith(2, 8, { page: 1, size: 20 })
    expect(wrapper.find('.test-version-dialog').text()).toContain('暂无操作记录')
    expect(wrapper.find('.metadata-version-dialog__audit-table').exists()).toBe(false)
    wrapper.unmount()
  })

  it('仅在接口确认存在待处理问题且具备问题处理能力时突出治理入口', async () => {
    // 结构接口独立要求metadata:view；保留此场景的原字段/错误/隔离断言。
    respond(capability(['metadata:release:view', 'metadata:view', 'governance:view', 'governance:issue:view', 'governance:issue:manage']))
    api.listQualityIssues.mockImplementation((_snapshotId: number, params: { status: string }) => ({
      data: { records: [], total: params.status === 'OPEN' ? 2 : 0 },
    }))
    const wrapper = mountPage()
    await settle()
    await versionButton(wrapper, 1)!.trigger('click')
    await settle()

    expect(wrapper.find('.metadata-version-dialog__footer').text()).toContain('查看治理情况')
    expect(wrapper.find('.metadata-version-dialog__footer').text()).toContain('处理待处理问题（2）')
    expect(api.listQualityIssues).toHaveBeenCalledWith(8, { status: 'OPEN', page: 1, size: 1 })
    expect(api.listQualityIssues).toHaveBeenCalledWith(8, { status: 'CONFIRMED', page: 1, size: 1 })
    expect(api.listQualityIssues).toHaveBeenCalledWith(8, { status: 'REOPENED', page: 1, size: 1 })

    await wrapper.findAll('button').find((button) => button.text().startsWith('处理待处理问题'))!.trigger('click')
    expect(routerState.push).toHaveBeenCalledWith({
      path: '/admin/governance/issues',
      query: { datasourceId: String(DATASOURCE_ID), snapshotId: '8' },
    })
    expect(api.triggerQualityCheck).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('详情切换快照时，较晚返回的旧请求不能覆盖新版本', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1), version(9, 2)], total: 2 } })
    // 结构接口独立要求metadata:view；保留此场景的原字段/错误/隔离断言。
    respond(capability(['metadata:release:view', 'metadata:view']))
    let resolveFirst!: (value: { data: ReturnType<typeof snapshotDetail> }) => void
    let resolveSecond!: (value: { data: ReturnType<typeof snapshotDetail> }) => void
    const firstRequest = new Promise<{ data: ReturnType<typeof snapshotDetail> }>((resolve) => { resolveFirst = resolve })
    const secondRequest = new Promise<{ data: ReturnType<typeof snapshotDetail> }>((resolve) => { resolveSecond = resolve })
    api.getSnapshotDetail.mockImplementation((id: number) => id === 8 ? firstRequest : secondRequest)
    const wrapper = mountPage()
    await settle()

    await versionButton(wrapper, 1)!.trigger('click')
    await versionButton(wrapper, 2)!.trigger('click')
    resolveSecond({ data: snapshotDetail(9, 2) })
    await settle()
    expect(wrapper.find('.test-version-dialog').text()).toContain('版本 v2')
    expect(wrapper.find('.test-version-dialog').text()).toContain('orders_2')
    resolveFirst({ data: snapshotDetail(8, 1) })
    await settle()
    expect(wrapper.find('.test-version-dialog').text()).toContain('版本 v2')
    expect(wrapper.find('.test-version-dialog').text()).not.toContain('orders_1')
    wrapper.unmount()
  })

  it('详情失败显示错误并对已打开快照重试；空字段与失败保持区分', async () => {
    // 结构接口独立要求metadata:view；保留此场景的原字段/错误/隔离断言。
    respond(capability(['metadata:release:view', 'metadata:view']))
    api.getSnapshotDetail
      .mockRejectedValueOnce(new Error('快照详情服务暂不可用'))
      .mockResolvedValueOnce({ data: { ...snapshotDetail(8, 1), tables: [] } })
    const wrapper = mountPage()
    await settle()
    await versionButton(wrapper, 1)!.trigger('click')
    await settle()
    expect(wrapper.find('.test-version-dialog').text()).toContain('快照详情服务暂不可用')
    expect(wrapper.find('.test-version-dialog').text()).not.toContain('该快照尚未返回表清单')

    const retry = wrapper.findAll('button').find((button) => button.text() === '重试')!
    await retry.trigger('click')
    await settle()
    expect(api.getSnapshotDetail).toHaveBeenNthCalledWith(1, 8)
    expect(api.getSnapshotDetail).toHaveBeenNthCalledWith(2, 8)
    expect(wrapper.find('.test-version-dialog').text()).toContain('该快照尚未返回表清单')
    wrapper.unmount()
  })

  it('切换数据源后关闭详情弹窗，旧对象结果不会覆盖新数据源', async () => {
    // 结构接口独立要求metadata:view；保留此场景的原字段/错误/隔离断言。
    respond(capability(['metadata:release:view', 'metadata:view']))
    let resolvePending!: (value: { data: ReturnType<typeof snapshotDetail> }) => void
    const pendingRequest = new Promise<{ data: ReturnType<typeof snapshotDetail> }>((resolve) => { resolvePending = resolve })
    api.getSnapshotDetail.mockReturnValue(pendingRequest)
    const wrapper = mountPage()
    await settle()
    await versionButton(wrapper, 1)!.trigger('click')
    await settle()

    const datasourceChange = useAdminContextStore().selectDatasource(9)
    await settle()
    expect(wrapper.find('.test-version-dialog').exists()).toBe(false)
    resolvePending({ data: snapshotDetail(8, 1, DATASOURCE_ID) })
    await datasourceChange
    await settle()
    expect(wrapper.find('.test-version-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('版本操作按权限和状态显示，撤回保留原原因确认和执行请求', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, 'PUBLISHED')], total: 1 } })
    respond(capability(['metadata:release:view', 'metadata:release:publish']))
    const wrapper = mountPage()
    await settle()
    const row = rowForVersion(wrapper, 1)!
    const actions = row.find('.release-row-actions')!
    expect(actions.text()).toContain('操作记录')
    expect(actions.text()).toContain('撤回')
    expect(actions.text()).not.toContain('更多')
    expect(row.find('.release-version__link').exists()).toBe(true)

    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '回滚原因' } as never)
    await rowButton(wrapper, 1, '撤回')!.trigger('click')
    await settle()
    expect(prompt).toHaveBeenCalledWith('请输入撤回原因', '撤回已发布快照', expect.objectContaining({
      confirmButtonText: '确认撤回',
      cancelButtonText: '取消',
    }))
    expect(api.revokeSnapshot).toHaveBeenCalledWith(8, '回滚原因')
    prompt.mockRestore()
    wrapper.unmount()

    setActivePinia(createPinia())
    respond(capability(['metadata:release:view']))
    const readOnlyWrapper = mountPage()
    await settle()
    const readOnlyRow = rowForVersion(readOnlyWrapper, 1)!
    expect(readOnlyRow.find('.release-row-actions').text()).not.toContain('撤回')
    readOnlyWrapper.unmount()
  })

  it('审核批准与正式发布分别遵循现有状态和 IAM 功能门禁', async () => {
    api.listVersionHistory.mockResolvedValue({
      data: { records: [version(8, 1, 'ISSUE_FOUND'), version(9, 2, 'APPROVED')], total: 2 },
    })
    respond(capability(['metadata:release:view']))
    const readOnlyWrapper = mountPage()
    await settle()
    expect(rowForVersion(readOnlyWrapper, 1)!.find('.release-row-actions').text()).not.toContain('批准')
    expect(rowForVersion(readOnlyWrapper, 2)!.find('.release-row-actions').text()).not.toContain('发布')
    readOnlyWrapper.unmount()

    setActivePinia(createPinia())
    respond(capability(['metadata:release:view', 'metadata:release:review', 'metadata:release:publish']))
    const authorizedWrapper = mountPage()
    await settle()
    expect(rowForVersion(authorizedWrapper, 1)!.find('.release-row-actions').text()).toContain('批准')
    expect(rowForVersion(authorizedWrapper, 2)!.find('.release-row-actions').text()).toContain('发布')
    authorizedWrapper.unmount()
  })

  it('列表加载不为每一行请求发布门禁', async () => {
    api.listVersionHistory.mockResolvedValue({
      data: { records: [version(8, 1, 'APPROVED'), version(9, 2, 'APPROVED')], total: 2 },
    })
    respond(capability(['metadata:release:view', 'metadata:release:publish']))
    const wrapper = mountPage()
    await settle()

    expect(api.getReleaseGate).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('发布确认在存在阻断时禁用提交并显示原因', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(9, 2, 'APPROVED')], total: 1 } })
    api.getReleaseGate.mockResolvedValue({ data: blockedReleaseGate(9) })
    respond(capability(['metadata:release:view', 'metadata:release:publish']))
    const wrapper = mountPage()
    await settle()

    await rowButton(wrapper, 2, '发布')!.trigger('click')
    await settle()

    expect(api.getReleaseGate).toHaveBeenCalledWith(9)
    const confirm = wrapper.findAll('button').find((button) => button.text() === '确认发布')
    expect(confirm).toBeTruthy()
    expect(confirm!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('无法发布：存在 2 个未解决的高风险问题')
    expect(api.publishSnapshot).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('仅有语义提示时允许发布并明确不阻断', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(9, 2, 'APPROVED')], total: 1 } })
    api.getReleaseGate.mockResolvedValue({
      data: releaseGate(9, {
        warningCount: 1,
        unresolvedSemanticHigh: 1,
        items: [
          { code: 'SOURCE_HIGH_UNRESOLVED', category: 'SOURCE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 0, message: '没有未解决的来源高风险问题' },
          { code: 'NO_NORMAL_RECOMMENDED_TABLE', category: 'STRUCTURE', severity: 'HIGH', status: 'PASS', blocksApproval: false, blocksPublish: false, count: 2, message: '存在 2 张 NORMAL/RECOMMENDED 状态的表' },
          { code: 'SEMANTIC_HIGH_UNRESOLVED', category: 'SEMANTIC', severity: 'HIGH', status: 'WARN', blocksApproval: false, blocksPublish: false, count: 1, message: '存在 1 个未解决的语义高风险问题，语义问题不阻断发布，请在发布前确认' },
        ],
      }),
    })
    respond(capability(['metadata:release:view', 'metadata:release:publish']))
    const wrapper = mountPage()
    await settle()

    await rowButton(wrapper, 2, '发布')!.trigger('click')
    await settle()
    expect(wrapper.text()).toContain('语义问题不阻断发布')

    const confirm = wrapper.findAll('button').find((button) => button.text() === '确认发布')
    expect(confirm!.attributes('disabled')).toBeUndefined()
    await confirm!.trigger('click')
    await settle()
    expect(api.publishSnapshot).toHaveBeenCalledWith(9)
    wrapper.unmount()
  })

  it('批准前读取门禁，来源高风险未解决时停止提交', async () => {
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, 'ISSUE_FOUND')], total: 1 } })
    api.getReleaseGate.mockResolvedValue({ data: blockedReleaseGate(8) })
    respond(capability(['metadata:release:view', 'metadata:release:review']))
    const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    await settle()

    await rowButton(wrapper, 1, '批准')!.trigger('click')
    await settle()

    expect(api.getReleaseGate).toHaveBeenCalledWith(8)
    expect(alert).toHaveBeenCalledWith(
      expect.stringContaining('无法发布：存在 2 个未解决的高风险问题'),
      '暂不能批准',
      expect.objectContaining({ type: 'warning' }),
    )
    expect(api.changeSnapshotStatus).not.toHaveBeenCalled()
    alert.mockRestore()
    wrapper.unmount()
  })
})

describe('显式人工审核', () => {
  it('审核者可明确进入审核再批准，但不会执行质量检查或自动发布', async () => {
    respond(capability(['metadata:release:view', 'metadata:release:review']))
    let status = 'DRAFT'
    api.listVersionHistory.mockImplementation(() => ({ data: { records: [version(8, 1, status)], total: 1 } }))
    api.changeSnapshotStatus.mockImplementation(async (_id: number, payload: { targetStatus: string }) => {
      status = payload.targetStatus
      return { data: null }
    })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    try {
      await settle()
      const enter = rowButton(wrapper, 1, '进入审核')
      expect(enter).toBeDefined()
      expect(rowButton(wrapper, 1, '开始检查')).toBeUndefined()
      expect(rowButton(wrapper, 1, '批准')).toBeUndefined()
      await enter!.trigger('click')
      await settle()
      expect(confirm).toHaveBeenCalledWith(expect.stringContaining('不代表质量检查已完整完成'),
        '确认进入审核', expect.objectContaining({ type: 'warning' }))
      expect(api.changeSnapshotStatus).toHaveBeenCalledWith(8, expect.objectContaining({ targetStatus: 'CHECKING' }))
      expect(api.triggerQualityCheck).not.toHaveBeenCalled()
      expect(api.publishSnapshot).not.toHaveBeenCalled()
      expect(rowButton(wrapper, 1, '批准')).toBeDefined()
      await rowButton(wrapper, 1, '批准')!.trigger('click')
      await settle()
      expect(api.getReleaseGate).toHaveBeenCalledWith(8)
      expect(api.changeSnapshotStatus).toHaveBeenLastCalledWith(8, { targetStatus: 'APPROVED' })
      expect(api.publishSnapshot).not.toHaveBeenCalled()
      expect(rowButton(wrapper, 1, '发布')).toBeUndefined()
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
    }
  })

  it.each(['check-only', 'other-source-review'])('%s 不获得当前快照的人工审核动作', async (role) => {
    const snapshot = capability(['metadata:release:view', ...(role === 'check-only' ? ['governance:check'] : [])])
    if (role === 'other-source-review') snapshot.datasourceCapabilities.push({
      datasourceId: 6, datasourceName: '其他源', functionCodes: ['metadata:release:review'], functionNames: [],
    })
    respond(snapshot)
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, 'DRAFT'), version(9, 2, 'CHECKING')], total: 2 } })
    const wrapper = mountPage()
    try {
      await settle()
      expect(rowButton(wrapper, 1, '进入审核')).toBeUndefined()
      expect(rowButton(wrapper, 2, '批准')).toBeUndefined()
      if (role === 'check-only') expect(rowButton(wrapper, 1, '开始检查')).toBeDefined()
      expect(api.changeSnapshotStatus).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('同时有检查和审核能力时 PARTIAL 检查也不会自动进入审核', async () => {
    respond(capability(['metadata:release:view', 'metadata:release:review', 'governance:check']))
    api.triggerQualityCheck.mockResolvedValue({ data: { status: 'PARTIAL', complete: false, qualityScore: null } })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    try {
      await settle()
      await rowButton(wrapper, 1, '开始检查')!.trigger('click')
      await settle()
      expect(api.triggerQualityCheck).toHaveBeenCalledWith(8)
      expect(api.changeSnapshotStatus).not.toHaveBeenCalled()
      expect(rowButton(wrapper, 1, '进入审核')).toBeDefined()
      expect(rowButton(wrapper, 1, '批准')).toBeUndefined()
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
    }
  })

  it.each(['CHECKING', 'ISSUE_FOUND'])('%s 人工批准仍受来源高风险门禁阻断', async (status) => {
    respond(capability(['metadata:release:view', 'metadata:release:review']))
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, status)], total: 1 } })
    api.getReleaseGate.mockResolvedValue({ data: blockedReleaseGate(8) })
    const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    try {
      await settle()
      const approve = rowButton(wrapper, 1, '批准')
      expect(approve).toBeDefined()
      await approve!.trigger('click')
      await settle()
      expect(alert).toHaveBeenCalled()
      expect(api.changeSnapshotStatus).not.toHaveBeenCalled()
      expect(api.publishSnapshot).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      alert.mockRestore()
    }
  })

  it.each([false, true])('人工批准保留后端409拒绝，门禁读取失败=%s', async (gateReadFails) => {
    respond(capability(['metadata:release:view', 'metadata:release:review']))
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, 'CHECKING')], total: 1 } })
    if (gateReadFails) api.getReleaseGate.mockRejectedValue(new Error('门禁暂不可读'))
    api.changeSnapshotStatus.mockRejectedValue(Object.assign(new Error('审核来源已变化'), {
      response: { status: 409, data: { message: '审核来源已变化，不能批准' } },
    }))
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }) as never)
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }) as never)
    const wrapper = mountPage()
    try {
      await settle()
      const approve = rowButton(wrapper, 1, '批准')
      expect(approve).toBeDefined()
      await approve!.trigger('click')
      await settle()
      expect(api.changeSnapshotStatus).toHaveBeenCalledWith(8, { targetStatus: 'APPROVED' })
      expect(error).toHaveBeenCalledWith(expect.stringContaining('审核来源已变化'))
      expect(success).not.toHaveBeenCalled()
      expect(api.publishSnapshot).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
      success.mockRestore()
      error.mockRestore()
    }
  })

  it('取消进入审核确认后不写状态', async () => {
    respond(capability(['metadata:release:view', 'metadata:release:review']))
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mountPage()
    try {
      await settle()
      const enter = rowButton(wrapper, 1, '进入审核')
      expect(enter).toBeDefined()
      await enter!.trigger('click')
      await settle()
      expect(api.changeSnapshotStatus).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
    }
  })

  it('进入审核确认期间 A→B→A 后取消原写操作', async () => {
    respond(capability(['metadata:release:view', 'metadata:release:review']))
    let finish!: () => void
    const pending = new Promise((resolve) => { finish = () => resolve('confirm') })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValue(pending as never)
    const wrapper = mountPage()
    try {
      await settle()
      const enter = rowButton(wrapper, 1, '进入审核')
      expect(enter).toBeDefined()
      await enter!.trigger('click')
      const context = useAdminContextStore()
      context.datasourceId = 6
      await settle()
      context.datasourceId = DATASOURCE_ID
      await settle()
      finish()
      await settle()
      expect(api.changeSnapshotStatus).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
    }
  })

  it('CHECKING 仅语义警告仍可人工批准，但不自动发布', async () => {
    respond(capability(['metadata:release:view', 'metadata:release:review']))
    api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, 'CHECKING')], total: 1 } })
    api.getReleaseGate.mockResolvedValue({ data: releaseGate(8, { approvable: true, warningCount: 1 }) })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    try {
      await settle()
      const approve = rowButton(wrapper, 1, '批准')
      expect(approve).toBeDefined()
      await approve!.trigger('click')
      await settle()
      expect(api.changeSnapshotStatus).toHaveBeenCalledWith(8, { targetStatus: 'APPROVED' })
      expect(api.publishSnapshot).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
    }
  })
})


describe('版本生命周期反例', () => {
  function deferred() { let resolve!:(x:any)=>void, reject!:(x:any)=>void;const promise=new Promise<any>((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject} }
  it('仅发布查看者不读取元数据结构，但保留门禁和审计',async()=>{
    respond(capability(['metadata:release:view']));const w=mountPage();await settle();await versionButton(w,1)!.trigger('click');await settle()
    expect(api.getSnapshotDetail).not.toHaveBeenCalled();expect(api.getReleaseGate).toHaveBeenCalledWith(8);expect(w.text()).toContain('没有此源的 metadata:view')
    await w.findAll('.el-tabs__item').find(x=>x.text()==='操作记录')!.trigger('click');await settle();expect(api.listSnapshotAuditLogs).toHaveBeenCalledWith(8,{page:1,size:20});w.unmount()
  })
  it('无发布查看权不读取版本和当前正式版',async()=>{
    respond(capability(['audit:view']));const w=mountPage();await settle();expect(api.listVersionHistory).not.toHaveBeenCalled();expect(api.getPublishedSnapshot).not.toHaveBeenCalled();expect(w.text()).toContain('没有当前数据源');w.unmount()
  })
  it('批准门禁读取期间重复点击与换账号不发旧写',async()=>{
    respond(capability(['metadata:release:view','metadata:release:review']));api.listVersionHistory.mockResolvedValue({data:{records:[version(8,1,'CHECKING')],total:1}});const d=deferred();api.getReleaseGate.mockReturnValue(d.promise);const confirm=vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as never);const w=mountPage();await settle()
    await rowButton(w,1,'批准')!.trigger('click');await rowButton(w,1,'批准')!.trigger('click');expect(api.getReleaseGate).toHaveBeenCalledTimes(1);useAuthStore().token='synthetic-new-account';await settle();d.resolve({data:releaseGate(8)});await settle();expect(confirm).not.toHaveBeenCalled();expect(api.changeSnapshotStatus).not.toHaveBeenCalled();w.unmount();confirm.mockRestore()
  })
  it('发布确认切源立即关闭，旧确认不写原快照',async()=>{
    respond(capability(['metadata:release:view','metadata:release:publish']));api.listVersionHistory.mockResolvedValue({data:{records:[version(8,1,'APPROVED')],total:1}});const w=mountPage();await settle();await rowButton(w,1,'发布')!.trigger('click');await settle();expect(w.text()).toContain('确认发布');useAdminContextStore().datasourceId=6;await settle();expect(w.text()).not.toContain('确认发布');expect(api.publishSnapshot).not.toHaveBeenCalled();w.unmount()
  })
  it('撤回409保留原因而不自动重发',async()=>{
    respond(capability(['metadata:release:view','metadata:release:publish']));api.listVersionHistory.mockResolvedValue({data:{records:[version(8,1,'PUBLISHED')],total:1}});api.revokeSnapshot.mockRejectedValue({response:{status:409,data:{message:'合成冲突'}}});const prompt=vi.spyOn(ElMessageBox,'prompt').mockResolvedValue({value:'必须保留的撤回原因'} as never);const w=mountPage();await settle();await rowButton(w,1,'撤回')!.trigger('click');await settle();expect(w.text()).toContain('必须保留的撤回原因');expect(w.text()).toContain('不会自动重试');expect(api.revokeSnapshot).toHaveBeenCalledTimes(1);w.unmount();prompt.mockRestore()
  })
  it('超时未知不会因普通刷新、A到B到A或无新审计解锁',async()=>{
    respond(capability(['metadata:release:view','metadata:release:publish']));api.listVersionHistory.mockResolvedValue({data:{records:[version(8,1,'APPROVED')],total:1}});api.publishSnapshot.mockRejectedValue({response:{status:408,data:{message:'超时'}}});const w=mountPage();await settle();await rowButton(w,1,'发布')!.trigger('click');await settle();await w.findAll('button').find(x=>x.text()==='确认发布')!.trigger('click');await settle();expect(pendingReleaseWrites.value).toHaveLength(1)
    useAdminContextStore().datasourceId=6;await settle();useAdminContextStore().datasourceId=5;await settle();await w.findAll('button').find(x=>x.text()==='刷新版本')!.trigger('click');await settle();await w.findAll('button').find(x=>x.text()==='核对该快照操作记录')!.trigger('click');await settle();expect(w.text()).toContain('尚不能确认原请求结果');expect(api.listSnapshotAuditLogs).toHaveBeenCalledWith(8,{page:1,size:20});await rowButton(w,1,'发布')!.trigger('click');expect(api.publishSnapshot).toHaveBeenCalledTimes(1);expect(pendingReleaseWrites.value).toHaveLength(1);w.unmount()
  })
  it('在途发布换账号后超时仍归原会话，旧回包不关闭新范围',async()=>{
    respond(capability(['metadata:release:view','metadata:release:publish']));api.listVersionHistory.mockResolvedValue({data:{records:[version(8,1,'APPROVED')],total:1}});const d=deferred();api.publishSnapshot.mockReturnValue(d.promise);const w=mountPage();await settle();await rowButton(w,1,'发布')!.trigger('click');await settle();await w.findAll('button').find(x=>x.text()==='确认发布')!.trigger('click');await settle();useAuthStore().token='other';await settle();await rowButton(w,1,'发布')!.trigger('click');await settle();d.reject(new Error('旧请求超时'));await settle();expect(w.text()).toContain('确认发布');expect(pendingReleaseWrites.value).toHaveLength(1);expect(pendingReleaseWrites.value[0]?.identity).not.toBe('other');w.unmount()
  })
  it('同源撤权清旧版本，旧列表回包不能重新显示',async()=>{
    respond(capability(['metadata:release:view']));const d=deferred();api.listVersionHistory.mockReturnValue(d.promise);const w=mountPage();await settle();useIamS1Store().snapshot=capability(['audit:view']);await settle();d.resolve({data:{records:[version(8,1)],total:1}});await settle();expect(w.findAll('.el-table__row')).toHaveLength(0);expect(w.text()).toContain('没有当前数据源');w.unmount()
  })
})

it.each([false,true])('未知发布不把旧事件当新证据；同秒新ID=%s',async(newId)=>{
  respond(capability(['metadata:release:view','metadata:release:publish']));api.listVersionHistory.mockResolvedValue({data:{records:[version(8,1,'APPROVED')],total:1}})
  const old={id:99,action:'PUBLISH',operatorName:'旧事件',createdAt:'2099-01-01T00:00:00'};api.listSnapshotAuditLogs.mockResolvedValue({data:{records:[old],total:1}});api.publishSnapshot.mockRejectedValue(new Error('超时'))
  const w=mountPage();await settle();await rowButton(w,1,'发布')!.trigger('click');await settle();await w.findAll('button').find(x=>x.text()==='确认发布')!.trigger('click');await settle();expect(pendingReleaseWrites.value[0]?.baselineAuditId).toBe(99)
  api.listSnapshotAuditLogs.mockResolvedValue({data:{records:[{...old,id:newId?100:99}],total:1}});await w.findAll('button').find(x=>x.text()==='核对该快照操作记录')!.trigger('click');await settle();expect(pendingReleaseWrites.value).toHaveLength(newId?0:1);expect(api.publishSnapshot).toHaveBeenCalledTimes(1);w.unmount()
})
