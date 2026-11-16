// @vitest-environment happy-dom
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
const api = vi.hoisted(() => ({
  getUnreadNotificationCount: vi.fn(),
  listNotifications: vi.fn(),
  markNotificationAsRead: vi.fn(),
  markNotificationsBatchAsRead: vi.fn(),
}))
vi.mock('../api/http', () => ({ http: {} }))
vi.mock('../api/notification', () => api)
import { useAuthStore } from '../stores/auth'
import { useAdminNotifications } from './useAdminNotifications'
const row = (id: number, isRead = false) => ({
  id,
  isRead,
  title: '合成通知' + id,
  content: '合成内容',
  type: 'SYSTEM',
  createdAt: 'fixture',
})
const deferred = () => {
  let resolve!: (v: any) => void
  return { promise: new Promise((r) => (resolve = r)), resolve: (v: any) => resolve(v) }
}
let wrappers: any[] = []
async function page() {
  const w = mount(defineComponent({ setup: useAdminNotifications, template: '<div/>' }))
  wrappers.push(w)
  await flushPromises()
  return w.vm as any
}
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().token = 'A'
  Object.values(api).forEach((f) => f.mockReset())
  api.getUnreadNotificationCount.mockResolvedValue({ code: 200, data: { count: 12 } })
  api.listNotifications.mockResolvedValue({
    code: 200,
    data: { records: Array.from({ length: 8 }, (_, i) => row(i + 1)), total: 12 },
  })
  api.markNotificationAsRead.mockResolvedValue({ code: 200, data: null })
  api.markNotificationsBatchAsRead.mockResolvedValue({ code: 200, data: null })
})
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers = []
  vi.restoreAllMocks()
})
it('数量失败或残缺是未知，不伪造0', async () => {
  api.getUnreadNotificationCount.mockResolvedValue({ data: {} })
  const v = await page()
  expect(v.unreadCount).toBeUndefined()
  expect(v.notificationCountError).toContain('未确认')
})
it('列表失败独立于空列表，并支持明确重读', async () => {
  api.listNotifications.mockRejectedValueOnce(new Error('合成读取失败'))
  const v = await page()
  await v.fetchNotifications()
  expect(v.notificationError).toBe('合成读取失败')
  await v.fetchNotifications()
  expect(v.notifications).toHaveLength(8)
  expect(v.notificationError).toBe('')
})
it('A列表晚回不能填入B，且不再跟进旧账号数量', async () => {
  const d = deferred()
  api.listNotifications
    .mockReturnValueOnce(d.promise)
    .mockResolvedValue({ data: { records: [row(9)], total: 1 } })
  const v = await page()
  const a = v.fetchNotifications()
  useAuthStore().token = 'B'
  await flushPromises()
  await v.fetchNotifications()
  const calls = api.getUnreadNotificationCount.mock.calls.length
  d.resolve({ data: { records: [row(1)], total: 1 } })
  await a
  expect(v.notifications.map((x: any) => x.id)).toEqual([9])
  expect(api.getUnreadNotificationCount).toHaveBeenCalledTimes(calls)
})
it('只批读当前8条并重读全局余数4，不归零', async () => {
  const v = await page()
  await v.fetchNotifications()
  api.listNotifications.mockResolvedValue({
    data: { records: Array.from({ length: 8 }, (_, i) => row(i + 1, true)), total: 12 },
  })
  api.getUnreadNotificationCount.mockResolvedValue({ data: { count: 4 } })
  await v.readAllVisible()
  expect(api.markNotificationsBatchAsRead).toHaveBeenCalledExactlyOnceWith([1, 2, 3, 4, 5, 6, 7, 8])
  expect(v.unreadCount).toBe(4)
})
it('单读重复点击只发一次，回执未知不乐观改已读', async () => {
  const d = deferred()
  api.markNotificationAsRead.mockReturnValue(d.promise)
  const v = await page()
  await v.fetchNotifications()
  const first = v.readNotification(v.notifications[0])
  await v.readNotification(v.notifications[0])
  expect(api.markNotificationAsRead).toHaveBeenCalledExactlyOnceWith(1)
  d.resolve({})
  await first
  expect(v.notifications[0].isRead).toBe(false)
  expect(v.notificationError).toContain('未确认')
})
it('旧账号单读成功不能重读或更改后来账号的列表', async () => {
  const d = deferred()
  api.markNotificationAsRead.mockReturnValue(d.promise)
  const v = await page()
  await v.fetchNotifications()
  const a = v.readNotification(v.notifications[0])
  useAuthStore().token = 'B'
  await flushPromises()
  api.listNotifications.mockResolvedValue({ data: { records: [row(9)], total: 1 } })
  await v.fetchNotifications()
  const calls = api.listNotifications.mock.calls.length
  d.resolve({ code: 200, data: null })
  await a
  expect(v.notifications[0].id).toBe(9)
  expect(api.listNotifications).toHaveBeenCalledTimes(calls)
})
it('先发的数量回包不覆盖后发读取，卸载清理轮询', async () => {
  const clear = vi.spyOn(globalThis, 'clearInterval')
  const d = deferred()
  api.getUnreadNotificationCount.mockReturnValueOnce(d.promise).mockResolvedValue({ data: { count: 3 } })
  const v = await page()
  await v.fetchUnreadCount()
  d.resolve({ data: { count: 12 } })
  await flushPromises()
  expect(v.unreadCount).toBe(3)
  wrappers[0].unmount()
  wrappers = []
  expect(clear).toHaveBeenCalledOnce()
})
it('残缺或重复通知ID不能当作合法通知列表', async () => {
  api.listNotifications.mockResolvedValue({ data: { records: [row(1), row(1)], total: 2 } })
  const v = await page()
  await v.fetchNotifications()
  expect(v.notificationError).toContain('不完整')
  expect(v.notifications).toHaveLength(0)
})

it('写期间重开浮层不插入写前GET，ACK后强制重读新列表', async () => {
  const write = deferred(),
    oldRead = deferred()
  api.markNotificationAsRead.mockReturnValue(write.promise)
  const v = await page()
  await v.fetchNotifications()
  let accepted = false
  api.listNotifications.mockImplementation(() =>
    accepted ? Promise.resolve({ data: { records: [row(1, true)], total: 12 } }) : oldRead.promise,
  )
  const pending = v.readNotification(v.notifications[0])
  const reopened = v.fetchNotifications()
  expect(api.listNotifications).toHaveBeenCalledTimes(1)
  accepted = true
  write.resolve({ code: 200, data: null })
  await pending
  oldRead.resolve({ data: { records: [row(1, false)], total: 12 } })
  await reopened
  await flushPromises()
  expect(api.listNotifications).toHaveBeenCalledTimes(2)
  expect(v.notifications[0].isRead).toBe(true)
})
