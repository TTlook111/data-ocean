// @vitest-environment happy-dom
/**
 * 批次 4 前端能力控制测试（版本发布页里属于本批次的两个入口）
 *
 * 该页里有两个按钮调用的端点在批次 4 迁到了 IAM-SIMPLE-1：
 * - 「开始检查」→ `POST /snapshots/{id}/quality-check`，要求 `governance:check` + 该快照的数据源；
 * - 「日志」（快照审计记录）→ `GET /snapshots/{id}/audit-logs`，要求 `metadata:release:view` + 该数据源。
 * 无能力时按钮必须不可用，且不得发出必然失败的请求。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { IamS1CapabilitySnapshot } from '../../../api/iamS1'

const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))

const api = vi.hoisted(() => ({
  getIamS1Capabilities: vi.fn(),
  listVersionHistory: vi.fn(),
  getPublishedSnapshot: vi.fn(),
  listSnapshotAuditLogs: vi.fn(),
  triggerQualityCheck: vi.fn(),
  listSimpleDatasources: vi.fn(),
  listSnapshots: vi.fn(),
  listKnowledgeDocs: vi.fn(),
}))

vi.mock('../../../api/iamS1', () => ({ getIamS1Capabilities: api.getIamS1Capabilities }))
vi.mock('../../../api/admin/versioning', () => ({
  listVersionHistory: api.listVersionHistory,
  getPublishedSnapshot: api.getPublishedSnapshot,
  listSnapshotAuditLogs: api.listSnapshotAuditLogs,
  changeSnapshotStatus: vi.fn(),
  compareSnapshots: vi.fn(),
  publishSnapshot: vi.fn(),
  revokeSnapshot: vi.fn(),
}))
vi.mock('../../../api/admin/governance', () => ({ triggerQualityCheck: api.triggerQualityCheck }))
vi.mock('../../../api/admin/metadata', () => ({
  listSnapshots: api.listSnapshots,
  getSnapshotDetail: vi.fn(),
}))
vi.mock('../../../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('../../../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routeState,
    useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  }
})

import ReleasesView from './ReleasesView.vue'

const DATASOURCE_ID = 5

function capability(functionCodes: string[]): IamS1CapabilitySnapshot {
  return {
    protocolVersion: 'IAM-SIMPLE-1',
    userId: 7,
    systemAdmin: false,
    globalFunctions: [],
    datasourceCapabilities: [
      { datasourceId: DATASOURCE_ID, datasourceName: '销售库', functionCodes, functionNames: functionCodes },
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

function mountPage() {
  return mount(ReleasesView as never, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        TaskPageHeader: { template: '<div><slot name="actions" /></div>' },
        BusinessStatusBadge: true,
        ObjectContextSummary: true,
        ActivityTimeline: true,
      },
    },
  })
}

/**
 * 等到页面异步链路与渲染全部落定。
 *
 * 不能用单次 `flushPromises`：Element Plus 的表格在随后的微任务和帧里才真正渲染出内容，
 * 机器负载高时会读到“还没渲染完”的 DOM，让断言随机失败。
 */
async function settle() {
  for (let round = 0; round < 5; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

/** el-table 会在隐藏列区域再渲染一份列模板，那份的插槽数据是空对象，必须只在真实数据行里找按钮。 */
function rowButton(wrapper: ReturnType<typeof mountPage>, text: string) {
  const row = wrapper.findAll('.el-table__row')[0]
  return row?.findAll('button').find((button) => button.text() === text)
}

beforeEach(() => {
  setActivePinia(createPinia())
  routeState.query = {}
  localStorage.clear()
  Object.values(api).forEach((fn) => fn.mockReset())
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: DATASOURCE_ID, name: '销售库' }] })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 8, snapshotVersion: 1 }], total: 1 } })
  api.getPublishedSnapshot.mockResolvedValue({ data: null })
  api.listSnapshotAuditLogs.mockResolvedValue({ data: { records: [], total: 0 } })
  api.listVersionHistory.mockResolvedValue({
    data: {
      records: [{
        snapshotId: 8,
        datasourceId: DATASOURCE_ID,
        snapshotVersion: 1,
        status: 'DRAFT',
        tableCount: 3,
        columnCount: 12,
        createdAt: '2026-09-20 10:00:00',
      }],
      total: 1,
    },
  })
})

describe('ReleasesView 的能力控制', () => {
  it('没有 metadata:release:view 时「日志」不可用且不请求审计记录', async () => {
    respond(capability([]))
    const wrapper = mountPage()
    await settle()

    const audit = rowButton(wrapper, '日志')
    expect(audit).toBeTruthy()
    expect(audit!.attributes('disabled')).toBeDefined()

    await audit!.trigger('click')
    await settle()
    expect(api.listSnapshotAuditLogs).not.toHaveBeenCalled()
  })

  it('拥有 metadata:release:view 时「日志」可用并能取回审计记录', async () => {
    respond(capability(['metadata:release:view']))
    const wrapper = mountPage()
    await settle()

    const audit = rowButton(wrapper, '日志')
    expect(audit!.attributes('disabled')).toBeUndefined()

    await audit!.trigger('click')
    await settle()
    expect(api.listSnapshotAuditLogs).toHaveBeenCalledWith(8, { page: 1, size: 50 })
  })

  it('没有 governance:check 时「开始检查」不可用且不触发质量校验', async () => {
    respond(capability(['metadata:release:view']))
    const wrapper = mountPage()
    await settle()

    const check = rowButton(wrapper, '开始检查')
    expect(check).toBeTruthy()
    expect(check!.attributes('disabled')).toBeDefined()

    await check!.trigger('click')
    await settle()
    // 质量校验会创建任务并产生问题，无权请求不得走到那一步
    expect(api.triggerQualityCheck).not.toHaveBeenCalled()
  })

  it('拥有 governance:check 时「开始检查」可用', async () => {
    respond(capability(['governance:check']))
    const wrapper = mountPage()
    await settle()

    expect(rowButton(wrapper, '开始检查')!.attributes('disabled')).toBeUndefined()
  })

  it('功能挂在别的数据源上时同样不可用', async () => {
    respond(capability([]))
    api.getIamS1Capabilities.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        ...capability([]),
        datasourceCapabilities: [
          {
            datasourceId: 9,
            datasourceName: '财务库',
            functionCodes: ['metadata:release:view', 'governance:check'],
            functionNames: [],
          },
        ],
      },
    })
    const wrapper = mountPage()
    await settle()

    expect(rowButton(wrapper, '日志')!.attributes('disabled')).toBeDefined()
    expect(rowButton(wrapper, '开始检查')!.attributes('disabled')).toBeDefined()
  })
})
