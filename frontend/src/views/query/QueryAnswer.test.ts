// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import { iamS1GetTask, iamS1ViewSql, iamS1SubmitFeedback, iamS1OwnFeedback } from '../../api/iamS1'
import QueryAnswer from './QueryAnswer.vue'

const auth = reactive({ token: 'reader-a' })
const capabilities = reactive({ viewSql: true, exportResult: true, snapshot: {}, datasourceCapabilities: [] })
vi.mock('../../stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../../stores/iamS1', () => ({ useIamS1Store: () => capabilities }))
vi.mock('../../api/iamS1', () => ({ iamS1GetTask: vi.fn(), iamS1ViewSql: vi.fn(), iamS1ExportCsv: vi.fn(), iamS1SubmitFeedback: vi.fn(), iamS1OwnFeedback: vi.fn() }))
const result = (taskId: string) => ({ taskId, status: 'COMPLETED', sqlExplanation: `fresh-${taskId}`, data: [{ amount: taskId }], canViewSql: true, canExport: true })
const wrappers: ReturnType<typeof mount>[] = []
function card(taskId = 'a') {
  const wrapper = mount(QueryAnswer, { props: { taskId, datasourceId: 1, originalQuestion: '问题' }, global: { stubs: {
    'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue" class="dialog"><slot /></div>' },
    QueryResult: { name: 'QueryResult', props: ['latestResult', 'resultTab', 'canViewSql', 'canExport', 'sqlLoading', 'sqlErrorMessage'], emits: ['expand', 'feedback', 'close'],
      template: '<div class="result-stub"><span>{{ latestResult?.taskId }} {{ latestResult?.sql }}</span><button v-if="canViewSql" class="sql" @click="$emit(\'expand\',\'sql\')">SQL</button><button class="feedback" @click="$emit(\'feedback\',\'LIKE\')">评价</button><button class="close" @click="$emit(\'close\')">关闭</button></div>' },
  } } })
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('IntersectionObserver', undefined)
  auth.token = 'reader-a'
  capabilities.viewSql = true
  capabilities.exportResult = true
  capabilities.snapshot = {}
  vi.mocked(iamS1GetTask).mockImplementation(async taskId => ({ data: result(taskId) }) as never)
  vi.mocked(iamS1OwnFeedback).mockResolvedValue({ data: { status: 'NOT_SUBMITTED' } } as never)
  vi.mocked(iamS1SubmitFeedback).mockResolvedValue({ data: { status: 'PENDING_ATTRIBUTION' } } as never)
})
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; vi.unstubAllGlobals() })
describe('每条回答的当前保护与任务归属', () => {
  it('普通独立数据授权 reader 无后台负责源时，仍按顶层功能和任务结论提供 SQL/导出', async () => {
    const wrapper = card()
    await flushPromises()
    expect(capabilities.datasourceCapabilities).toEqual([])
    expect(wrapper.findComponent({ name: 'QueryResult' }).props()).toMatchObject({ canViewSql: true, canExport: true })
    expect(wrapper.text()).toContain('fresh-a')
  })
  it('两条卡片的 SQL 和评价分别指向各自任务', async () => {
    vi.mocked(iamS1ViewSql).mockImplementation(async taskId => ({ data: { sql: `SQL-${taskId}` } }) as never)
    const a = card('a'), b = card('b')
    await flushPromises()
    await a.get('.sql').trigger('click'); await b.get('.sql').trigger('click')
    await flushPromises()
    expect(a.get('.dialog').text()).toContain('SQL-a')
    expect(a.get('.dialog').text()).not.toContain('SQL-b')
    expect(b.get('.dialog').text()).toContain('SQL-b')
    await a.get('.feedback').trigger('click'); await b.get('.feedback').trigger('click')
    await flushPromises()
    expect(iamS1SubmitFeedback).toHaveBeenCalledWith('a', 'LIKE')
    expect(iamS1SubmitFeedback).toHaveBeenCalledWith('b', 'LIKE')
  })
  it('当前读取被拒绝时不显示旧摘要、数据或 SQL 操作', async () => {
    vi.mocked(iamS1GetTask).mockRejectedValue({ response: { status: 403, data: { message: '知识已失效' } } })
    const wrapper = card()
    await flushPromises()
    expect(wrapper.text()).toContain('知识已失效')
    expect(wrapper.find('.answer-summary').exists()).toBe(false)
    expect(wrapper.find('.result-stub').exists()).toBe(false)
    expect(iamS1ViewSql).not.toHaveBeenCalled()
  })
  it('账号或能力变化使旧读取失效，迟到结果不回填新状态', async () => {
    let resolveOld!: (value: unknown) => void
    vi.mocked(iamS1GetTask).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }) as never)
    const wrapper = card()
    auth.token = 'reader-b'
    await nextTick(); await flushPromises()
    expect(wrapper.text()).toContain('fresh-a')
    resolveOld({ data: { ...result('a'), sqlExplanation: '旧账号秘密' } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧账号秘密')
  })
  it('关闭弹窗后的 SQL 回包被丢弃，重新打开重新核验', async () => {
    let resolveSql!: (value: unknown) => void
    vi.mocked(iamS1ViewSql).mockReturnValueOnce(new Promise(resolve => { resolveSql = resolve }) as never)
    const wrapper = card()
    await flushPromises(); await wrapper.get('.sql').trigger('click')
    await wrapper.get('.dialog .close').trigger('click')
    resolveSql({ data: { sql: '迟到 SQL' } }); await flushPromises()
    expect(wrapper.text()).not.toContain('迟到 SQL')
    vi.mocked(iamS1ViewSql).mockResolvedValue({ data: { sql: '当前 SQL' } } as never)
    await wrapper.get('.sql').trigger('click'); await flushPromises()
    expect(wrapper.get('.dialog').text()).toContain('当前 SQL')
    expect(iamS1ViewSql).toHaveBeenCalledTimes(2)
  })
  it('任务返回无 SQL/导出功能时，即使账号有功能也不请求 SQL', async () => {
    vi.mocked(iamS1GetTask).mockResolvedValue({ data: { ...result('a'), canViewSql: false, canExport: false } } as never)
    const wrapper = card(); await flushPromises()
    expect(wrapper.find('.sql').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'QueryResult' }).props('canExport')).toBe(false)
    expect(iamS1ViewSql).not.toHaveBeenCalled()
  })
})
