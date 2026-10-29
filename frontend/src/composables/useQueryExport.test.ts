import {beforeEach,describe,expect,it,vi} from 'vitest'
import {effectScope,ref,nextTick} from 'vue'
import {flushPromises} from '@vue/test-utils'
import {useQueryExport} from './useQueryExport'
import {ElMessageBox} from 'element-plus'
import {iamS1ExportCsv,iamS1OwnFeedback,iamS1SubmitFeedback,type IamS1QueryTaskResult} from '../api/iamS1'
vi.mock('../api/iamS1',()=>({iamS1OwnFeedback:vi.fn(),iamS1SubmitFeedback:vi.fn(),iamS1ExportCsv:vi.fn()}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn(),error:vi.fn(),info:vi.fn()},ElMessageBox:{prompt:vi.fn()}}))
beforeEach(()=>vi.resetAllMocks())
const saved={data:{status:'PENDING_ATTRIBUTION'}}
describe('问数评价与反馈跟踪',()=>{
  it('卸载时只通过本实例的取消按钮关闭补充说明框，不关闭其他弹窗', async () => {
    let rejectPrompt!: (reason: unknown) => void
    vi.mocked(iamS1OwnFeedback).mockResolvedValue({ data: { status: 'NOT_SUBMITTED' } } as never)
    vi.mocked(iamS1SubmitFeedback).mockResolvedValue(saved as never)
    vi.mocked(ElMessageBox.prompt).mockReturnValue(new Promise((_resolve, reject) => { rejectPrompt = reject }) as never)
    const scope = effectScope()
    const model = scope.run(() => useQueryExport({ latestResult: ref({ taskId: 'a', status: 'COMPLETED', data: [] }), canExport: ref(false) }))!
    const pending = model.handleFeedback('DISLIKE')
    await flushPromises()
    const ownClass = vi.mocked(ElMessageBox.prompt).mock.calls[0]![2]!.cancelButtonClass
    const cancel = vi.fn(() => rejectPrompt('cancel'))
    const querySelector = vi.fn((selector: string) => selector === `.${ownClass}` ? { click: cancel } : null)
    vi.stubGlobal('document', { querySelector })
    try {
      scope.stop(); await pending
      expect(querySelector).toHaveBeenCalledWith(`.${ownClass}`)
      expect(cancel).toHaveBeenCalledTimes(1)
      expect(iamS1SubmitFeedback).toHaveBeenCalledTimes(1)
    } finally { vi.unstubAllGlobals() }
  })
  it('导出挂起时拒绝重复点击，卸载后不触发旧文件下载', async () => {
    let resolveBlob!: (value: unknown) => void
    vi.mocked(iamS1OwnFeedback).mockResolvedValue({ data: { status: 'NOT_SUBMITTED' } } as never)
    vi.mocked(iamS1ExportCsv).mockReturnValue(new Promise(resolve => { resolveBlob = resolve }) as never)
    const scope = effectScope(), latest = ref<IamS1QueryTaskResult | null>({ taskId: 'a', status: 'COMPLETED', data: [{ amount: 1 }], canExport: true })
    const model = scope.run(() => useQueryExport({ latestResult: latest, canExport: ref(true) }))!
    const createUrl = vi.fn()
    vi.stubGlobal('URL', { createObjectURL: createUrl })
    try {
      const first = model.exportCsv(); await model.exportCsv()
      expect(iamS1ExportCsv).toHaveBeenCalledTimes(1)
      scope.stop(); resolveBlob({}); await first
      expect(createUrl).not.toHaveBeenCalled()
    } finally { vi.unstubAllGlobals() }
  })
  it('新版本复查后仍提示待验证原问题，不提前显示修复完成',async()=>{
    vi.mocked(iamS1OwnFeedback).mockResolvedValue({data:{status:'APPLIED',followupStatus:'AWAITING_VERIFICATION'}} as never)
    const scope=effectScope(),latest=ref<IamS1QueryTaskResult|null>({taskId:'a',status:'COMPLETED',data:[]})
    const model=scope.run(()=>useQueryExport({latestResult:latest,canExport:ref(false)}))!
    await flushPromises();expect(model.feedbackStatus.value).toContain('待验证');scope.stop()
  })
  it('空结果可评价，提交后不会被较早的读取请求一直锁在加载中',async()=>{
    let resolve!:(value:unknown)=>void
    vi.mocked(iamS1OwnFeedback).mockReturnValue(new Promise(r=>{resolve=r}) as never)
    vi.mocked(iamS1SubmitFeedback).mockResolvedValue(saved as never)
    const scope=effectScope(),latest=ref<IamS1QueryTaskResult|null>({taskId:'a',status:'COMPLETED',data:[]})
    const model=scope.run(()=>useQueryExport({latestResult:latest,canExport:ref(false)}))!
    expect(model.feedbackLoading.value).toBe(true)
    await model.handleFeedback('LIKE')
    expect(iamS1SubmitFeedback).toHaveBeenCalledWith('a','LIKE')
    expect(model.feedbackLoading.value).toBe(false)
    resolve({data:{status:'NOT_SUBMITTED'}});await flushPromises()
    expect(model.feedbackStatus.value).toContain('等待分析');scope.stop()
  })
  it('切换结果后旧提交的 finally 不会清除新结果的提交状态',async()=>{
    vi.mocked(iamS1OwnFeedback).mockResolvedValue({data:{status:'NOT_SUBMITTED'}} as never)
    let resolveA!:(value:unknown)=>void,resolveB!:(value:unknown)=>void
    vi.mocked(iamS1SubmitFeedback).mockReturnValueOnce(new Promise(r=>{resolveA=r}) as never).mockReturnValueOnce(new Promise(r=>{resolveB=r}) as never)
    const scope=effectScope(),latest=ref<IamS1QueryTaskResult|null>({taskId:'a',status:'COMPLETED',data:[]})
    const model=scope.run(()=>useQueryExport({latestResult:latest,canExport:ref(false)}))!
    const first=model.handleFeedback('LIKE');latest.value={taskId:'b',status:'COMPLETED',data:[]};await nextTick()
    expect(model.feedbackSubmitting.value).toBe(false)
    const second=model.handleFeedback('LIKE');resolveA(saved);await first
    expect(model.feedbackSubmitting.value).toBe(true)
    resolveB(saved);await second;expect(model.feedbackSubmitting.value).toBe(false);scope.stop()
  })
})
