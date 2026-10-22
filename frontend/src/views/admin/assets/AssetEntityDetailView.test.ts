// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, type VNodeChild } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import AssetEntityDetailView from './AssetEntityDetailView.vue'
import type { CatalogEntityOverview, OverviewColumn, OverviewTermItem } from '../../../api/admin/catalog'

const holders = vi.hoisted(() => ({
  route: null as unknown as { params: { entityId: string }; query: Record<string, string> },
  router: null as unknown as {
    push: ReturnType<typeof vi.fn>
    replace: ReturnType<typeof vi.fn>
  },
}))

vi.mock('vue-router', async () => {
  const { reactive: createReactive } = await import('vue')
  holders.route = createReactive({ params: { entityId: '1' }, query: {} })
  holders.router = { push: vi.fn(), replace: vi.fn() }
  return {
    useRoute: () => holders.route,
    useRouter: () => holders.router,
  }
})

const api = vi.hoisted(() => ({
  getEntityOverview: vi.fn(),
  unconfirmEntityTag: vi.fn(),
}))
vi.mock('../../../api/admin/catalog', () => api)

const iam = vi.hoisted(() => ({
  canOnDatasource: vi.fn((_functionCode: string, _datasourceId?: number) => true),
  load: vi.fn(() => Promise.resolve()),
  loaded: true,
}))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => iam }))

const TaskHeaderStub = defineComponent({
  name: 'TaskPageHeader',
  props: ['title', 'eyebrow', 'description', 'help', 'descriptionMode'],
  setup(props, { slots }) {
    return () => h('header', [
      slots['title-prefix']?.(),
      h('h1', { 'data-test': 'title' }, props.title),
      slots['title-suffix']?.(),
      slots.status?.(),
      slots.actions?.(),
      slots.meta?.(),
    ])
  },
})
const ErrorStub = defineComponent({
  name: 'ErrorState',
  props: ['message'],
  setup(props) { return () => h('div', { 'data-test': 'error' }, props.message) },
})
const ButtonStub = defineComponent({
  name: 'ElButton',
  props: ['disabled', 'title', 'loading'],
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () => h('button', {
      disabled: props.disabled,
      title: props.title,
      onClick: () => {
        if (!props.disabled) emit('click')
      },
    }, slots.default?.())
  },
})
const TagStub = defineComponent({
  name: 'ElTag',
  setup(_, { slots }) { return () => h('span', slots.default?.()) },
})
const AlertStub = defineComponent({
  name: 'ElAlert',
  props: ['title', 'type'],
  setup(props) { return () => h('div', { 'data-test': 'alert', 'data-type': props.type }, props.title) },
})
const TabsStub = defineComponent({
  name: 'ElTabs',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(props, { slots }) {
    return () => h('div', { 'data-test': 'tabs', 'data-active': props.modelValue }, slots.default?.())
  },
})
const TabPaneStub = defineComponent({
  name: 'ElTabPane',
  props: ['name', 'label'],
  setup(props, { slots }) {
    return () => h('section', { 'data-pane': props.name }, [slots.label?.(), slots.default?.()])
  },
})
const CollapseStub = defineComponent({
  name: 'ElCollapse',
  setup(_, { slots }) { return () => h('div', slots.default?.()) },
})
const CollapseItemStub = defineComponent({
  name: 'ElCollapseItem',
  props: ['name'],
  setup(_, { slots }) { return () => h('div', [slots.title?.(), slots.default?.()]) },
})
const TableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    return () => h('div', { class: 'el-table-stub' }, (props.data as Record<string, unknown>[]).map((row) => {
      const columns = slots.default?.() || []
      return h('div', { class: 'el-table-stub__row' }, columns.map((column) => {
        const renderCell = (column as { children?: { default?: (scope: { row: unknown }) => VNodeChild } })
          .children?.default
        return h('div', { class: 'el-table-stub__cell' }, [renderCell ? renderCell({ row }) : null] as VNodeChild[])
      }))
    }))
  },
})

const wrappers: VueWrapper[] = []
const hosts: HTMLElement[] = []

