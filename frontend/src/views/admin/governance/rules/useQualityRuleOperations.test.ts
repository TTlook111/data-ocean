// @vitest-environment happy-dom
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { computed, defineComponent, reactive } from 'vue'
import { shallowMount } from '@vue/test-utils'
import type { useQualityRuleScope } from './useQualityRuleScope'
import { useQualityRuleOperations } from './useQualityRuleOperations'

const api = vi.hoisted(() => ({
  submitQualityRuleOperation: vi.fn(),
  getQualityRuleOperation: vi.fn(),
  resolveQualityRuleOperation: vi.fn(),
}))
vi.mock('../../../../api/admin/qualityRuleOperations', () => api)
const target = { scopeType: 'DATASOURCE' as const, datasourceId: 5 }
const configuration = {
  ...target,
  ruleName: '仅内存中的敏感草稿',
  templateCode: 'COMP_COLUMN_COMMENT_MISSING',
  description: '不应持久化的建议',
  severity: 'MEDIUM',
  deductionPoints: 3,
  parameters: {},
}
const request = { action: 'CREATE' as const, configuration }
let state: {
  auth: { token: string; currentUser: { id: number }; user: null }
  allowed: number[]
  ready: boolean
  snapshot: object
}
const wrappers: Array<{ unmount: () => void }> = []
function mountOperations(onRevoked = vi.fn(), coordinator = useQualityRuleOperations) {
  let operations!: ReturnType<typeof useQualityRuleOperations>
  const load = vi.fn()
  const scope = {
    auth: state.auth,
    iamS1: {
      get snapshot() {
        return state.snapshot
      },
      load,
    },
    permissionReady: computed(() => state.ready),
    canManageRule: (value: typeof target) => state.ready && state.allowed.includes(value.datasourceId),
  } as unknown as ReturnType<typeof useQualityRuleScope>
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        operations = coordinator(scope, onRevoked)
        return () => null
      },
    }),
  )
  wrappers.push(wrapper)
  return { operations, wrapper, onRevoked, load }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}
