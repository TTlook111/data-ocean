import { ref } from 'vue'
import { getSnapshotDetail, type SnapshotDetail } from '../api/admin/metadata'
import { getHttpErrorMessage } from '../utils/httpError'

export interface SnapshotDetailContext {
  snapshotId: number
  datasourceId?: number
}

/**
 * 共享快照详情读取状态。新对象开始读取或调用 clear 后，旧请求的成功、失败与 finally
 * 都不会覆盖当前对象，数据源上下文也会在响应落地前核对。
 */
export function useSnapshotDetail() {
  const detail = ref<SnapshotDetail | null>(null)
  const loading = ref(false)
  const error = ref('')
  let requestVersion = 0

  async function load(context: SnapshotDetailContext) {
    const currentRequest = ++requestVersion
    const snapshotId = context.snapshotId
    const expectedDatasourceId = context.datasourceId
    detail.value = null
    error.value = ''
    loading.value = true

    if (!Number.isInteger(snapshotId) || snapshotId <= 0) {
      error.value = '快照 ID 无效'
      loading.value = false
      return
    }

    try {
      const result = await getSnapshotDetail(snapshotId)
      if (currentRequest !== requestVersion) return

      const loadedDetail = result.data
      if (!loadedDetail?.snapshot || loadedDetail.snapshot.id !== snapshotId) {
        error.value = '无法读取指定快照'
        return
      }
      if (expectedDatasourceId !== undefined && loadedDetail.snapshot.datasourceId !== expectedDatasourceId) {
        error.value = '该快照不属于当前数据源'
        return
      }
      detail.value = loadedDetail
    } catch (cause) {
      if (currentRequest !== requestVersion) return
      error.value = getHttpErrorMessage(cause, '快照详情加载失败')
    } finally {
      if (currentRequest === requestVersion) loading.value = false
    }
  }

  function clear() {
    requestVersion += 1
    detail.value = null
    error.value = ''
    loading.value = false
  }

  return { detail, loading, error, load, clear }
}
