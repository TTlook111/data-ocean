import { ref, onBeforeUnmount } from 'vue'

/**
 * 查询任务 SSE 连接 Composable
 *
 * 提供实时进度推送，替代轮询机制。
 *
 * @param baseUrl API 基础 URL
 * @returns SSE 连接状态和方法
 */
export function useQuerySse(baseUrl: string = '') {
  const connected = ref(false)
  const progress = ref<Record<string, unknown> | null>(null)
  const result = ref<Record<string, unknown> | null>(null)
  const error = ref<string | null>(null)

  let eventSource: EventSource | null = null

  /**
   * 建立 SSE 连接
   *
   * @param taskId 任务 ID
   * @param onProgress 进度回调
   * @param onResult 结果回调
   * @param onError 错误回调
   */
  function connect(
    taskId: string,
    onProgress?: (data: Record<string, unknown>) => void,
    onResult?: (data: Record<string, unknown>) => void,
    onError?: (error: string) => void,
  ) {
    // 关闭已有连接
    disconnect()

    const url = `${baseUrl}/api/query/tasks/${taskId}/stream`
    eventSource = new EventSource(url)

    eventSource.onopen = () => {
      connected.value = true
      error.value = null
    }

    eventSource.addEventListener('connected', (event) => {
      const data = JSON.parse(event.data)
      console.log('SSE 连接成功:', data)
    })

    eventSource.addEventListener('progress', (event) => {
      const data = JSON.parse(event.data)
      progress.value = data
      onProgress?.(data)
    })

    eventSource.addEventListener('result', (event) => {
      const data = JSON.parse(event.data)
      result.value = data
      onResult?.(data)
      disconnect()
    })

    eventSource.addEventListener('error', (event) => {
      if (event instanceof MessageEvent) {
        const data = JSON.parse(event.data)
        error.value = data.error || '连接错误'
        onError?.(error.value || '连接错误')
      } else {
        error.value = '连接断开'
        onError?.('连接断开')
      }
      disconnect()
    })

    eventSource.onerror = () => {
      connected.value = false
      error.value = '连接断开'
      onError?.(error.value || '连接断开')
    }
  }

  /**
   * 断开 SSE 连接
   */
  function disconnect() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    connected.value = false
  }

  // 组件卸载时自动断开
  onBeforeUnmount(() => {
    disconnect()
  })

  return {
    connected,
    progress,
    result,
    error,
    connect,
    disconnect,
  }
}
