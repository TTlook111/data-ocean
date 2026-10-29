import { shallowRef } from 'vue'
/** Mutation ownership survives workspace navigation. Unknown results never trigger automatic retry. */
export const pendingGovernanceWrites = shallowRef<
  Array<{ identity: string; keys: string[]; label: string; state: 'pending' | 'unknown' }>
>([])
export async function trackGovernanceWrite<T>(
  identity: string,
  keys: string[],
  label: string,
  write: () => Promise<T>,
): Promise<T> {
  if (
    pendingGovernanceWrites.value.some(
      (item) => item.identity === identity && item.keys.some((key) => keys.includes(key)),
    )
  )
    throw new Error('此对象仍有执行中或结果未知的操作，请先核对记录；不能重复提交')
  const record = { identity, keys: [...keys], label, state: 'pending' as const }
  pendingGovernanceWrites.value = [...pendingGovernanceWrites.value, record]
  const remove = () => {
    pendingGovernanceWrites.value = pendingGovernanceWrites.value.filter((item) => item !== record)
  }
  try {
    const result = await write()
    remove()
    return result
  } catch (cause) {
    const status = (cause as { response?: { status?: number } })?.response?.status
    if (status && status < 500 && status !== 408) remove()
    else
      pendingGovernanceWrites.value = pendingGovernanceWrites.value.map((item) =>
        item === record ? { ...record, state: 'unknown' } : item,
      )
    throw cause
  }
}
