/**
 * useQueryExport — 导出功能 composable
 * 管理 CSV / PNG 导出和反馈提交
 */
import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitQueryFeedback, type QueryTaskResult } from '../api/query'

export function useQueryExport(options: {
  latestResult: Ref<QueryTaskResult | null>
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

  function exportCsv() {
    const result = latestResult.value
    if (!result?.data?.length || !result?.columns?.length) return
    const escapeCsvField = (val: string) => {
      if (val.includes(',') || val.includes('"') || val.includes('\n')) {
        return `"${val.replace(/"/g, '""')}"`
      }
      return val
    }
    const headers = result.columns.map(c => escapeCsvField(c.comment || c.name))
    const keys = result.columns.map(c => c.name)
    const rows = result.data.map(row => keys.map(k => escapeCsvField(String(row[k] ?? ''))).join(','))
    const csv = [headers.join(','), ...rows].join('\n')
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `query_result_${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('CSV 导出成功')
  }

  function exportPng() {
    // PNG 导出暂不可用（图表由 ChartContainer 组件管理）
    ElMessage.info('PNG 导出功能开发中')
  }

  async function handleFeedback(type: 'LIKE' | 'DISLIKE') {
    const result = latestResult.value
    if (!result?.taskId) return
    try {
      await submitQueryFeedback(result.taskId, type)
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