beforeEach(() => {
  localStorage.clear()
  state = reactive({
    auth: { token: 'private-token-A', currentUser: { id: 7 }, user: null },
    allowed: [5, 6],
    ready: true,
    snapshot: {},
  })
  Object.values(api).forEach((mock) => mock.mockReset())
  api.submitQualityRuleOperation.mockRejectedValue(new Error('response lost'))
  api.getQualityRuleOperation.mockImplementation(async (operationId) => ({
    data: { operationId, outcome: 'NOT_FOUND' },
  }))
  api.resolveQualityRuleOperation.mockImplementation(async (operationId) => ({
    data: { operationId, outcome: 'REJECTED', errorCode: 409, errorMessage: '该操作已终止，未提交规则变更' },
  }))
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

// 断网和刷新是两次独立工作面生命周期，第二次只能使用第一次落盘的操作标识。
it('发请求前落最小标识，刷新恢复原ID且不保存草稿或令牌', async () => {
  const first = mountOperations()
  await expect(first.operations.submit(request, target, target, 'create:5')).rejects.toThrow('response lost')
  const operationId = api.submitQualityRuleOperation.mock.calls[0]![0].operationId
  const stored = localStorage.getItem(`dataocean_quality_rule_operations_v1:7:${operationId}`)!
  expect(stored).toContain(operationId)
  for (const secret of [
    'private-token-A',
    configuration.ruleName,
    configuration.description,
    configuration.templateCode,
    'configuration',
  ])
    expect(stored).not.toContain(secret)
  first.wrapper.unmount()
  const second = mountOperations()
  expect(second.operations.records.value[0]?.operationId).toBe(operationId)
  await expect(second.operations.submit(request, target)).rejects.toThrow('结果未知')
  expect(api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
})

it('NOT_FOUND不解锁，持久终止围栏返回后才允许明确新操作', async () => {
  const { operations } = mountOperations()
  await operations.submit(request, target).catch(() => undefined)
  const record = operations.records.value[0]!
  await operations.reconcile(record)
  expect(operations.records.value).toHaveLength(1)
  expect(api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
  await operations.reconcile(record, true)
  expect(api.resolveQualityRuleOperation).toHaveBeenCalledWith(record.operationId)
  expect(operations.records.value).toHaveLength(0)
  await operations.submit(request, target).catch(() => undefined)
  expect(api.submitQualityRuleOperation.mock.calls[1]![0].operationId).not.toBe(record.operationId)
})

it('已提交CREATE回执仍锁住新建，直到对应草稿消费完成才释放', async () => {
  api.submitQualityRuleOperation.mockImplementation(async (input) => ({
    data: {
      operationId: input.operationId,
      action: input.action,
      outcome: 'APPLIED',
      ruleId: 81,
      configVersion: 1,
    },
  }))
  const { operations } = mountOperations()
  const receipt = await operations.submit(request, target)
  expect(operations.records.value[0]?.applied).toEqual({ ruleId: 81, configVersion: 1 })
  await expect(operations.submit(request, { scopeType: 'GLOBAL' })).rejects.toThrow('结果未知')
  operations.acknowledge(receipt.operationId)
  expect(operations.records.value).toHaveLength(0)
})

it('刷新后查到APPLIED保留已提交ID和版本，不误当未提交的新建', async () => {
  const first = mountOperations()
  await first.operations.submit(request, target).catch(() => undefined)
  first.wrapper.unmount()
  api.getQualityRuleOperation.mockImplementation(async (operationId) => ({
    data: { operationId, action: 'CREATE', outcome: 'APPLIED', ruleId: 81, configVersion: 1 },
  }))
  const { operations } = mountOperations()
  await operations.reconcile(operations.records.value[0]!)
  expect(operations.records.value[0]?.applied?.ruleId).toBe(81)
  expect(operations.conflicts(81, target)).toBe(true)
  expect(api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
})

it('确定版本冲突释放操作记录但不更改客户端原版本或自动重试', async () => {
  api.submitQualityRuleOperation.mockImplementation(async (input) => ({
    data: { operationId: input.operationId, action: input.action, outcome: 'CONFLICT', errorCode: 409 },
  }))
  const { operations } = mountOperations()
  const receipt = await operations.submit(
    { action: 'UPDATE', ruleId: 81, expectedVersion: 3, configuration },
    target,
  )
  expect(receipt.outcome).toBe('CONFLICT')
  expect(operations.records.value).toHaveLength(0)
  expect(api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
  expect(api.submitQualityRuleOperation.mock.calls[0]![0].expectedVersion).toBe(3)
})

it.each([403, 404])('确定权限/范围失效%s只清关联标识并通知定向草稿清理', async (status) => {
  api.submitQualityRuleOperation.mockRejectedValue({ response: { status } })
  const { operations, onRevoked, load } = mountOperations()
  await operations
    .submit({ action: 'UPDATE', ruleId: 81, expectedVersion: 1, configuration }, target)
    .catch(() => undefined)
  expect(onRevoked).toHaveBeenCalledWith(expect.objectContaining({ ruleId: 81, original: target }))
  expect(load).toHaveBeenCalledWith(true)
  expect(operations.records.value).toHaveLength(0)
})

it('HTTP200明确403回执同样清理无权内容，不能只当普通保存失败', async () => {
  api.submitQualityRuleOperation.mockImplementation(async (input) => ({
    data: { operationId: input.operationId, outcome: 'REJECTED', errorCode: 403 },
  }))
  const { operations, onRevoked } = mountOperations()
  await operations.submit(request, target)
  expect(onRevoked).toHaveBeenCalledOnce()
  expect(operations.records.value).toHaveLength(0)
})

it('旧身份晚回执不清新身份记录或显示旧结果', async () => {
  const response = deferred<any>()
  api.submitQualityRuleOperation.mockReturnValue(response.promise)
  const { operations, onRevoked } = mountOperations()
  const old = operations.submit(request, target).catch(() => undefined)
  state.auth.token = 'private-token-B'
  state.auth.currentUser.id = 8
  response.reject({ response: { status: 403 } })
  await old
  expect(onRevoked).not.toHaveBeenCalled()
  expect(operations.records.value).toHaveLength(0)
  expect(
    Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)).filter((key) =>
      key?.startsWith('dataocean_quality_rule_operations_v1:7:'),
    ),
  ).toHaveLength(1)
})

it('权限摘要暂时不可读隐藏恢复记录，确认撤权才移除目标范围', async () => {
  const { operations } = mountOperations()
  await operations.submit(request, target).catch(() => undefined)
  state.ready = false
  expect(operations.records.value).toHaveLength(0)
  expect(
    Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)).filter((key) =>
      key?.startsWith('dataocean_quality_rule_operations_v1:7:'),
    ),
  ).toHaveLength(1)
  state.allowed = [6]
  state.ready = true
  expect(
    Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)).filter((key) =>
      key?.startsWith('dataocean_quality_rule_operations_v1:7:'),
    ),
  ).toHaveLength(0)
})

