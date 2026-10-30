// @vitest-environment happy-dom
import { shallowMount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { reactive } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({
  auth: { token: 'A' },
  iam: { loaded: true, errorMessage: null, snapshot: {}, canOnDatasource: vi.fn() },
  get: vi.fn(),
}))
vi.mock('../../../stores/auth', () => ({ useAuthStore: () => reactive(state.auth) }))
vi.mock('../../../stores/iamS1', () => ({ useIamS1Store: () => reactive(state.iam) }))
vi.mock('../../../api/admin/audit', () => ({ getAuditLogDetail: state.get }))
import Page from './AuditRecordDrawer.vue'
function deferred() {
  let resolve!: (x: any) => void
  const promise = new Promise<any>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
function page() {
  return shallowMount(Page, { props: { target: { id: 81, source: 5 } }, global: { plugins: [ElementPlus] } })
}
beforeEach(() => {
  state.auth.token = 'A'
  state.iam.loaded = true
  state.iam.canOnDatasource.mockReset().mockReturnValue(true)
  state.get.mockReset().mockResolvedValue({ data: { id: 81, datasourceId: 5, sqlText: 'SELECT protected' } })
})
it('默认SQL剥离，按真实审计id再次读取；重复点击只一次请求', async () => {
  const w = page()
  await flushPromises()
  expect((w.vm as any).record.sqlText).toBeUndefined()
  const p = deferred()
  state.get.mockReturnValueOnce(p.promise)
  const a = (w.vm as any).readSql()
  await (w.vm as any).readSql()
  expect(state.get).toHaveBeenCalledTimes(2)
  p.resolve({ data: { id: 81, datasourceId: 5, sqlText: 'SQL' } })
  await a
  expect((w.vm as any).sql).toBe('SQL')
  w.unmount()
})
it('换目标后旧详情不能覆盖新记录', async () => {
  const old = deferred()
  state.get.mockReturnValueOnce(old.promise).mockResolvedValue({ data: { id: 82, datasourceId: 6 } })
  const w = page()
  await w.setProps({ target: { id: 82, source: 6 } })
  await flushPromises()
  old.resolve({ data: { id: 81, datasourceId: 5 } })
  await flushPromises()
  expect((w.vm as any).record.id).toBe(82)
  w.unmount()
})
it('关闭后迟到SQL不回填', async () => {
  const w = page()
  await flushPromises()
  const pending = deferred()
  state.get.mockReturnValueOnce(pending.promise)
  const read = (w.vm as any).readSql()
  await w.setProps({ target: null })
  pending.resolve({ data: { id: 81, datasourceId: 5, sqlText: 'OLD' } })
  await read
  expect((w.vm as any).sql).toBeNull()
  w.unmount()
})
it('撤权或账号变化清旧记录并关闭，不用旧源补权', async () => {
  const w = page()
  await flushPromises()
  reactive(state.auth).token = 'B'
  reactive(state.iam).loaded = false
  await flushPromises()
  expect((w.vm as any).record).toBeNull()
  expect(w.emitted('close')).toBeTruthy()
  w.unmount()
})
it('详情和SQL返回错源/错记录直接拒绝', async () => {
  state.get.mockResolvedValue({ data: { id: 81, datasourceId: 6, sqlText: 'OTHER' } })
  const w = page()
  await flushPromises()
  expect((w.vm as any).record).toBeNull()
  expect((w.vm as any).error).toContain('不一致')
  w.unmount()
})
