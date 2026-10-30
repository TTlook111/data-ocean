// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'

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

import { pendingReleaseWrites } from '../../../composables/releaseWriteTracker'
const reactiveRouteState = reactive(routeState)

const DATASOURCE_ID = 5

function capability(functionCodes: string[], datasourceId = DATASOURCE_ID): IamS1CapabilitySnapshot {
  return {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 7,
    systemAdmin: false,
    globalFunctions: [],
    datasourceCapabilities: [
      {
        datasourceId,
        datasourceName: datasourceId === DATASOURCE_ID ? '销售库' : '财务库',
        functionCodes,
        functionNames: functionCodes,
      },
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

function version(
  snapshotId: number,
  snapshotVersion: number,
  status = 'DRAFT',
  datasourceId = DATASOURCE_ID,
) {
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
    tables: [
      {
        id: tableId,
        tableName: `orders_${snapshotVersion}`,
        tableComment: '订单表',
        tableType: 'TABLE',
        rowCountEstimate: 3,
        governanceStatus: 'NORMAL',
      },
    ],
    columns: [
      {
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
      },
    ],
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
      {
        code: 'SOURCE_HIGH_UNRESOLVED',
        category: 'SOURCE',
        severity: 'HIGH',
        status: 'PASS',
        blocksApproval: false,
        blocksPublish: false,
        count: 0,
        message: '没有未解决的来源高风险问题',
      },
      {
        code: 'NO_NORMAL_RECOMMENDED_TABLE',
        category: 'STRUCTURE',
        severity: 'HIGH',
        status: 'PASS',
        blocksApproval: false,
        blocksPublish: false,
        count: 2,
        message: '存在 2 张 NORMAL/RECOMMENDED 状态的表',
      },
      {
        code: 'SEMANTIC_HIGH_UNRESOLVED',
        category: 'SEMANTIC',
        severity: 'HIGH',
        status: 'PASS',
        blocksApproval: false,
        blocksPublish: false,
        count: 0,
        message: '没有未解决的语义高风险问题',
      },
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
      {
        code: 'SOURCE_HIGH_UNRESOLVED',
        category: 'SOURCE',
        severity: 'HIGH',
        status: 'FAIL',
        blocksApproval: true,
        blocksPublish: true,
        count: 2,
        message: '无法发布：存在 2 个未解决的高风险问题',
      },
      {
        code: 'NO_NORMAL_RECOMMENDED_TABLE',
        category: 'STRUCTURE',
        severity: 'HIGH',
        status: 'PASS',
        blocksApproval: false,
        blocksPublish: false,
        count: 2,
        message: '存在 2 张 NORMAL/RECOMMENDED 状态的表',
      },
      {
        code: 'SEMANTIC_HIGH_UNRESOLVED',
        category: 'SEMANTIC',
        severity: 'HIGH',
        status: 'PASS',
        blocksApproval: false,
        blocksPublish: false,
        count: 0,
        message: '没有未解决的语义高风险问题',
      },
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
  return rowForVersion(wrapper, versionNumber)
    ?.findAll('button')
    .find((button) => button.text() === label)
}

function versionButton(wrapper: ReturnType<typeof mountPage>, versionNumber: number) {
  return wrapper.findAll('.release-version__link').find((button) => button.text() === `v${versionNumber}`)
}

beforeEach(() => {
  pendingReleaseWrites.value = []
  setActivePinia(createPinia())
  routeState.query = {}
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  routerState.push.mockReset()
  routerState.replace.mockReset()
  api.listSimpleDatasources.mockResolvedValue({
    data: [{ id: DATASOURCE_ID, name: '销售库', databaseName: 'sales' }],
  })
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

function caps(extra: string, source: number) {
  const c = capability(['metadata:release:view', 'metadata:release:publish'])
  if (source === 5) c.datasourceCapabilities[0].functionCodes.push(extra)
  else if (source)
    c.datasourceCapabilities.push({
      datasourceId: source,
      datasourceName: '另一源',
      functionCodes: [extra],
      functionNames: [extra],
    })
  return c
}
it.each([0, 6, 5])('空版本采集入口只接受同源collect:view(授权源%s)', async (source) => {
  respond(caps('metadata:collect:view', source))
  api.listVersionHistory.mockResolvedValue({ data: { records: [], total: 0 } })
  const w = mountPage()
  try {
    await settle()
    const btn = w.findAll('button').find((x) => x.text() === '去采集任务')
    expect(!!btn).toBe(source === 5)
    if (btn) {
      await btn.trigger('click')
      expect(routerState.push).toHaveBeenCalledWith({
        path: '/admin/collections',
        query: { datasourceId: '5' },
      })
    }
  } finally {
    w.unmount()
  }
})
it.each([0, 6, 5])('版本详情门禁问题入口只接受同源issue:view(授权源%s)', async (source) => {
  respond(caps('governance:issue:view', source))
  api.getReleaseGate.mockImplementation((id) => ({ data: blockedReleaseGate(id) }))
  const w = mountPage()
  try {
    await settle()
    await versionButton(w, 1)!.trigger('click')
    await settle()
    expect(api.getReleaseGate).toHaveBeenCalledWith(8)
    expect(w.findAll('a').some((a) => a.text() === '去问题中心处理')).toBe(source === 5)
    expect(w.text()).toContain('无法发布：存在 2 个未解决的高风险问题')
  } finally {
    w.unmount()
  }
})
it.each([0, 6, 5])('发布确认门禁问题入口只接受同源issue:view且不放宽阻断(授权源%s)', async (source) => {
  respond(caps('governance:issue:view', source))
  api.listVersionHistory.mockResolvedValue({ data: { records: [version(8, 1, 'APPROVED')], total: 1 } })
  api.getReleaseGate.mockImplementation((id) => ({ data: blockedReleaseGate(id) }))
  const w = mountPage()
  try {
    await settle()
    await rowButton(w, 1, '发布')!.trigger('click')
    await settle()
    expect(w.findAll('a').some((a) => a.text() === '去问题中心处理')).toBe(source === 5)
    const confirm = w.findAll('button').find((x) => x.text() === '确认发布')!
    expect(confirm.attributes('disabled')).toBeDefined()
    await confirm.trigger('click')
    expect(api.publishSnapshot).not.toHaveBeenCalled()
  } finally {
    w.unmount()
  }
})