it('本地恢复记录损坏时拒绝新写入，不能覆盖原未知操作', async () => {
  localStorage.setItem('dataocean_quality_rule_operations_v1:7', '{invalid')
  const { operations } = mountOperations()
  await expect(operations.submit(request, target)).rejects.toThrow('恢复记录尚未就绪')
  expect(api.submitQualityRuleOperation).not.toHaveBeenCalled()
  expect(localStorage.getItem('dataocean_quality_rule_operations_v1:7')).toBe('{invalid')
})

it('存储不可写时不发网络请求', async () => {
  const { operations } = mountOperations()
  vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
    throw new Error('quota')
  })
  await expect(operations.submit(request, target)).rejects.toThrow('quota')
  expect(api.submitQualityRuleOperation).not.toHaveBeenCalled()
})

it('两个工作面的不同规则操作互不覆盖，消费一个回执保留另一个', async () => {
  const first = mountOperations(),
    second = mountOperations()
  await first.operations
    .submit({ action: 'UPDATE', ruleId: 81, expectedVersion: 1, configuration }, target)
    .catch(() => undefined)
  await second.operations
    .submit({ action: 'UPDATE', ruleId: 82, expectedVersion: 1, configuration }, target)
    .catch(() => undefined)
  const third = mountOperations()
  expect(third.operations.records.value.map((record) => record.ruleId).sort()).toEqual([81, 82])
  api.getQualityRuleOperation.mockImplementation(async (operationId) => ({
    data: { operationId, action: 'UPDATE', outcome: 'APPLIED', ruleId: 81, configVersion: 2 },
  }))
  await first.operations.reconcile(first.operations.records.value[0]!)
  first.operations.acknowledge(first.operations.records.value[0]!.operationId)
  const refreshed = mountOperations()
  expect(refreshed.operations.records.value.map((record) => record.ruleId)).toEqual([82])
})

// 两个真实标签页拥有独立模块内存；共享存储消费不能替原发起工作面处理其人工草稿。
it('A响应丢失后B核对并消费成功，A仍必须处理原回执而不能再次CREATE', async () => {
  const first = mountOperations()
  await first.operations.submit(request, target, target, 'create:5').catch(() => undefined)
  const record = first.operations.records.value[0]!
  vi.resetModules()
  const peerModule = await import('./useQualityRuleOperations')
  api.getQualityRuleOperation.mockImplementation(async (operationId) => ({
    data: { operationId, action: 'CREATE', outcome: 'APPLIED', ruleId: 81, configVersion: 1 },
  }))
  const peer = mountOperations(vi.fn(), peerModule.useQualityRuleOperations)
  await peer.operations.reconcile(peer.operations.records.value[0]!)
  peer.operations.acknowledge(record.operationId)
  window.dispatchEvent(
    new StorageEvent('storage', { key: `dataocean_quality_rule_operations_v1:7:${record.operationId}` }),
  )
  expect(first.operations.records.value).toHaveLength(1)
  expect(first.operations.records.value[0]?.applied?.ruleId).toBe(81)
  await expect(first.operations.submit(request, target)).rejects.toThrow('结果未知')
  expect(api.submitQualityRuleOperation).toHaveBeenCalledTimes(1)
})

it('已确认撤权后原APPLIED晚回不能重新显示或持久化无权记录', async () => {
  const response = deferred<any>()
  api.submitQualityRuleOperation.mockReturnValue(response.promise)
  const { operations } = mountOperations()
  const saving = operations.submit(request, target)
  const operationId = api.submitQualityRuleOperation.mock.calls[0]![0].operationId
  state.allowed = [6]
  state.snapshot = { revision: 2 }
  response.resolve({
    data: { operationId, action: 'CREATE', outcome: 'APPLIED', ruleId: 81, configVersion: 1 },
  })
  await saving
  expect(operations.records.value).toHaveLength(0)
  expect(localStorage.getItem(`dataocean_quality_rule_operations_v1:7:${operationId}`)).toBeNull()
  expect(operations.notice.value).toBe('')
})
