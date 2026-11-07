import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  triggerQualityCheck,
  getQualityCheckOperation,
  resolveQualityCheckOperation,
  type QualityCheckBatch,
} from '../../../api/admin/governance'
import type { useQualityBusinessScope } from '../../../composables/useQualityBusinessScope'

interface CheckOperation {
  operationId: string
  datasourceId: number
  snapshotId: number
  batchId?: number
  status?: string
  consumed?: boolean
}
// 原发起页面的在途归属只留内存，其他标签页消费不能替它处理尚未观察到的结果。
const origins = new Map<string, Set<string>>()
const terminal = (status?: string) => ['SUCCESS', 'PARTIAL', 'FAILED'].includes(status || '')

/** 检查是持久执行任务，结果通过原 UUID 核对；RUNNING 和未登记都不能直接释放为新请求。 */
export function useQualityCheckOperations(scope: ReturnType<typeof useQualityBusinessScope>) {
  const { auth, iam } = scope
  const records = ref<CheckOperation[]>([]),
    busy = ref<string[]>([]),
    error = ref('')
  const userId = computed(() => auth.currentUser?.id ?? auth.user?.userId)
  const prefix = () => `dataocean_quality_checks_v1:${userId.value}:`
  const owned = () => {
    if (!origins.has(auth.token)) origins.set(auth.token, new Set())
    return origins.get(auth.token)!
  }
  const allowed = (item: CheckOperation) =>
    iam.loaded && !iam.errorMessage && iam.canOnDatasource('governance:check', item.datasourceId)
  let disposed = false,
    storageReady = false
  function save(item: CheckOperation) {
    localStorage.setItem(prefix() + item.operationId, JSON.stringify(item))
    records.value = records.value.filter((r) => r.operationId !== item.operationId)
    if (allowed(item) && (!item.consumed || owned().has(item.operationId))) records.value.push(item)
  }
  function remove(id: string) {
    localStorage.removeItem(prefix() + id)
    records.value = records.value.filter((r) => r.operationId !== id)
    owned().delete(id)
  }
  function restore() {
    storageReady = false
    records.value = []
    if (!userId.value || !iam.loaded || iam.errorMessage) return
    try {
      const keys = Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i)).filter(
        (key): key is string => !!key?.startsWith(prefix()),
      )
      for (const key of keys) {
        const item: CheckOperation = JSON.parse(localStorage.getItem(key) || 'null')
        if (
          !item ||
          !/^[0-9a-f-]{36}$/i.test(item.operationId) ||
          !Number.isSafeInteger(item.snapshotId) ||
          item.snapshotId <= 0 ||
          !Number.isSafeInteger(item.datasourceId) ||
          item.datasourceId <= 0 ||
          key !== prefix() + item.operationId
        )
          throw new Error('检查恢复标识错误')
        if (!allowed(item)) {
          localStorage.removeItem(key)
          owned().delete(item.operationId)
          continue
        }
        if (!item.consumed || owned().has(item.operationId)) records.value.push(item)
      }
      storageReady = true
    } catch {
      error.value = '检查恢复记录不可读取，暂不能发起新的检查。'
    }
  }
  function pending(source: number | undefined, snapshot: number | undefined) {
    return records.value.some((r) => r.datasourceId === source && r.snapshotId === snapshot)
  }
  const current = (token: string) => !disposed && auth.token === token
  function accept(item: CheckOperation, batch: QualityCheckBatch) {
    if (!batch?.id || batch.snapshotId !== item.snapshotId || batch.datasourceId !== item.datasourceId)
      throw new Error('检查回执范围不一致，原操作保留。')
    if (iam.loaded && !iam.errorMessage && !allowed(item)) {
      remove(item.operationId)
      return
    }
    save({ ...item, batchId: batch.id, status: batch.status })
  }
  // HTTP 拒绝与网络未知分开；后者保留原标识，即使换页或整页刷新也不自动 POST。
  async function start(selection: { dimensions?: string[]; tableNames?: string[] } = {}) {
    restore()
    const source = scope.datasourceId.value,
      snapshot = scope.snapshotId.value
    if (
      !storageReady ||
      !scope.ready.value ||
      !source ||
      !snapshot ||
      !iam.canOnDatasource('governance:check', source)
    )
      throw new Error('当前检查范围尚不可用。')
    if (pending(source, snapshot)) throw new Error('此范围已有待核对检查，请先核对原操作。')
    const item: CheckOperation = {
      operationId: crypto.randomUUID(),
      datasourceId: source,
      snapshotId: snapshot,
    }
    const token = auth.token
    let postAccepted = false
    save(item)
    owned().add(item.operationId)
    busy.value.push(item.operationId)
    error.value = ''
    try {
      const result = await triggerQualityCheck(snapshot, {
        operationId: item.operationId,
        dimensions: selection.dimensions ? [...selection.dimensions] : undefined,
        tableNames: selection.tableNames ? [...selection.tableNames] : undefined,
      })
      if (!current(token)) return
      if (result.data?.snapshotId !== snapshot || !result.data.checkBatchId)
        throw new Error('检查回执缺少精确批次，结果仍待核对。')
      postAccepted = true
      if (iam.loaded && !iam.errorMessage && !allowed(item)) {
        remove(item.operationId)
        return
      }
      // 计算响应可能先于持久收尾；只以原 UUID 的持久批次终态解除互斥或展示完整评分。
      save({ ...item, batchId: result.data.checkBatchId })
      const persisted = await getQualityCheckOperation(snapshot, item.operationId)
      if (!current(token)) return
      accept(item, persisted.data)
      return {
        item,
        result: {
          ...result.data,
          checkBatchId: persisted.data.id,
          status: persisted.data.status,
          complete: persisted.data.complete,
          requestedScope: persisted.data.requestedScope,
          qualityScore: persisted.data.totalScore,
        },
      }
    } catch (cause) {
      if (current(token)) {
        const status = (cause as { response?: { status?: number } })?.response?.status
        if (status === 403 || (!postAccepted && status && status < 500 && ![408, 429].includes(status)))
          remove(item.operationId)
        if (status === 403) void iam.load(true)
      }
      throw cause
    } finally {
      if (current(token)) busy.value = busy.value.filter((id) => id !== item.operationId)
    }
  }
  async function reconcile(item: CheckOperation, finish = false) {
    if (busy.value.includes(item.operationId) || !allowed(item)) return
    const token = auth.token
    busy.value.push(item.operationId)
    error.value = ''
    try {
      const result = await (finish
        ? resolveQualityCheckOperation(item.snapshotId, item.operationId)
        : getQualityCheckOperation(item.snapshotId, item.operationId))
      if (!current(token)) return
      accept(item, result.data)
      return result.data
    } catch (cause) {
      if (current(token)) {
        const status = (cause as { response?: { status?: number } })?.response?.status
        if (status === 403) {
          remove(item.operationId)
          void iam.load(true)
        }
        error.value =
          status === 404
            ? '原操作尚未登记；可继续核对，或结束尚未开始的请求。'
            : cause instanceof Error
              ? cause.message
              : '检查回执读取失败，原操作保留。'
      }
    } finally {
      if (current(token)) busy.value = busy.value.filter((id) => id !== item.operationId)
    }
  }
  function consume(item: CheckOperation) {
    const saved = records.value.find((r) => r.operationId === item.operationId)
    if (!saved || !terminal(saved.status)) return
    if (owned().has(item.operationId)) remove(item.operationId)
    else save({ ...saved, consumed: true })
  }
  watch(
    () => [auth.token, userId.value, iam.snapshot, iam.loaded, iam.errorMessage],
    () => {
      error.value = ''
      restore()
    },
    { immediate: true, flush: 'sync' },
  )
  const onStorage = (event: StorageEvent) => {
    if (!event.key || event.key.startsWith(prefix())) restore()
  }
  window.addEventListener('storage', onStorage)
  onBeforeUnmount(() => {
    disposed = true
    window.removeEventListener('storage', onStorage)
  })
  return { records, busy, error, start, reconcile, consume, pending, terminal }
}
