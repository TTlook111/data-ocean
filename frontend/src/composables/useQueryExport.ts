/**
 * useQueryExport — 导出功能 composable
 * 管理 CSV / PNG 导出和反馈提交
 */
import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { iamS1ExportCsv, iamS1SubmitFeedback, type IamS1QueryTaskResult } from '../api/iamS1'

export function useQueryExport(options: {
  latestResult: Ref<IamS1QueryTaskResult | null>
}) {
  const { latestResult } = options

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
    if (!result?.taskId || result.canExport === false) return
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
    if (!result?.taskId) return
    try {
      await iamS1SubmitFeedback(result.taskId, type)
      ElMessage.success(type === 'LIKE' ? '感谢您的肯定' : '已收到反馈，我们会持续改进')
    } catch {
      ElMessage.error('反馈提交失败')
    }
  }

  return {
    tablePage,
    tablePageSize,
    pagedTableData,
    exportCsv,
    exportPng,
    handleFeedback,
  }
}
