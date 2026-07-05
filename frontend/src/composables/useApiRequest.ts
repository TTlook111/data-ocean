import { ref, onBeforeUnmount } from 'vue'
import { extractError } from '../utils/error'

/**
 * API 请求封装 Composable
 *
 * 提供统一的加载状态、错误处理、竞态请求取消功能。
 *
 * @param apiFn API 请求函数（接收 AbortSignal 参数）
 * @returns 请求状态和执行方法
 */
export function useApiRequest<T>(apiFn: (signal?: AbortSignal) => Promise<T>) {
  const data = ref<T | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let abortController: AbortController | null = null

  /**
   * 执行请求
   */
  async function execute() {
    // 取消上一次未完成的请求（竞态处理）
    abortController?.abort()
    abortController = new AbortController()

    loading.value = true
    error.value = null
    try {
      data.value = await apiFn(abortController.signal)
    } catch (e: any) {
      if (e.name === 'AbortError') return // 主动取消，不报错
      error.value = extractError(e, '请求失败')
    } finally {
      loading.value = false
    }
  }

  /**
   * 取消请求
   */
  function cancel() {
    abortController?.abort()
  }

  // 组件卸载时自动取消请求
  onBeforeUnmount(() => cancel())

  return { data, loading, error, execute, cancel }
}
