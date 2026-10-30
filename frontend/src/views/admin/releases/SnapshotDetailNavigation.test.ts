// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'

const routeState = vi.hoisted(() => ({
  params: { snapshotId: '9' } as Record<string, string>,
  query: { datasourceId: '5' } as Record<string, string>,
}))
const routerState = vi.hoisted(() => ({ push: vi.fn() }))
const api = vi.hoisted(() => ({ getSnapshotDetail: vi.fn(), getReleaseGate: vi.fn() }))
const iam = vi.hoisted(() => ({ loaded: true, snapshot: {} as any, canOnDatasource: vi.fn() }))
const identity = vi.hoisted(() => ({ token: 'synthetic-test' }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => reactiveRoute,
    useRouter: () => routerState,
  }
})
vi.mock('../../../api/admin/metadata', () => ({ getSnapshotDetail: api.getSnapshotDetail }))
vi.mock('../../../api/admin/versioning', () => ({ getReleaseGate: api.getReleaseGate }))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactiveIdentity }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactiveIam }))

import SnapshotDetailView from './SnapshotDetailView.vue'
const reactiveRoute = reactive(routeState),
  reactiveIam = reactive(iam),
  reactiveIdentity = reactive(identity)

function detail(snapshotId = 9) {
  return {
    snapshot: {
      id: snapshotId,
      snapshotVersion: 2,
      datasourceId: 5,
      datasourceName: '销售库',
      tableCount: 1,
      columnCount: 2,
      qualityScore: 92,
      status: 'APPROVED',
      createdAt: '2026-09-20T10:00:00',
    },
    datasourceName: '销售库',
    tables: [],
    columns: [],
  }
}

function gate(snapshotId = 9) {
  return {
    snapshotId,
    datasourceId: 5,
    snapshotStatus: 'APPROVED',
    approvable: true,
    publishable: true,
    blockingCount: 0,
    warningCount: 0,
    unresolvedSourceHigh: 0,
    unresolvedSemanticHigh: 0,
    validTableCount: 2,
    items: [],
  }
}

function mountPage() {
  return mount(SnapshotDetailView, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :data-target="JSON.stringify(to)"><slot /></a>' },
        TaskPageHeader: {
          template:
            '<header><slot name="title-prefix" /><slot name="title-suffix" /><slot name="status" /><slot name="actions" /></header>',
        },
        BusinessStatusBadge: true,
        'el-button': true,
        SnapshotDetailPanel: {
          props: ['detail'],
          template: '<section class="detail-stub">detail-panel {{ detail?.snapshot.id }}</section>',
        },
        ReleaseGatePanel: {
          props: ['gate', 'loading', 'error', 'issueTarget'],
          template:
            '<section class="gate-stub" :data-target="JSON.stringify(issueTarget)">{{ gate ? gate.snapshotId : error }}</section>',
        },
      },
    },
  })
}

async function settle() {
  for (let round = 0; round < 5; round += 1) {
    await flushPromises()
    await nextTick()
  }
}

beforeEach(() => {
  routeState.query = { datasourceId: '5' }
  routeState.params = { snapshotId: '9' }
  identity.token = 'synthetic-test'
  api.getSnapshotDetail.mockReset()
  api.getReleaseGate.mockReset()
  routerState.push.mockReset()
  iam.loaded = true
  iam.canOnDatasource.mockReset()
  iam.canOnDatasource.mockReturnValue(true)
  api.getSnapshotDetail.mockResolvedValue({ data: detail() })
  api.getReleaseGate.mockResolvedValue({ data: gate() })
})

function target(w: any) {
  return JSON.parse(w.find('.snapshot-detail__back').attributes('data-target')!)
}
it('metadata-only成功详情返回核实的源5快照9，源6的版本权不拼接', async () => {
  iam.canOnDatasource.mockImplementation(
    (c, id) => (c === 'metadata:view' && id === 5) || (c === 'metadata:release:view' && id === 6),
  )
  const w = mountPage()
  try {
    await settle()
    expect(target(w)).toEqual({
      path: '/admin/assets',
      query: { datasourceId: '5', snapshotId: '9', view: 'tables' },
    })
    expect(api.getReleaseGate).not.toHaveBeenCalled()
  } finally {
    w.unmount()
  }
})
it('同源版本查看权返回版本页，不携带详情本地快照参数', async () => {
  iam.canOnDatasource.mockImplementation(
    (c, id) => id === 5 && ['metadata:view', 'metadata:release:view'].includes(c),
  )
  const w = mountPage()
  try {
    await settle()
    expect(target(w)).toEqual({ path: '/admin/releases', query: { datasourceId: '5' } })
  } finally {
    w.unmount()
  }
})
it.each(['failure', 'malformed', 'wrong-source'])('详情%s时返回源目录不带未核实快照', async (kind) => {
  iam.canOnDatasource.mockImplementation((c, id) => c === 'metadata:view' && id === 5)
  if (kind === 'failure') api.getSnapshotDetail.mockRejectedValueOnce(new Error('合成读取失败'))
  if (kind === 'malformed') reactiveRoute.params.snapshotId = 'bad'
  if (kind === 'wrong-source')
    api.getSnapshotDetail.mockResolvedValueOnce({
      data: { ...detail(), snapshot: { ...detail().snapshot, datasourceId: 6 } },
    })
  const w = mountPage()
  try {
    await settle()
    expect(target(w)).toEqual({ path: '/admin/assets', query: { datasourceId: '5', view: 'tables' } })
    expect(JSON.stringify(target(w))).not.toContain('NaN')
    expect(w.find('.detail-stub').text()).not.toContain('9')
  } finally {
    w.unmount()
  }
})
it.each([0, 6, 5])('独立快照门禁只有同源issue:view才给问题入口(授权源%s)', async (source) => {
  iam.canOnDatasource.mockImplementation(
    (c, id) =>
      (id === 5 && ['metadata:view', 'metadata:release:view'].includes(c)) ||
      (c === 'governance:issue:view' && id === source),
  )
  const w = mountPage()
  try {
    await settle()
    const value = w.find('.gate-stub').attributes('data-target')
    if (source === 5)
      expect(JSON.parse(value!)).toEqual({
        path: '/admin/governance/issues',
        query: { datasourceId: '5', snapshotId: '9' },
      })
    else expect(value).toBeUndefined()
    expect(api.getReleaseGate).toHaveBeenCalledWith(9)
  } finally {
    w.unmount()
  }
})
