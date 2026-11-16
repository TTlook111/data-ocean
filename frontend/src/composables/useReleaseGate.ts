import { computed, ref } from 'vue'
import { getReleaseGate, type ReleaseGate } from '../api/admin/versioning'
import { getHttpErrorMessage } from '../utils/httpError'

export type ReleaseGateState = 'idle' | 'loading' | 'success' | 'error'

/**
 * 单个快照的发布门禁读取状态。
 *
 * 连续打开不同快照时，旧请求的成功、失败和 finally 都不能覆盖当前快照。
 */
export function useReleaseGate() {
  const gate = ref<ReleaseGate | null>(null)
  const loading = ref(false)
  const error = ref('')
  const state = ref<ReleaseGateState>('idle')
  let requestVersion = 0

  const blockingCount = computed(() => gate.value?.blockingCount ?? 0)
  const warningCount = computed(() => gate.value?.warningCount ?? 0)
  const canPublish = computed(() => gate.value?.publishable ?? false)

  async function load(snapshotId: number, datasourceId?: number, canReadSource?: (source: number) => boolean) {
    const currentRequest = ++requestVersion
    gate.value = null
    error.value = ''
    loading.value = true
    state.value = 'loading'

    if (!Number.isInteger(snapshotId) || snapshotId <= 0) {
      error.value = '快照 ID 无效'
      loading.value = false
      state.value = 'error'
      return
    }

    try {
      const result = await getReleaseGate(snapshotId)
      if (currentRequest !== requestVersion) return
      const source = result.data?.datasourceId
      // 精确链接可能没有显式来源，返回的来源也要先通过本次授权检查再进入页面。
      if (!result.data || result.data.snapshotId !== snapshotId || (datasourceId !== undefined && source !== datasourceId) || (canReadSource && (!source || !canReadSource(source)))) {
        error.value = '无法读取指定快照的发布门禁'
        state.value = 'error'
        return
      }
      gate.value = result.data
      state.value = 'success'
    } catch (cause) {
      if (currentRequest !== requestVersion) return
      error.value = getHttpErrorMessage(cause, '发布门禁读取失败')
      state.value = 'error'
    } finally {
      if (currentRequest === requestVersion) loading.value = false
    }
  }

  function clear() {
    requestVersion += 1
    gate.value = null
    error.value = ''
    loading.value = false
    state.value = 'idle'
  }

  return { gate, loading, error, state, blockingCount, warningCount, canPublish, load, clear }
}
