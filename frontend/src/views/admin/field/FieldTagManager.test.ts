// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import FieldTagManager from './FieldTagManager.vue'

const route = vi.hoisted(() => ({ query: {} as Record<string, string> }))
vi.mock('vue-router', () => ({ useRoute: () => route }))

const context = vi.hoisted(() => ({
  datasourceId: 1 as number | undefined,
  snapshotId: undefined as number | undefined,
  initialize: vi.fn(),
  selectSnapshot: vi.fn(),
  ensureSnapshot: vi.fn(),
}))
vi.mock('../../../stores/adminContext', () => ({ useAdminContextStore: () => context }))
vi.mock('../../../stores/iamS1', () => ({
  useIamS1Store: () => ({ canOnDatasource: () => true }),
}))
vi.mock('../../../composables/useGsapMotion', () => ({
  useGsapMotion: () => ({ reveal: vi.fn(), withContext: (callback: () => void) => callback() }),
}))

const api = vi.hoisted(() => ({
  listPredefinedTags: vi.fn(),
  getFieldTags: vi.fn(),
  batchAddFieldTags: vi.fn(),
  autoTagByPattern: vi.fn(),
  listSnapshots: vi.fn(),
  listSnapshotTables: vi.fn(),
  listSnapshotTableColumns: vi.fn(),
}))
vi.mock('../../../api/admin/field', () => ({
  listPredefinedTags: api.listPredefinedTags,
  getFieldTags: api.getFieldTags,
  batchAddFieldTags: api.batchAddFieldTags,
  autoTagByPattern: api.autoTagByPattern,
}))
vi.mock('../../../api/admin/governance', () => ({
  listSnapshotTables: api.listSnapshotTables,
  listSnapshotTableColumns: api.listSnapshotTableColumns,
}))
vi.mock('../../../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots }))

const AlertStub = defineComponent({
  props: ['title', 'type'],
  setup(props) { return () => h('div', { 'data-test': 'alert' }, props.title) },
})

function snapshotItem(id = 99) {
  return {
    id,
    snapshotVersion: 3,
    datasourceId: 1,
    datasourceName: 'ds',
    tableCount: 1,
    columnCount: 2,
    status: 'PUBLISHED',
    createdAt: '2026-10-18T00:00:00',
  }
}

function column(id = 21, columnName = 'amount') {
  return {
    id,
    tableMetaId: 1,
    tableName: 'orders',
    columnName,
    columnComment: '金额',
    dataType: 'DECIMAL(10,2)',
    isNullable: 1,
    isPrimaryKey: 0,
    ordinalPosition: 1,
    governanceStatus: 'NORMAL',
  }
}

function mountView(props: { embedded?: boolean } = {}) {
  return mount(FieldTagManager, {
    props,
    global: {
      stubs: {
        'el-select': { template: '<div><slot /></div>' },
        'el-option': true,
        'el-table': true,
        'el-table-column': true,
        'el-empty': true,
        'el-dialog': true,
        'el-button': true,
        'el-alert': AlertStub,
      },
      directives: { loading: {} },
    },
  })
}

beforeEach(() => {
  route.query = {
    datasourceId: '1',
    snapshotId: '99',
    tableName: 'orders',
    columnMetaId: '21',
  }
  context.datasourceId = 1
  context.snapshotId = undefined
  context.initialize.mockReset().mockResolvedValue(undefined)
  context.selectSnapshot.mockReset()
  context.ensureSnapshot.mockReset().mockResolvedValue(snapshotItem())
  api.listPredefinedTags.mockReset().mockResolvedValue({ data: [] })
  api.getFieldTags.mockReset().mockResolvedValue({ data: [] })
  api.batchAddFieldTags.mockReset()
  api.autoTagByPattern.mockReset()
  api.listSnapshots.mockReset().mockResolvedValue({ data: { records: [] } })
  api.listSnapshotTables.mockReset().mockResolvedValue({ data: [{ id: 1, tableName: 'orders' }] })
  api.listSnapshotTableColumns.mockReset().mockResolvedValue({ data: [column()] })
})

describe('field governance location from asset detail', () => {
  it('loads a snapshot outside the first 50 by id and locates the exact column', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(api.listSnapshots).toHaveBeenCalledWith({ datasourceId: 1, page: 1, size: 50 })
    expect(context.ensureSnapshot).toHaveBeenCalledWith(99)
    expect(api.listSnapshotTables).toHaveBeenCalledWith(99)
    expect(api.listSnapshotTableColumns).toHaveBeenCalledWith(99, 'orders')
    expect(api.getFieldTags).toHaveBeenCalledWith(21)
    expect(wrapper.findAll('[data-test="alert"]')).toHaveLength(0)
  })

  it('keeps an explicit snapshot unavailable instead of falling back to another snapshot', async () => {
    context.ensureSnapshot.mockRejectedValue(new Error('无权访问该快照'))
    const wrapper = mountView()
    await flushPromises()

    expect(api.listSnapshotTables).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('未切换到其他快照')
  })

  it('reports a missing requested table without selecting another table', async () => {
    route.query.tableName = 'missing_table'
    const wrapper = mountView()
    await flushPromises()

    expect(api.listSnapshotTableColumns).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('未找到表')
  })

  it('reports a missing requested column without selecting another column', async () => {
    api.listSnapshotTableColumns.mockResolvedValue({ data: [column(22, 'price')] })
    const wrapper = mountView()
    await flushPromises()

    expect(api.getFieldTags).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('未找到指定字段')
  })
})

describe('字段治理内嵌模式', () => {
  it('只读取公共快照，不再显示或请求第二套快照选择', async () => {
    context.snapshotId = 99
    const wrapper = mountView({ embedded: true })
    await flushPromises()
    await flushPromises()
    expect(api.listSnapshots).not.toHaveBeenCalled()
    expect(context.selectSnapshot).not.toHaveBeenCalled()
    expect(api.listSnapshotTables).toHaveBeenCalledWith(99)
    expect(wrapper.html()).not.toContain('选择快照')
    wrapper.unmount()
  })
  it('未选择表时只显示一个引导状态，不同时绘制空表', async () => {
    context.snapshotId = 99
    route.query = { datasourceId: '1', snapshotId: '99' }
    const wrapper = mountView({ embedded: true })
    await flushPromises()
    await flushPromises()
    expect(wrapper.findAll('el-empty-stub')).toHaveLength(1)
    expect(wrapper.find('el-table-stub').exists()).toBe(false)
    expect(wrapper.find('el-empty-stub').attributes('description')).toContain('选择一张表')
    wrapper.unmount()
  })
})
