import { ref } from 'vue'

export interface ReleaseObservation {
  userId: number
  source: number
  snapshot: number
  action: 'CHECKING' | 'APPROVED' | 'publish' | 'revoke'
  started: number
}

// 当前标签页只保留待核对目标。状态和指针来自服务器，撤回原因和凭据只留在当前表单。
export const pendingReleaseWrites = ref<ReleaseObservation[]>([])
const prefix = (userId: number) => `dataocean_release_observation:${userId}:`
const key = (item: ReleaseObservation) => `${prefix(item.userId)}${item.source}:${item.snapshot}:${item.started}`

export function restoreReleaseWrites(userId: number, allowed: (item: ReleaseObservation) => boolean) {
  const visible: ReleaseObservation[] = []
  const keys = Array.from({length: sessionStorage.length}, (_, index) => sessionStorage.key(index))
  for (const storedKey of keys) {
    if (!storedKey?.startsWith(prefix(userId))) continue
    const item = JSON.parse(sessionStorage.getItem(storedKey) || 'null') as ReleaseObservation | null
    if (!item || item.userId !== userId || !Number.isSafeInteger(item.snapshot) || item.snapshot <= 0 || !Number.isSafeInteger(item.source) || item.source <= 0 || !['CHECKING','APPROVED','publish','revoke'].includes(item.action) || storedKey !== key(item)) throw new Error('版本操作的本地核对标识无效')
    if (allowed(item)) visible.push(item)
    else sessionStorage.removeItem(storedKey)
  }
  pendingReleaseWrites.value = visible
}

export function finishReleaseObservation(item: ReleaseObservation) {
  sessionStorage.removeItem(key(item))
  pendingReleaseWrites.value = pendingReleaseWrites.value.filter(record => key(record) !== key(item))
}

export async function trackReleaseWrite<T>(item: ReleaseObservation, write: () => Promise<T>): Promise<T> {
  // 网络发出前写入定位标识，刷新后只能核对该快照，不能从审计中的相似事件确认原请求。
  sessionStorage.setItem(key(item), JSON.stringify(item))
  pendingReleaseWrites.value.push(item)
  try {
    const result = await write()
    finishReleaseObservation(item)
    return result
  } catch (cause) {
    const status = (cause as { response?: { status?: number } })?.response?.status
    if (status && status < 500 && ![408,429].includes(status)) finishReleaseObservation(item)
    throw cause
  }
}
