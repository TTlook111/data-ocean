import { ref } from 'vue'

/** Memory only: survives workspace navigation, never serializes session credentials. */
export const pendingReleaseWrites = ref<{ identity: string; source: number; snapshot: number; action: string; started: number; baselineAuditId?: number }[]>([])
export async function trackReleaseWrite<T>(record: typeof pendingReleaseWrites.value[number], write: () => Promise<T>): Promise<T> {
  pendingReleaseWrites.value.push(record)
  const remove = () => { pendingReleaseWrites.value = pendingReleaseWrites.value.filter(item => !(item.identity === record.identity && item.source === record.source && item.snapshot === record.snapshot && item.started === record.started && item.action === record.action)) }
  try { const result = await write(); remove(); return result }
  catch (cause) {
    const status = (cause as { response?: { status?: number } })?.response?.status
    if (status && status < 500 && status !== 408) remove()
    throw cause
  }
}
