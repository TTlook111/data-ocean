// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus, { ElCheckbox, ElMessageBox } from 'element-plus'
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
    useRoute: () => routeState,
    useRouter: () => routerState,
  }
})

import ReleasesView from './ReleasesView.vue'

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
    respond(capability(['metadata:release:view', 'governance:view']))
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
    respond(capability(['metadata:release:view', 'governance:view', 'governance:issue:view', 'governance:issue:manage']))
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
    respond(capability(['metadata:release:view']))
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
    respond(capability(['metadata:release:view']))
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
    respond(capability(['metadata:release:view']))
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
