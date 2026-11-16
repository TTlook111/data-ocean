// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, reactive, ref } from 'vue'
import QueryDatasourceView from './QueryDatasourceView.vue'

const auth = reactive({ token: 'reader', user: { username: 'reader' }, currentUser: null, logout: vi.fn().mockResolvedValue(undefined) })
const route = reactive({ query: {} })
const router = { push: vi.fn(), replace: vi.fn() }
const iam = { load: vi.fn(), systemAdmin: false, hasAnyAdminCapability: false, errorMessage: '' }
function makeSession() {
  const active = ref({ id: 'current', datasourceId: 1, title: '当前会话', messages: [] as Array<Record<string, unknown>> })
  return {
    loading: ref(false), historyLoading: ref(false), historyError: ref(''), errorMessage: ref(''),
    readinessErrors: ref({}), readinessLoading: ref(false), readinessMap: ref({ 1: { askable: true } }), selectedId: ref(1),
    activeSession: active, activeSessionId: ref('current'), activeMessages: computed(() => active.value.messages),
    selectedDatasource: ref({ id: 1, name: 'source' }), selectedBlockReason: ref(undefined), canAskSelectedDatasource: ref(true),
    datasourceSessions: ref([]), datasources: ref([{ id: 1, name: 'source' }]), keyword: ref(''),
    createSession: vi.fn(), selectSession: vi.fn(), selectDatasource: vi.fn(), startNewSession: vi.fn(),
    fetchDatasources: vi.fn(), removeSession: vi.fn(), loadOlderMessages: vi.fn(), reset: vi.fn(), retryHistory: vi.fn(),
  }
}
function makeSubmit() {
  return { question: ref(''), isQuerying: ref(false), cancelCurrentQuery: vi.fn().mockResolvedValue(true),
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
vi.mock('./QueryScope.vue', () => ({ default: { name: 'QueryScope', template: '<button>可问范围</button>' } }))
vi.mock('./QueryAnswer.vue', () => ({ default: { name: 'QueryAnswer', template: '<div>fresh-answer-slot</div>' } }))
const wrappers: ReturnType<typeof mount>[] = []
async function page() {
  const wrapper = mount(QueryDatasourceView, { global: { stubs: {
    QuerySidebar: { name: 'QuerySidebar', emits: ['select-session', 'new-session', 'select-datasource', 'remove-session', 'user-command'], template: '<nav />' },
    // 范围入口已移到页头；这里隔离浮层实现，只验证工作区接收选择后的业务保护。
    QueryDatasourceSelector: { name: 'QueryDatasourceSelector', emits: ['select', 'refresh'], template: '<button>选择数据源</button>' },
    QueryInput: { name: 'QueryInput', template: '<div />' }, QueryAnswer: { name: 'QueryAnswer', template: '<div>fresh-answer-slot</div>' },
    RouterLink: { template: '<a><slot /></a>' },
  } } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => { auth.token='reader';route.query={};session = makeSession(); submit = makeSubmit(); vi.clearAllMocks() })
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0 })
describe('对话导航意图与正文保护', () => {
  it('先选 A 慢取消、再选 B 快取消，旧 A 不得反向接管会话', async () => {
    const wrapper = await page()
    let resolveOld!: () => void
    submit.cancelCurrentQuery.mockReturnValueOnce(new Promise<boolean>(resolve => { resolveOld = () => resolve(true) })).mockResolvedValueOnce(true)
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
    submit.cancelCurrentQuery.mockReturnValueOnce(new Promise<boolean>(resolve => { resolveOld = () => resolve(true) })).mockResolvedValueOnce(true)
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

it('unconfirmed cancellation keeps the active conversation and source visible', async () => {
  const wrapper=await page()
  submit.isQuerying.value=true; submit.cancelCurrentQuery.mockResolvedValue(false)
  const sidebar=wrapper.findComponent({name:'QuerySidebar'})
  sidebar.vm.$emit('new-session'); await flushPromises()
  expect(session.startNewSession).not.toHaveBeenCalled()
  sidebar.vm.$emit('select-session','other'); await flushPromises()
  expect(session.selectSession).not.toHaveBeenCalled()
  expect(session.activeSessionId.value).toBe('current')
})

// 页头中的新入口必须复用停止确认，不能因位置变化直接切源并遗失在途对话。
it('header source selection keeps the current scope when cancellation is unconfirmed', async () => {
  session.datasources.value.push({ id: 2, name: 'other-source' })
  const wrapper = await page()
  submit.isQuerying.value = true
  submit.cancelCurrentQuery.mockResolvedValue(false)
  wrapper.findComponent({ name: 'QueryDatasourceSelector' }).vm.$emit('select', 2)
  await flushPromises()
  expect(session.selectDatasource).not.toHaveBeenCalled()
  expect(session.selectedId.value).toBe(1)
  expect(router.push).not.toHaveBeenCalled()
})

// 本地停止等待与服务端终态分开，恢复入口必须继续指向原任务且不重新提交问题。
it('locally timed-out messages keep the original task recovery action visible', async () => {
  session.activeSession.value.messages = [{ id: 'waiting', role: 'assistant', status: 'PROCESSING', taskId: 'pending-task', localWaitTimedOut: true, content: '等待结果', originalQuestion: '原始问题', createdAt: '' }]
  submit.isLocallyResumable = () => true
  const wrapper = await page()
  expect(wrapper.text()).toContain('仍在处理')
  const resume = wrapper.findAll('button').find(button => button.text() === '恢复等待')!
  await resume.trigger('click')
  expect(submit.continueWaiting).toHaveBeenCalledWith('pending-task')
  expect(submit.sendQuestion).not.toHaveBeenCalled()
  expect(submit.retryQuery).not.toHaveBeenCalled()
})

it('failed planning and live progress explain the stage without exposing implementation labels',async()=>{
 session.activeSession.value.messages=[
  {id:'f',role:'assistant',status:'FAILED',content:'模型输出不符合规划关系合同，查询未完成，请重试或联系管理员。',createdAt:''},
  {id:'p',role:'assistant',status:'PROCESSING',content:'正在查询中...',createdAt:'',queryResult:{status:'PROCESSING',progressNode:'sql_semantic_check'}}]
 const w=await page()
 expect(w.text()).not.toContain('规划关系合同')
 expect(w.text()).toContain('查询方案不符合已审核的数据关系')
 expect(w.text()).toContain('核对问题语义')
 expect(w.text()).not.toContain('sql_semantic_check')
})

type DeleteHooks={beforeDelete:()=>Promise<boolean>,accepts:()=>boolean}
async function deleteHooks() {
 const w=await page();submit.isQuerying.value=true
 w.findComponent({name:'QuerySidebar'}).vm.$emit('remove-session',session.activeSession.value)
 await flushPromises()
 const hooks=session.removeSession.mock.calls.at(-1)?.[1] as DeleteHooks|undefined
 expect(hooks).toBeDefined()
 return {w,hooks:hooks!}
}
it('deleting an active pending local session requires stop confirmation',async()=>{
 const {hooks}=await deleteHooks();submit.cancelCurrentQuery.mockResolvedValue(false)
 expect(await hooks.beforeDelete()).toBe(false);expect(submit.cancelCurrentQuery).toHaveBeenCalledOnce()
 expect(session.activeSessionId.value).toBe('current')
})
it('a confirmed stop permits the selected active-session deletion',async()=>{
 const {hooks}=await deleteHooks()
 expect(await hooks.beforeDelete()).toBe(true);expect(submit.cancelCurrentQuery).toHaveBeenCalledOnce()
})
it('confirming deletion of an inactive session leaves the current query alone',async()=>{
 const {hooks}=await deleteHooks();session.activeSessionId.value='different'
 expect(await hooks.beforeDelete()).toBe(true);expect(submit.cancelCurrentQuery).not.toHaveBeenCalled()
})
it('old-account deletion confirmation cannot cancel a new account query',async()=>{
 const {hooks}=await deleteHooks();auth.token='other';await flushPromises()
 submit.cancelCurrentQuery.mockClear()
 expect(hooks.accepts()).toBe(false);expect(await hooks.beforeDelete()).toBe(false)
 expect(submit.cancelCurrentQuery).not.toHaveBeenCalled()
})
it('logout still completes after an unconfirmed stop',async()=>{
 const w=await page();submit.isQuerying.value=true;submit.cancelCurrentQuery.mockResolvedValue(false)
 w.findComponent({name:'QuerySidebar'}).vm.$emit('user-command','logout');await flushPromises()
 expect(auth.logout).toHaveBeenCalledOnce();expect(router.push).toHaveBeenCalledWith('/login')
})

it('no-source recovery gives a concrete contact checklist without revealing hidden sources',async()=>{
 session.selectedId.value=undefined as never;session.datasources.value=[]
 const w=await page();const next=w.get('.no-source-next-step')
 expect(next.find('summary').text()).toBe('向管理员提供哪些信息')
 expect(next.text()).toContain('当前账号');expect(next.text()).toContain('期望业务主题');expect(next.text()).toContain('所需数据范围')
 expect(next.text()).not.toContain('sales_orders');expect(w.text()).not.toContain('申请已提交')
})
