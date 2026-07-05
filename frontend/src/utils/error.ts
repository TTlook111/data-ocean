/**
 * 错误处理工具函数
 *
 * 提供统一的错误消息提取功能。
 */

/**
 * 从异常中提取用户友好的错误消息
 *
 * @param err 异常对象
 * @param fallback 默认错误消息
 * @returns 用户友好的错误消息
 */
export function extractError(err: unknown, fallback: string = '操作失败'): string {
  if (typeof err === 'object' && err !== null && 'response' in err) {
    const msg = (err as any).response?.data?.message
    if (typeof msg === 'string') return msg
  }
  if (err instanceof Error && err.name === 'AbortError') {
    return '请求已取消'
  }
  return fallback
}
