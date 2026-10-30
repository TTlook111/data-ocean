import { shallowRef } from 'vue'
/** Keep an uncertain mutation blocked across workspace navigation, without persisting credentials. */
export const pendingLineageWrites = shallowRef<{ identity: string; key: string }[]>([])
export async function trackLineageWrite<T>(
  identity: string,
  key: string,
  write: () => Promise<T>,
): Promise<T> {
  if (pendingLineageWrites.value.some((x) => x.identity === identity && x.key === key))
    throw new Error('此操作仍在执行或结果未知，请先核对关系记录，不能重复提交')
  const record = { identity, key }
  pendingLineageWrites.value = [...pendingLineageWrites.value, record]
  try {
    const result = await write()
    pendingLineageWrites.value = pendingLineageWrites.value.filter((x) => x !== record)
    return result
  } catch (cause) {
    const status = (cause as { response?: { status?: number } })?.response?.status
    if (status && status < 500 && status !== 408)
      pendingLineageWrites.value = pendingLineageWrites.value.filter((x) => x !== record)
    throw cause
  }
}
