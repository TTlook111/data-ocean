import type { IamS1QueryTaskResult } from '../api/iamS1'

export type QueryDisplayState = {
  kind:
    | 'empty'
    | 'selection-loading'
    | 'selection-error'
    | 'processing'
    | 'clarification'
    | 'failed'
    | 'cancelled'
    | 'timeout'
    | 'success-empty'
    | 'success-data'
    | 'unknown'
  title: string
  description: string
  canResume?: boolean
}

export function finalProtectionLabel(status?: string): string | undefined {
  if (status === 'FINAL_PROTECTED') return '已按当前权限校验'
  if (status === 'FINAL_MASKED') return '已按当前权限校验并脱敏'
  return undefined
}

export function resolveQueryDisplayState(options: {
  result: IamS1QueryTaskResult | null
  messageStatus?: string
  isSubmitting?: boolean
  localWaitTimedOut?: boolean
  selectionLoading?: boolean
  selectionError?: string
}): QueryDisplayState {
  const {
    result,
    messageStatus,
    isSubmitting = false,
    localWaitTimedOut = false,
    selectionLoading = false,
    selectionError,
  } = options

  if (selectionLoading) {
    return { kind: 'selection-loading', title: '正在读取这条查询的结果', description: '结果加载完成后会显示在这里。' }
  }
  if (selectionError) {
    return { kind: 'selection-error', title: '无法读取所选查询', description: selectionError }
  }
  if (isSubmitting) {
    return { kind: 'processing', title: '正在提交查询', description: '任务开始后会在此显示真实进度。' }
  }

  const status = result?.status || messageStatus
  switch (status) {
    case 'PROCESSING':
    case 'loading':
      return {
        kind: 'processing',
        title: localWaitTimedOut ? '任务仍在处理中' : result?.progressMessage || '查询正在执行中',
        description: localWaitTimedOut
          ? '本地等待已达到上限；服务端任务仍在处理，可以恢复等待。'
          : '可以在输入区停止查询。',
        canResume: localWaitTimedOut,
      }
    case 'CLARIFICATION_REQUIRED':
      return {
        kind: 'clarification',
        title: '需要补充查询条件',
        description: result?.sqlExplanation || result?.errorMessage || '请补充指标、时间范围或筛选口径。',
      }
    case 'FAILED':
    case 'error':
      return {
        kind: 'failed',
        title: '查询未能完成',
        description: result?.errorMessage || '请求未能完成，可以保留原问题后重试。',
      }
    case 'CANCELLED':
      return { kind: 'cancelled', title: '本次查询已停止', description: '原问题已保留，可以再次提问。' }
    case 'TIMEOUT':
      return {
        kind: 'timeout',
        title: '查询已超时并终止',
        description: result?.errorMessage || '服务端任务已经结束，请重新发起查询。',
      }
    case 'COMPLETED':
      if (result?.data?.length) {
        return { kind: 'success-data', title: '查询完成', description: `返回 ${result.rowCount ?? result.data.length} 行数据。` }
      }
      return { kind: 'success-empty', title: '查询成功，没有匹配记录', description: '可以调整时间范围、筛选条件或分组方式后再次提问。' }
    case undefined:
    case '':
      return { kind: 'empty', title: '还没有查询结果', description: '提出一个问题后，结果会显示在这里。' }
    default:
      return { kind: 'unknown', title: '查询状态暂不可用', description: '请刷新会话或重新读取这条查询。' }
  }
}
