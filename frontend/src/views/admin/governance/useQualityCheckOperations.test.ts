// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { computed, defineComponent, reactive } from 'vue'
import { shallowMount } from '@vue/test-utils'
import { useQualityCheckOperations } from './useQualityCheckOperations'
import type { useQualityBusinessScope } from '../../../composables/useQualityBusinessScope'
const api = vi.hoisted(() => ({
  triggerQualityCheck: vi.fn(),
  getQualityCheckOperation: vi.fn(),
  resolveQualityCheckOperation: vi.fn(),
}))
vi.mock('../../../api/admin/governance', () => api)
let state: { token: string; allowed: boolean; revision: number }
const wrappers: Array<{ unmount: () => void }> = []
function mount(coordinator = useQualityCheckOperations) {
  let value!: ReturnType<typeof useQualityCheckOperations>
  const scope = {
    auth: {
      get token() {
        return state.token
      },
      currentUser: { id: 7 },
    },
    iam: {
      loaded: true,
      errorMessage: null,
      get snapshot() {
        return state.revision
      },
      canOnDatasource: () => state.allowed,
      load: vi.fn(),
    },
    ready: computed(() => true),
    datasourceId: computed(() => 5),
    snapshotId: computed(() => 8),
  } as unknown as ReturnType<typeof useQualityBusinessScope>
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        value = coordinator(scope)
        return () => null
      },
    }),
  )
  wrappers.push(wrapper)
  return { value, wrapper }
}
const batch = {
  id: 41,
  datasourceId: 5,
  snapshotId: 8,
  status: 'PARTIAL',
  complete: false,
  operationOutcome: 'RECORDED',
}
beforeEach(() => {
  localStorage.clear()
  state = reactive({ token: 'test-only-A', allowed: true, revision: 1 })
  Object.values(api).forEach((fn) => fn.mockReset())
  api.triggerQualityCheck.mockResolvedValue({
    data: { snapshotId: 8, checkBatchId: 41, status: 'SUCCESS', complete: true },
  })
  api.getQualityCheckOperation.mockResolvedValue({ data: batch })
})
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount())
  vi.restoreAllMocks()
})

it('POST计算完成但持久批次RUNNING时不释放新检查', async () => {
  api.getQualityCheckOperation.mockResolvedValue({ data: { ...batch, status: 'RUNNING' } })
  const { value } = mount()
  const response = await value.start()
  expect(response?.result.status).toBe('RUNNING')
  value.consume(response!.item)
  expect(value.records.value).toHaveLength(1)
  await expect(value.start()).rejects.toThrow('待核对')
  expect(api.triggerQualityCheck).toHaveBeenCalledTimes(1)
})
it('响应丢失整页恢复仅查询原UUID，不重发POST', async () => {
  api.triggerQualityCheck.mockRejectedValue(new Error('网络断开'))
  const first = mount()
  await first.value.start().catch(() => undefined)
  const record = first.value.records.value[0]!
  first.wrapper.unmount()
  const second = mount()
  await second.value.reconcile(second.value.records.value[0]!)
  expect(api.getQualityCheckOperation).toHaveBeenCalledWith(8, record.operationId)
  expect(api.triggerQualityCheck).toHaveBeenCalledTimes(1)
  expect(JSON.stringify({ ...localStorage })).not.toContain('test-only-A')
})
it('未登记404不解锁，resolve持久NOT_STARTED后才可结束', async () => {
  api.triggerQualityCheck.mockRejectedValue(new Error('断开'))
  api.getQualityCheckOperation.mockRejectedValue({ response: { status: 404 } })
  api.resolveQualityCheckOperation.mockResolvedValue({
    data: { ...batch, status: 'FAILED', operationOutcome: 'NOT_STARTED' },
  })
  const { value } = mount()
  await value.start().catch(() => undefined)
  const record = value.records.value[0]!
  await value.reconcile(record)
  expect(value.records.value).toHaveLength(1)
  await value.reconcile(record, true)
  value.consume(record)
  expect(value.records.value).toHaveLength(0)
  expect(api.triggerQualityCheck).toHaveBeenCalledTimes(1)
})
it('已确认撤权后晚成功不重新落盘恢复标识', async () => {
  let finish!: (value: unknown) => void
  api.triggerQualityCheck.mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const { value } = mount()
  const response = value.start()
  state.allowed = false
  state.revision++
  finish({ data: { snapshotId: 8, checkBatchId: 41, status: 'SUCCESS', complete: true } })
  await response
  expect(value.records.value).toHaveLength(0)
  expect(localStorage.length).toBe(0)
  expect(api.getQualityCheckOperation).not.toHaveBeenCalled()
})
it('错属批次保留未知，不能借另一快照结果释放', async () => {
  api.getQualityCheckOperation.mockResolvedValue({ data: { ...batch, snapshotId: 9 } })
  const { value } = mount()
  await expect(value.start()).rejects.toThrow('范围不一致')
  expect(value.records.value).toHaveLength(1)
})
it('POST已有批次但随后GET404，保留原UUID而非解释成写入拒绝', async () => {
  api.getQualityCheckOperation.mockRejectedValue({ response: { status: 404 } })
  const { value } = mount()
  await value.start().catch(() => undefined)
  expect(value.records.value[0]?.batchId).toBe(41)
  await expect(value.start()).rejects.toThrow('待核对')
  expect(api.triggerQualityCheck).toHaveBeenCalledTimes(1)
})
it('另一标签页核对消费不能解开原发起SPA仍未确认的操作', async () => {
  api.triggerQualityCheck.mockRejectedValue(new Error('断开'))
  const first = mount()
  await first.value.start().catch(() => undefined)
  const original = first.value.records.value[0]!
  vi.resetModules()
  const peer = mount((await import('./useQualityCheckOperations')).useQualityCheckOperations)
  await peer.value.reconcile(peer.value.records.value[0]!)
  peer.value.consume(peer.value.records.value[0]!)
  window.dispatchEvent(
    new StorageEvent('storage', { key: `dataocean_quality_checks_v1:7:${original.operationId}` }),
  )
  expect(first.value.records.value).toHaveLength(1)
  await expect(first.value.start()).rejects.toThrow('待核对')
  expect(api.triggerQualityCheck).toHaveBeenCalledTimes(1)
})
