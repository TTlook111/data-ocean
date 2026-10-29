// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, reactive, ref } from 'vue'
import QueryDatasourceView from './QueryDatasourceView.vue'

const auth = reactive({ token: 'reader', user: { username: 'reader' }, currentUser: null })
const route = reactive({ query: {} })
const router = { push: vi.fn(), replace: vi.fn() }
const iam = { load: vi.fn(), systemAdmin: false, hasAnyAdminCapability: false, errorMessage: '' }
function makeSession() {
  const active = ref({ id: 'current', datasourceId: 1, title: '当前会话', messages: [] as Array<Record<string, unknown>> })
  return {
    loading: ref(false), historyLoading: ref(false), historyError: ref(''), errorMessage: ref(''),
    readinessLoading: ref(false), readinessMap: ref({ 1: { askable: true } }), selectedId: ref(1),
    activeSession: active, activeSessionId: ref('current'), activeMessages: computed(() => active.value.messages),
    selectedDatasource: ref({ id: 1, name: 'source' }), selectedBlockReason: ref(undefined), canAskSelectedDatasource: ref(true),
    datasourceSessions: ref([]), datasources: ref([{ id: 1, name: 'source' }]), keyword: ref(''),
    createSession: vi.fn(), selectSession: vi.fn(), selectDatasource: vi.fn(), startNewSession: vi.fn(),
    fetchDatasources: vi.fn(), removeSession: vi.fn(), loadOlderMessages: vi.fn(), reset: vi.fn(), retryHistory: vi.fn(),
  }
}
function makeSubmit() {
  return { question: ref(''), isQuerying: ref(false), cancelCurrentQuery: vi.fn().mockResolvedValue(undefined),
    followLatestResult: vi.fn(), invalidateContext: vi.fn(), sendQuestion: vi.fn(), isLocallyResumable: () => false,
    prepareClarification: vi.fn(), retryQuery: vi.fn(), continueWaiting: vi.fn(),
  }
}
let session = makeSession(), submit = makeSubmit()
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => router }))
vi.mock('../../stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => iam }))
vi.mock('../../composables/useQuerySession', () => ({ useQuerySession: () => session }))
vi.mock('../../composables/useQuerySubmit', () => ({ useQuerySubmit: () => submit }))
vi.mock('./QueryAnswer.vue', () => ({ default: { name: 'QueryAnswer', template: '<div>fresh-answer-slot</div>' } }))
const wrappers: ReturnType<typeof mount>[] = []
async function page() {
  const wrapper = mount(QueryDatasourceView, { global: { stubs: {
    QuerySidebar: { name: 'QuerySidebar', emits: ['select-session', 'new-session', 'select-datasource'], template: '<nav />' },
    QueryInput: { name: 'QueryInput', template: '<div />' }, QueryAnswer: { name: 'QueryAnswer', template: '<div>fresh-answer-slot</div>' },
    RouterLink: { template: '<a><slot /></a>' },
  } } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => { session = makeSession(); submit = makeSubmit(); vi.clearAllMocks() })
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0 })
describe('对话导航意图与正文保护', () => {
  it('先选 A 慢取消、再选 B 快取消，旧 A 不得反向接管会话', async () => {
    const wrapper = await page()
    let resolveOld!: () => void
    submit.cancelCurrentQuery.mockReturnValueOnce(new Promise<void>(resolve => { resolveOld = resolve })).mockResolvedValueOnce(undefined)
    const sidebar = wrapper.findComponent({ name: 'QuerySidebar' })
    sidebar.vm.$emit('select-session', 'a')
    sidebar.vm.$emit('select-session', 'b')
    await flushPromises()
    expect(session.selectSession).toHaveBeenCalledWith('b')
    resolveOld(); await flushPromises()
    expect(session.selectSession.mock.calls).toEqual([['b']])
  })
  it('历史导航挂起后新建对话，迟到导航不能覆盖新建意图', async () => {
    const wrapper = await page()
    let resolveOld!: () => void
    submit.cancelCurrentQuery.mockReturnValueOnce(new Promise<void>(resolve => { resolveOld = resolve })).mockResolvedValueOnce(undefined)
    const sidebar = wrapper.findComponent({ name: 'QuerySidebar' })
    sidebar.vm.$emit('select-session', 'a'); sidebar.vm.$emit('new-session')
    await flushPromises(); expect(session.startNewSession).toHaveBeenCalledTimes(1)
    resolveOld(); await flushPromises()
    expect(session.selectSession).not.toHaveBeenCalled()
  })
  it('完成回答仅交给 fresh 卡片，不先渲染存储摘要或追问', async () => {
    session.activeSession.value.messages = [{ id: 'old', role: 'assistant', status: 'COMPLETED', taskId: 'old', content: '不可先显示的旧摘要', createdAt: '', queryResult: { suggestedQuestions: ['旧敏感追问'] } }]
    const wrapper = await page()
    expect(wrapper.text()).toContain('fresh-answer-slot')
    expect(wrapper.text()).not.toContain('不可先显示的旧摘要')
    expect(wrapper.text()).not.toContain('旧敏感追问')
  })
})
