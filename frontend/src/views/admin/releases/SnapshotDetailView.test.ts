// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'

const routeState = vi.hoisted(() => ({ params: { snapshotId: '9' } as Record<string, string>, query: { datasourceId: '5' } as Record<string, string> }))
const routerState = vi.hoisted(() => ({ push: vi.fn() }))
const api = vi.hoisted(() => ({ getSnapshotDetail: vi.fn(), getReleaseGate: vi.fn() }))
const iam = vi.hoisted(() => ({ loaded: true, snapshot:{} as any, canOnDatasource: vi.fn() }))
const identity=vi.hoisted(()=>({token:'synthetic-test'}))

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
const reactiveRoute=reactive(routeState), reactiveIam=reactive(iam), reactiveIdentity=reactive(identity)

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
        RouterLink: { template: '<a><slot /></a>' },
        TaskPageHeader: {
          template: '<header><slot name="title-prefix" /><slot name="title-suffix" /><slot name="status" /><slot name="actions" /></header>',
        },
        BusinessStatusBadge: true,
        'el-button': true,
        SnapshotDetailPanel: { props: ['detail'], template: '<section class="detail-stub">detail-panel {{ detail?.snapshot.id }}</section>' },
        ReleaseGatePanel: {
          props: ['gate', 'loading', 'error'],
          template: '<section class="gate-stub">{{ gate ? gate.snapshotId : error }}</section>',
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
  routeState.query={datasourceId:'5'}; routeState.params={snapshotId:'9'}; identity.token='synthetic-test'
  api.getSnapshotDetail.mockReset()
  api.getReleaseGate.mockReset()
  routerState.push.mockReset()
  iam.loaded = true
  iam.canOnDatasource.mockReset()
  iam.canOnDatasource.mockReturnValue(true)
  api.getSnapshotDetail.mockResolvedValue({ data: detail() })
  api.getReleaseGate.mockResolvedValue({ data: gate() })
})

describe('SnapshotDetailView 发布门禁', () => {
  it('具备 metadata:release:view 时读取并展示门禁', async () => {
    const wrapper = mountPage()
    await settle()

    expect(api.getReleaseGate).toHaveBeenCalledWith(9)
    expect(wrapper.find('.gate-stub').text()).toContain('9')
    expect(wrapper.find('.detail-stub').exists()).toBe(true)
  })

  it('没有 metadata:release:view 时不请求门禁', async () => {
    iam.canOnDatasource.mockImplementation((code: string) => code !== 'metadata:release:view')
    const wrapper = mountPage()
    await settle()

    expect(api.getReleaseGate).not.toHaveBeenCalled()
    expect(wrapper.find('.gate-stub').exists()).toBe(false)
  })

  it('门禁读取失败只影响门禁面板，快照正文仍显示', async () => {
    api.getReleaseGate.mockRejectedValue({})
    const wrapper = mountPage()
    await settle()

    expect(wrapper.find('.gate-stub').text()).toContain('发布门禁读取失败')
    expect(wrapper.find('.detail-stub').exists()).toBe(true)
  })
})

it('非法显式源不回落也不请求精确结构',async()=>{reactiveRoute.query.datasourceId='bad';const w=mountPage();await settle();expect(api.getSnapshotDetail).not.toHaveBeenCalled();expect(api.getReleaseGate).not.toHaveBeenCalled();w.unmount()})
it('没有结构权不借用发布查看权',async()=>{iam.canOnDatasource.mockImplementation(code=>code!=='metadata:view');const w=mountPage();await settle();expect(api.getSnapshotDetail).not.toHaveBeenCalled();w.unmount()})
it('账号切换后旧精确结构响应不能回填',async()=>{let resolve!:(x:any)=>void;api.getSnapshotDetail.mockReturnValueOnce(new Promise(r=>{resolve=r}));const w=mountPage();await settle();reactiveIdentity.token='new';await settle();resolve({data:detail(88)});await settle();expect(w.find('.detail-stub').text()).not.toContain('88');expect(w.find('.detail-stub').text()).toContain('9');w.unmount()})
