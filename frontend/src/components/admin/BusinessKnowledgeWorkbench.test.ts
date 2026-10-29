// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BusinessKnowledgeWorkbench from './BusinessKnowledgeWorkbench.vue'
import { listBusinessKnowledge } from '../../api/admin/governanceWorkflow'

vi.mock('../../stores/adminContext', () => ({
  useAdminContextStore: () => ({
    datasourceId: 5,
    snapshotId: 8,
    loading: false,
    routeSyncing: false,
    routeSyncError: '',
  }),
}))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => ({ token: 'synthetic' }) }))
vi.mock('../../stores/iamS1', () => ({
  useIamS1Store: () => ({ loaded: true, errorMessage: '', snapshot: {}, canOnDatasource: () => true }),
}))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('../../api/admin/governanceWorkflow', () => ({
  listBusinessKnowledge: vi.fn(),
  saveBusinessKnowledge: vi.fn(),
  actBusinessKnowledge: vi.fn(),
  governanceError: vi.fn(),
  businessSourceStatus: vi.fn(),
}))
vi.mock('../../api/admin/governance', () => ({
  listSnapshotTables: vi.fn(),
  listSnapshotTableColumns: vi.fn(),
}))
vi.mock('../../api/admin/knowledge', () => ({ generateFromSnapshot: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn() }, ElMessageBox: {} }))

function page() {
  return mount(BusinessKnowledgeWorkbench, {
    global: {
      stubs: {
        ElButton: true,
        ElTable: { props: ['data'], template: '<div>{{data.map(row => row.title).join("|")}}</div>' },
        ElTableColumn: true,
        ElDialog: true,
        GovernanceHistoryDialog: true,
        ElOption: true,
        ElSelect: true,
        ElFormItem: true,
        ElForm: true,
        ElInput: true,
        ElCollapse: true,
        ElCollapseItem: true,
        ElPagination: true,
        ElCheckbox: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<button @click="$emit(\'update:modelValue\', !modelValue)"><slot/></button>',
        },
      },
      directives: { loading: () => {} },
    },
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listBusinessKnowledge).mockResolvedValue({
    data: [
      { id: 4, title: '新草稿', status: 'DRAFT' },
      { id: 3, title: '仍生效的版本', status: 'VALID' },
      { id: 2, title: '旧草稿', status: 'SUPERSEDED_DRAFT' },
      { id: 1, title: '旧有效版', status: 'SUPERSEDED' },
    ],
  } as never)
})
describe('业务知识当前任务与历史', () => {
  it('默认只呈现当前版本及新草稿，避免历史重复占用工作列表', async () => {
    const wrapper = page()
    await flushPromises()
    expect(wrapper.text()).toContain('新草稿')
    expect(wrapper.text()).toContain('仍生效的版本')
    expect(wrapper.text()).not.toContain('旧草稿')
    expect(wrapper.text()).not.toContain('旧有效版')
    wrapper.unmount()
  })
  it('需要追溯时可展开历史版本', async () => {
    const wrapper = page()
    await flushPromises()
    const toggle = wrapper.findAll('button').find((button) => button.text().includes('显示历史修订'))
    expect(toggle).toBeDefined()
    await toggle!.trigger('click')
    expect(wrapper.text()).toContain('旧草稿')
    expect(wrapper.text()).toContain('旧有效版')
    wrapper.unmount()
  })
})