function mountView(attachTo?: HTMLElement) {
  const wrapper = mount(AssetEntityDetailView, {
    ...(attachTo ? { attachTo } : {}),
    global: {
      stubs: {
        TaskPageHeader: TaskHeaderStub,
        ErrorState: ErrorStub,
        LoadingState: true,
        EmptyState: true,
        BusinessStatusBadge: true,
        'el-button': ButtonStub,
        'el-tag': TagStub,
        'el-alert': AlertStub,
        'el-tabs': TabsStub,
        'el-tab-pane': TabPaneStub,
        'el-table': TableStub,
        'el-table-column': true,
        'el-pagination': true,
        'el-collapse': CollapseStub,
        'el-collapse-item': CollapseItemStub,
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

/** 详情页在真实后台里挂在 .admin-shell__content 内，测试用同样的父容器提供剩余高度。 */
function mountInShell(clientHeight: number) {
  const host = document.createElement('div')
  host.className = 'admin-shell__content'
  Object.defineProperty(host, 'clientHeight', { configurable: true, get: () => clientHeight })
  document.body.appendChild(host)
  hosts.push(host)
  return { wrapper: mountView(host), host }
}

function stubDesktopViewport() {
  vi.spyOn(window, 'matchMedia').mockImplementation(((query: string) => ({
    matches: true,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia)
}

/** 所有稳定区域取同一高度，测试通过改变它模拟长标题、提示增减等真实内容变化。 */
function stubStableAreaHeight(initial: number) {
  let height = initial
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({
    height,
    width: 0,
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  }) as DOMRect)
  return (next: number) => { height = next }
}

function fieldsOverview(count: number) {
  const items: OverviewColumn[] = Array.from({ length: count }, (_, index) => ({
    entityId: 100 + index,
    columnMetaId: index + 1,
    name: `column_${index + 1}`,
    displayName: `字段 ${index + 1}`,
    dataType: 'INT',
    nullable: true,
    primaryKey: false,
    comment: '',
    governanceStatus: 'NORMAL',
    tableName: 'orders',
    ordinalPosition: index + 1,
    usageLimits: [],
  }))
  return overview(1, 'orders', {
    columns: { status: 'OK', items },
    counts: { columns: count, structureRelations: 0, terms: 0, tags: 0, fieldTags: 0, lineage: 0, downstream: 0 },
  })
}

function isFixed(wrapper: VueWrapper) {
  return wrapper.find('[data-test="entity-detail-page"]').classes()
    .includes('entity-detail-page--fixed')
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function overview(id: number, name: string, extra: Partial<CatalogEntityOverview> = {}): CatalogEntityOverview {
  return {
    entity: {
      id, entityType: 'TABLE', entityUuid: `uuid-${id}`, fqn: `ds.db.${name}`,
      name, displayName: name, version: 1,
    },
    entityType: 'TABLE',
    datasource: { id: 1, name: 'ds', databaseName: 'db' },
    sourceSnapshot: { id: 10, version: 1, status: 'PUBLISHED' },
    publishedSnapshot: { id: 10, version: 1, status: 'PUBLISHED', entitySyncStatus: 'SYNCED' },
    sourceVerified: true,
    sourceMatchesPublished: true,
    sourceMessage: '',
    table: { tableName: name, displayName: name, comment: '', governanceStatus: 'NORMAL', tableType: 'BASE TABLE' },
    columns: { status: 'OK', items: [] },
    counts: { columns: 0, structureRelations: 0, terms: 0, tags: 0, fieldTags: 0, lineage: 0, downstream: 0 },
    structureRelations: { status: 'OK', items: [] },
    terms: { status: 'OK', items: [] },
    tags: { status: 'OK', items: [] },
    fieldTags: { status: 'OK', items: [] },
    lineage: { status: 'OK', items: [] },
    downstream: { status: 'OK', items: [] },
    ...extra,
  }
}

function columnOverview(extra: Partial<CatalogEntityOverview> = {}): CatalogEntityOverview {
  const column: OverviewColumn = {
    entityId: 101, columnMetaId: 21, name: 'amount', displayName: '金额',
    dataType: 'DECIMAL(10,2)', nullable: true, primaryKey: false,
    comment: '金额', governanceStatus: 'NORMAL', tableName: 'orders', ordinalPosition: 1,
    usageLimits: [],
  }
  return overview(1, 'orders', {
    entityType: 'COLUMN',
    entity: {
      id: 101, entityType: 'COLUMN', entityUuid: 'uuid-col', fqn: 'ds.db.orders.amount',
      name: 'amount', displayName: '金额', version: 1,
    },
    table: { tableName: 'orders', displayName: '订单', comment: '', governanceStatus: 'NORMAL', tableType: 'BASE TABLE' },
    column,
    columns: { status: 'OK', items: [column] },
    counts: { columns: 1, structureRelations: 0, terms: 0, tags: 0, fieldTags: 0, lineage: 0, downstream: 0 },
    ...extra,
  })
}

function term(termId: number, extra: Partial<OverviewTermItem> = {}): OverviewTermItem {
  return {
    termId,
    glossaryId: 5,
    name: `term-${termId}`,
    displayName: `术语 ${termId}`,
    description: '业务定义',
    status: 'APPROVED',
    currentlyValid: true,
    verificationCode: 'VALID',
    linkMode: 'DIRECT_COLUMN',
    viaColumns: [],
    ...extra,
  }
}

function title(wrapper: VueWrapper) {
  return wrapper.find('[data-test="title"]').text()
}

function eyebrow(wrapper: VueWrapper) {
  return wrapper.find('[data-test="type-tag"]').text()
}

function tableOverviewWithType(tableType?: string) {
  return overview(1, 'orders', {
    table: {
      tableName: 'orders',
      displayName: '订单',
      comment: '',
      governanceStatus: 'NORMAL',
      ...(tableType === undefined ? {} : { tableType }),
    },
  })
}

function activeTab(wrapper: VueWrapper) {
  return wrapper.find('[data-test="tabs"]').attributes('data-active')
}

beforeEach(() => {
  api.getEntityOverview.mockReset()
  api.unconfirmEntityTag.mockReset().mockResolvedValue({ data: null })
  iam.canOnDatasource.mockReset().mockReturnValue(true)
  iam.load.mockResolvedValue(undefined)
  iam.loaded = true
  holders.route.params.entityId = '1'
  holders.route.query = {}
  holders.router.push.mockReset()
  holders.router.replace.mockReset()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
})

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  hosts.splice(0).forEach((host) => host.remove())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('asset detail request isolation', () => {
  it('does not let an older A response overwrite newer B content', async () => {
    const first = deferred<{ data: CatalogEntityOverview }>()
    const second = deferred<{ data: CatalogEntityOverview }>()
    api.getEntityOverview.mockImplementation((id: number) => id === 1 ? first.promise : second.promise)

    const wrapper = mountView()
    await nextTick()
    expect(api.getEntityOverview).toHaveBeenCalledWith(1)

    holders.route.params.entityId = '2'
    await nextTick()
    expect(api.getEntityOverview).toHaveBeenCalledWith(2)

    second.resolve({ data: overview(2, 'B') })
    await flushPromises()
    await nextTick()
    expect(title(wrapper)).toBe('B')

    first.resolve({ data: overview(1, 'A') })
    await flushPromises()
    await nextTick()
    expect(title(wrapper)).toBe('B')
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })

  it('does not let an older error overwrite newer content', async () => {
    const first = deferred<{ data: CatalogEntityOverview }>()
    const second = deferred<{ data: CatalogEntityOverview }>()
    api.getEntityOverview.mockImplementation((id: number) => id === 1 ? first.promise : second.promise)

    const wrapper = mountView()
    await nextTick()
    holders.route.params.entityId = '2'
    await nextTick()

    second.resolve({ data: overview(2, 'B') })
    await flushPromises()
    await nextTick()
    first.reject(new Error('旧对象读取失败'))
    await flushPromises()
    await nextTick()

    expect(title(wrapper)).toBe('B')
    expect(wrapper.find('[data-test="error"]').exists()).toBe(false)
  })

  it('stops writing state after the component is unmounted', async () => {
    const request = deferred<{ data: CatalogEntityOverview }>()
    api.getEntityOverview.mockReturnValue(request.promise)
    const wrapper = mountView()
    await nextTick()

    wrapper.unmount()
    request.reject(new Error('unmounted request failed'))
    await flushPromises()

    expect(api.getEntityOverview).toHaveBeenCalledWith(1)
  })

  it('cancels a tag write when the object changes during confirmation', async () => {
    const tag = {
      id: 5, name: 'PII.手机号', displayName: 'PII.手机号', kind: 'CONFIRMED' as const,
      source: '分类标签（人工确认）', confirmed: true,
    }
    api.getEntityOverview.mockResolvedValueOnce({
      data: overview(1, 'A', { tags: { status: 'OK', items: [tag] } }),
    })
    const confirm = deferred<string>()
    vi.mocked(ElMessageBox.confirm).mockReturnValue(confirm.promise as never)
    const nextObject = deferred<{ data: CatalogEntityOverview }>()
    api.getEntityOverview.mockReturnValueOnce(nextObject.promise)

    const wrapper = mountView()
    await flushPromises()
    const cancel = wrapper.findAll('button').find((button) => button.text().includes('取消关联'))
    expect(cancel).toBeTruthy()
    await cancel!.trigger('click')

    holders.route.params.entityId = '2'
    await nextTick()
    confirm.resolve('confirm')
    await flushPromises()

    expect(api.unconfirmEntityTag).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('对象已切换，已取消原标签操作。')
    nextObject.resolve({ data: overview(2, 'B') })
    await flushPromises()
  })
})

describe('detail tab route state', () => {
  it('defaults a table to the fields tab and restores a requested tab', async () => {
    api.getEntityOverview.mockResolvedValue({ data: overview(1, 'orders') })

    const wrapper = mountView()
    await flushPromises()
    expect(activeTab(wrapper)).toBe('fields')

    holders.route.query.tab = 'lineage'
    await nextTick()
    expect(activeTab(wrapper)).toBe('lineage')
  })

  it('defaults a column to business terms even when a table-only tab was requested', async () => {
    holders.route.query.tab = 'fields'
    api.getEntityOverview.mockResolvedValue({ data: columnOverview() })

    const wrapper = mountView()
    await flushPromises()
    expect(activeTab(wrapper)).toBe('terms')
  })

  it('writes tab selection to the route query and keeps catalog filters', async () => {
    holders.route.query = { q: '订单', type: 'TABLE', view: 'catalog' }
    api.getEntityOverview.mockResolvedValue({ data: overview(1, 'orders') })

    const wrapper = mountView()
    await flushPromises()
    wrapper.findComponent(TabsStub).vm.$emit('update:modelValue', 'tags')
    await nextTick()

    expect(activeTab(wrapper)).toBe('tags')
    expect(holders.router.replace).toHaveBeenCalledWith({
      query: { q: '订单', type: 'TABLE', view: 'catalog', tab: 'tags' },
    })
  })

  it('returns to the catalog with filters but without the detail tab', async () => {
    holders.route.query = { q: '订单', type: 'TABLE', view: 'catalog', tab: 'tags' }
    api.getEntityOverview.mockResolvedValue({ data: overview(1, 'orders') })

    const wrapper = mountView()
    await flushPromises()
    const back = wrapper.findAll('button').find((button) => button.text().includes('返回目录'))
    await back!.trigger('click')

    expect(holders.router.push).toHaveBeenCalledWith({
      path: '/admin/assets',
      query: { q: '订单', type: 'TABLE', view: 'catalog' },
    })
  })

  it('keeps unavailable tabs in place and shows the forbidden reason', async () => {
    api.getEntityOverview.mockResolvedValue({
      data: columnOverview({
        terms: { status: 'FORBIDDEN', message: '无权查看业务术语', items: [] },
      }),
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('无权')
    expect(wrapper.find('[data-pane="terms"]').text()).toContain('无权查看业务术语')
    expect(wrapper.find('[data-pane="terms"]').text()).not.toContain('0 个')
  })

  it('summarizes repeated verification reasons with real counts', async () => {
    api.getEntityOverview.mockResolvedValue({
      data: columnOverview({
        terms: {
          status: 'OK',
          items: [
            term(1, { currentlyValid: false, verificationCode: 'EVIDENCE_MISSING', validityMessage: '历史审核证据不足' }),
            term(2, { currentlyValid: false, verificationCode: 'EVIDENCE_MISSING', validityMessage: '历史审核证据不足' }),
            term(3),
          ],
        },
        counts: { columns: 1, structureRelations: 0, terms: 3, tags: 0, fieldTags: 0, lineage: 0, downstream: 0 },
      }),
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-pane="terms"]').text()).toContain('历史审核证据不足 2 条')
  })
})

describe('object type display', () => {
  it.each([
    ['TABLE', '普通表'],
    ['BASE TABLE', '普通表'],
    ['VIEW', '视图'],
    ['SYSTEM VIEW', '系统视图'],
  ])('maps table type %s to %s', async (raw, expected) => {
    api.getEntityOverview.mockResolvedValue({ data: tableOverviewWithType(raw) })

    const wrapper = mountView()
    await flushPromises()

    expect(eyebrow(wrapper)).toBe(expected)
  })

  it('shows 表对象 instead of inventing 普通表 when the real type is missing', async () => {
    api.getEntityOverview.mockResolvedValue({ data: tableOverviewWithType(undefined) })

    const wrapper = mountView()
    await flushPromises()

    expect(eyebrow(wrapper)).toBe('表对象')
  })

  it('keeps an unknown table type as 表对象 and exposes the raw value in technical info', async () => {
    api.getEntityOverview.mockResolvedValue({ data: tableOverviewWithType('MATERIALIZED VIEW') })

    const wrapper = mountView()
    await flushPromises()

    expect(eyebrow(wrapper)).toBe('表对象')
    expect(eyebrow(wrapper)).not.toContain('普通表')
    expect(wrapper.text()).toContain('MATERIALIZED VIEW')
  })

  it('does not repeat the table type as a basic-info row', async () => {
    api.getEntityOverview.mockResolvedValue({ data: tableOverviewWithType('BASE TABLE') })

    const wrapper = mountView()
    await flushPromises()

    const facts = wrapper.find('.entity-detail__facts')
    expect(wrapper.findAll('.entity-detail__fact')).toHaveLength(4)
    expect(facts.text()).not.toContain('表类型')
    expect(facts.text()).toContain('数据源')
    expect(facts.text()).toContain('数据库')
    expect(facts.text()).toContain('表名')
    expect(facts.text()).toContain('中文名称')
  })
})

describe('tag governance counts', () => {
  it('does not show a mixed total on the tags tab and marks table field tags as not aggregated', async () => {
    const confirmed = {
      id: 5, name: 'PII.手机号', displayName: 'PII.手机号', kind: 'CONFIRMED' as const,
      source: '分类标签（人工确认）', confirmed: true,
    }
    api.getEntityOverview.mockResolvedValue({
      data: overview(1, 'orders', {
        tags: { status: 'OK', items: [confirmed] },
        counts: { columns: 0, structureRelations: 0, terms: 0, tags: 1, fieldTags: 0, lineage: 0, downstream: 0 },
      }),
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-tab="tags"]').text()).toBe('标签与治理')
    const head = wrapper.find('[data-test="field-tags-head"]')
    expect(head.text()).toContain('未汇总')
    expect(head.text()).toContain('按字段查看')
    expect(head.text()).not.toContain('0 个')
    expect(wrapper.find('[data-pane="tags"]').text()).toContain('目录标签')
  })

  it('keeps a real zero for a column field-tag section that is OK and empty', async () => {
    api.getEntityOverview.mockResolvedValue({
      data: columnOverview({ fieldTags: { status: 'OK', items: [] } }),
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-test="field-tags-head"]').text()).toContain('0 个')
  })

  it('keeps the accurate unavailable state instead of converting it to zero', async () => {
    api.getEntityOverview.mockResolvedValue({
      data: columnOverview({
        fieldTags: { status: 'FORBIDDEN', message: '无权查看字段治理标签', items: [] },
      }),
    })

    const wrapper = mountView()
    await flushPromises()

    const head = wrapper.find('[data-test="field-tags-head"]')
    expect(head.text()).toContain('无权')
    expect(head.text()).not.toContain('0 个')
    expect(wrapper.find('[data-pane="tags"]').text()).toContain('无权查看字段治理标签')
  })
})

describe('term definition expansion', () => {
  function termsOverview(description: string) {
    return columnOverview({
      terms: { status: 'OK', items: [term(1, { description })] },
      counts: { columns: 1, structureRelations: 0, terms: 1, tags: 0, fieldTags: 0, lineage: 0, downstream: 0 },
    })
  }

  it('does not render an expand control when the definition is not actually truncated', async () => {
    api.getEntityOverview.mockResolvedValue({ data: termsOverview('已完整显示的业务定义') })

    const wrapper = mountView()
    await flushPromises()

    // 未溢出时仍保留三行折叠样式，但不应出现无效的展开入口。
    expect(wrapper.find('[data-test="term-definition"]').classes())
      .toContain('entity-detail__term-definition--clamped')
    expect(wrapper.find('[data-test="term-toggle"]').exists()).toBe(false)
  })

  it('shows the control only when the rendered definition overflows and toggles accessibly', async () => {
    api.getEntityOverview.mockResolvedValue({ data: termsOverview('需要展开的长业务定义内容') })

    const wrapper = mountView()
    await flushPromises()
    const definition = wrapper.find('[data-test="term-definition"]')
    Object.defineProperty(definition.element, 'scrollHeight', { configurable: true, value: 120 })
    Object.defineProperty(definition.element, 'clientHeight', { configurable: true, value: 60 })
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    await nextTick()

    const toggle = wrapper.find('[data-test="term-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toBe('展开完整定义')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')
    await nextTick()
    const expanded = wrapper.find('[data-test="term-toggle"]')
    expect(expanded.text()).toBe('收起完整定义')
    expect(expanded.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('[data-test="term-definition"]').classes())
      .not.toContain('entity-detail__term-definition--clamped')

    await expanded.trigger('click')
    await nextTick()
    await nextTick()
    const collapsed = wrapper.find('[data-test="term-toggle"]')
    expect(collapsed.text()).toBe('展开完整定义')
    expect(collapsed.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-test="term-definition"]').classes())
      .toContain('entity-detail__term-definition--clamped')
  })
})

describe('source state', () => {
  it('keeps the actual source and published target distinct when they differ', async () => {
    api.getEntityOverview.mockResolvedValue({
      data: overview(1, 'orders', {
        publishedSnapshot: { id: 11, version: 2, status: 'PUBLISHED', entitySyncStatus: 'SYNCED' },
        sourceMatchesPublished: false,
      }),
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('快照 v1')
    expect(wrapper.text()).toContain('当前发布 v2')
    expect(wrapper.text()).toContain('详情按来源快照 v1 读取，当前发布目标为 v2。')
  })

  it('keeps a historical missing sync status visible instead of treating it as success', async () => {
    api.getEntityOverview.mockResolvedValue({
      data: overview(1, 'orders', {
        publishedSnapshot: { id: 10, version: 1, status: 'PUBLISHED' },
      }),
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('与发布目标一致 · 快照 v1')
    expect(wrapper.text()).toContain('没有记录目录同步状态，结果未经确认')
  })
})

describe('field governance location', () => {
  it('passes the displayed source snapshot, table and columnMetaId without entity id', async () => {
    api.getEntityOverview.mockResolvedValue({ data: columnOverview() })

    const wrapper = mountView()
    await flushPromises()
    const button = wrapper.findAll('button').find((item) => item.text().includes('进入字段治理'))
    expect(button).toBeTruthy()
    expect(button!.attributes('disabled')).toBeUndefined()
    await button!.trigger('click')

    expect(holders.router.push).toHaveBeenCalledWith({
      path: '/admin/governance/fields',
      query: {
        datasourceId: '1',
        snapshotId: '10',
        tableName: 'orders',
        columnMetaId: '21',
        tab: 'semantics',
      },
    })
  })

  it('does not require tag management permission to open field governance', async () => {
    iam.canOnDatasource.mockImplementation((code: string) => code === 'governance:field:view')
    api.getEntityOverview.mockResolvedValue({ data: columnOverview() })

    const wrapper = mountView()
    await flushPromises()
    const button = wrapper.findAll('button').find((item) => item.text().includes('进入字段治理'))
    expect(button!.attributes('disabled')).toBeUndefined()
    await button!.trigger('click')
    expect(holders.router.push).toHaveBeenCalledWith(expect.objectContaining({
      path: '/admin/governance/fields',
    }))
  })

  it('disables the entry and explains the missing view capability', async () => {
    iam.canOnDatasource.mockReturnValue(false)
    api.getEntityOverview.mockResolvedValue({ data: columnOverview() })

    const wrapper = mountView()
    await flushPromises()
    const button = wrapper.findAll('button').find((item) => item.text().includes('进入字段治理'))
    expect(button!.attributes('disabled')).toBe('')
    expect(wrapper.text()).toContain('governance:field:view')
    await button!.trigger('click')
    expect(holders.router.push).not.toHaveBeenCalledWith(expect.objectContaining({
      path: '/admin/governance/fields',
    }))
  })

  it('opens the lineage workspace with only datasourceId and entityId', async () => {
    iam.canOnDatasource.mockImplementation((code: string) => code === 'lineage:view')
    api.getEntityOverview.mockResolvedValue({ data: overview(1, 'orders') })

    const wrapper = mountView()
    await flushPromises()
    const button = wrapper.findAll('button').find((item) => item.text().includes('在血缘工作台中查看'))
    expect(button!.attributes('disabled')).toBeUndefined()
    await button!.trigger('click')

    expect(holders.router.push).toHaveBeenCalledWith({
      path: '/admin/assets/lineage',
      query: { datasourceId: '1', entityId: '1' },
    })
  })

  it('disables the lineage workspace entry without lineage:view', async () => {
    iam.canOnDatasource.mockReturnValue(false)
    api.getEntityOverview.mockResolvedValue({ data: overview(1, 'orders') })

    const wrapper = mountView()
    await flushPromises()
    const button = wrapper.findAll('button').find((item) => item.text().includes('在血缘工作台中查看'))
    expect(button!.attributes('disabled')).toBe('')
    expect(button!.attributes('title')).toContain('lineage:view')
    expect(holders.router.push).not.toHaveBeenCalledWith(expect.objectContaining({
      path: '/admin/assets/lineage',
    }))
  })

  it('opens a column detail with the catalog context but without the table detail tab', async () => {
    holders.route.query = { q: '订单', type: 'TABLE', tab: 'fields' }
    const column: OverviewColumn = {
      entityId: 101, columnMetaId: 21, name: 'amount', displayName: '金额',
      dataType: 'DECIMAL(10,2)', nullable: true, primaryKey: false,
      comment: '金额', governanceStatus: 'NORMAL', tableName: 'orders', ordinalPosition: 1,
      usageLimits: [],
    }
    api.getEntityOverview.mockResolvedValue({
      data: overview(1, 'orders', {
        columns: { status: 'OK', items: [column] },
        counts: { columns: 1, structureRelations: 0, terms: 0, tags: 0, fieldTags: 0, lineage: 0, downstream: 0 },
      }),
    })

    const wrapper = mountView()
    await flushPromises()
    const open = wrapper.findAll('button').find((button) => button.text().includes('查看字段'))
    expect(open).toBeTruthy()
    await open!.trigger('click')

    expect(holders.router.push).toHaveBeenCalledWith({
      path: '/admin/assets/entities/101',
      query: { q: '订单', type: 'TABLE' },
    })
  })
})

describe('desktop fixed scrolling layout', () => {
  it('uses the remaining body height with a fixed table header and visible pagination', async () => {
    stubDesktopViewport()
    stubStableAreaHeight(80)
    const base = fieldsOverview(13)
    const relation = {
      id: 1, relationType: 'CONTAINS', direction: 'OUT', sourceType: 'TABLE', sourceId: 1,
      sourceName: 'orders', targetType: 'COLUMN', targetId: 2, targetName: 'column_1',
      bindingStatus: 'BOUND',
    }
    const lineageItem = {
      id: 2, relationType: 'LINEAGE', direction: 'UPSTREAM' as const, sourceType: 'TABLE',
      sourceId: 3, sourceName: 'source_orders', targetType: 'TABLE', targetId: 1,
      targetName: 'orders', lineageType: 'TABLE', bindingStatus: 'BOUND',
    }
    const downstreamItem = { ...lineageItem, id: 3, direction: 'DOWNSTREAM' as const }
    api.getEntityOverview.mockResolvedValue({
      data: {
        ...base,
        structureRelations: { status: 'OK', items: [relation] },
        lineage: { status: 'OK', items: [lineageItem] },
        downstream: { status: 'OK', items: [downstreamItem] },
      },
    })

    const { wrapper } = mountInShell(900)
    await flushPromises()
    await nextTick()
    await nextTick()

    expect(isFixed(wrapper)).toBe(true)
    const fieldsPane = wrapper.find('[data-pane="fields"]')
    const region = fieldsPane.find('.entity-detail__table-region')
    expect(region.exists()).toBe(true)
    // 固定表头依赖 el-table 的 height；自然模式下不设置高度。
    expect(region.find('.el-table-stub').attributes('height')).toBe('100%')
    // 有表格的其他 Tab 采用相同规则。
    for (const name of ['relations', 'lineage', 'downstream']) {
      expect(wrapper.find(`[data-pane="${name}"] .entity-detail__table-region .el-table-stub`)
        .attributes('height')).toBe('100%')
    }
    // 分页位于表格滚动区域之外，表体滚动时保持可见。
    expect(region.find('el-pagination-stub').exists()).toBe(false)
    expect(fieldsPane.find('el-pagination-stub').exists()).toBe(true)
  })

  it('falls back to natural content scrolling when the remaining height is insufficient', async () => {
    stubDesktopViewport()
    stubStableAreaHeight(120)
    api.getEntityOverview.mockResolvedValue({ data: fieldsOverview(13) })

    const { wrapper } = mountInShell(500)
    await flushPromises()
    await nextTick()
    await nextTick()

    expect(isFixed(wrapper)).toBe(false)
    expect(wrapper.find('[data-pane="fields"] .el-table-stub').attributes('height')).toBeUndefined()
  })

  it('re-evaluates after the window or stable content changes and can return to fixed mode', async () => {
    stubDesktopViewport()
    const setStableHeight = stubStableAreaHeight(120)
    api.getEntityOverview.mockResolvedValue({ data: fieldsOverview(2) })

    const { wrapper } = mountInShell(900)
    await flushPromises()
    await nextTick()
    await nextTick()
    expect(isFixed(wrapper)).toBe(true)

    // 长标题或新增提示把稳定区域撑大，正文空间不足时退出固定模式。
    setStableHeight(220)
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    await nextTick()
    expect(isFixed(wrapper)).toBe(false)

    setStableHeight(60)
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    await nextTick()
    expect(isFixed(wrapper)).toBe(true)
  })

  it('drops fixed mode when an asynchronous warning grows the stable area', async () => {
    stubDesktopViewport()
    stubStableAreaHeight(180)
    api.getEntityOverview.mockImplementation((id: number) => Promise.resolve({
      data: id === 1
        ? overview(1, 'orders')
        : overview(2, 'orders', { sourceMessage: '来源快照存在差异，结构可能尚未同步。' }),
    }))

    const { wrapper } = mountInShell(1000)
    await flushPromises()
    await nextTick()
    await nextTick()
    expect(isFixed(wrapper)).toBe(true)

    holders.route.params.entityId = '2'
    await nextTick()
    await flushPromises()
    await nextTick()
    await nextTick()

    expect(wrapper.text()).toContain('来源快照存在差异')
    expect(isFixed(wrapper)).toBe(false)
  })

  it('disconnects layout observation and listeners when the detail page unmounts', async () => {
    stubDesktopViewport()
    const observers: Array<{ disconnect: ReturnType<typeof vi.fn> }> = []
    class FakeResizeObserver {
      disconnect = vi.fn()
      observe = vi.fn()
      unobserve = vi.fn()
      constructor(_callback: ResizeObserverCallback) {
        observers.push(this)
      }
    }
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    api.getEntityOverview.mockResolvedValue({ data: fieldsOverview(2) })
    const removeListener = vi.spyOn(window, 'removeEventListener')

    const { wrapper } = mountInShell(900)
    await flushPromises()
    await nextTick()

    wrapper.unmount()
    expect(observers.length).toBeGreaterThan(0)
    observers.forEach((observer) => expect(observer.disconnect).toHaveBeenCalled())
    expect(removeListener).toHaveBeenCalledWith('resize', expect.any(Function))
  })
})
