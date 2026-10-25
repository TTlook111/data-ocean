/**
 * useQueryExport — 导出功能 composable
 * 管理 CSV / PNG 导出和反馈提交
 */
import { computed, ref, watch, getCurrentScope,onScopeDispose,type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { iamS1ExportCsv, iamS1SubmitFeedback,iamS1OwnFeedback,type IamS1QueryFeedback,type IamS1QueryTaskResult } from '../api/iamS1'

export function useQueryExport(options: {
  latestResult: Ref<IamS1QueryTaskResult | null>
  canExport: Ref<boolean>
}) {
  const { latestResult, canExport } = options
  const feedbackStatus=ref(''),feedbackSubmitting=ref(false),feedbackLoading=ref(false)
  let feedbackRequest=0,submitRequest=0,alive=true
  if(getCurrentScope())onScopeDispose(()=>{alive=false;feedbackRequest++})
  function summary(feedback?:IamS1QueryFeedback){
    if(!feedback||feedback.status==='NOT_SUBMITTED')return ''
    if(feedback.followupStatus==='VERIFIED')return '原问题已由人工按验收标准核验；文档发布与 RAG 生效另行处理'
    if(['RECHECKED','AWAITING_VERIFICATION'].includes(feedback.followupStatus||''))return '知识修订已复查，原问题仍待验证修复'
    if(feedback.followupStatus==='QUEUED')return '已计入对应字段版本，并进入治理复核'
    if(feedback.followupStatus==='ALREADY_CHANGED')return '已记录到对应历史版本，当前知识版本已变化'
    return ({PENDING_ATTRIBUTION:'评价已记录，等待分析原因',PENDING_REVIEW:'评价已记录，等待审核归因',APPLIED:'已确认并计入对应字段版本',REVIEWED:'已确认其他问题方向，未调整字段分数',REJECTED:'已审核，不计入字段置信度'} as Record<string,string>)[feedback.status]||'评价已记录'
  }
  async function refreshFeedback(){const result=latestResult.value,ticket=++feedbackRequest;if(!result?.taskId||result.status!=='COMPLETED'){feedbackStatus.value='';feedbackLoading.value=false;return}
    feedbackLoading.value=true
    try{const response=await iamS1OwnFeedback(result.taskId);if(alive&&ticket===feedbackRequest&&latestResult.value?.taskId===result.taskId)feedbackStatus.value=summary(response.data)}
    catch{if(alive&&ticket===feedbackRequest)feedbackStatus.value='反馈处理状态暂未读取成功，可稍后刷新'}
    finally{if(alive&&ticket===feedbackRequest)feedbackLoading.value=false}
  }
  watch([()=>latestResult.value?.taskId,()=>latestResult.value?.status],()=>{submitRequest++;feedbackSubmitting.value=false;feedbackStatus.value='';refreshFeedback()},{immediate:true})

  /** 结果表格分页（前端分页） */
  const tablePage = ref(1)
  const tablePageSize = 50
  const pagedTableData = computed(() => {
    const data = latestResult.value?.data
    if (!data || !data.length) return []
    const start = (tablePage.value - 1) * tablePageSize
    return data.slice(start, start + tablePageSize)
  })

  async function exportCsv() {
    const result = latestResult.value
    if (!result?.taskId || result.status !== 'COMPLETED' || !result.data?.length || result.canExport === false || !canExport.value) return
    try {
      const blob = await iamS1ExportCsv(result.taskId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `query_result_${Date.now()}.csv`
      a.click()
      URL.revokeObjectURL(url)
      ElMessage.success('CSV 导出成功')
    } catch {
      ElMessage.error('当前权限不允许导出，或结果已失效')
    }
  }

  function exportPng() {
    // PNG 导出暂不可用（图表由 ChartContainer 组件管理）
    ElMessage.info('PNG 导出功能开发中')
  }

  async function handleFeedback(type: 'LIKE' | 'DISLIKE') {
    const result = latestResult.value
    if (!result?.taskId || result.status !== 'COMPLETED'||feedbackSubmitting.value) return
    const ticket=++submitRequest
    feedbackSubmitting.value=true
    try {
      const saved=await iamS1SubmitFeedback(result.taskId, type)
      if(!alive||ticket!==submitRequest||latestResult.value?.taskId!==result.taskId)return
      feedbackRequest++;feedbackLoading.value=false;feedbackStatus.value=summary(saved?.data)
      ElMessage.success(type === 'LIKE' ? '感谢您的肯定' : '已收到反馈，我们会持续改进')
      if (type === 'DISLIKE') {
        try {
          const response = await ElMessageBox.prompt('可以补充哪里不符合预期，帮助定位字段含义、指标口径或呈现问题。', '补充反馈（可选）', {
            inputType: 'textarea', confirmButtonText: '补充说明', cancelButtonText: '暂不补充', inputValidator: value => !value || value.length <= 4000 || '说明最多 4000 字',
          })
          if (response.value?.trim()&&alive&&ticket===submitRequest&&latestResult.value?.taskId===result.taskId) {
            const updated=await iamS1SubmitFeedback(result.taskId, type, { reasonCode: 'UNKNOWN', comment: response.value.trim() })
            if(alive&&ticket===submitRequest&&latestResult.value?.taskId===result.taskId)feedbackStatus.value=summary(updated?.data)
          }
        } catch { /* 首次评价已保存，未补充说明不撤销原反馈。 */ }
      }
    } catch {
      if(alive&&ticket===submitRequest&&latestResult.value?.taskId===result.taskId)ElMessage.error('反馈提交失败')
    }finally{if(alive&&ticket===submitRequest)feedbackSubmitting.value=false}
  }

  return {
    tablePage,
    tablePageSize,
    pagedTableData,
    exportCsv,
    exportPng,
    handleFeedback,
    feedbackStatus,feedbackSubmitting,feedbackLoading,refreshFeedback,
  }
}
