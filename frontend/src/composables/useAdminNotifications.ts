import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import {
  getUnreadNotificationCount,
  listNotifications,
  markNotificationAsRead,
  markNotificationsBatchAsRead,
  type NotificationItem,
} from '../api/notification'
/** Notifications belong to the authenticated user; no source directory or admin function is inferred. */
export function useAdminNotifications() {
  const auth = useAuthStore(),
    notifications = ref<NotificationItem[]>([]),
    loading = ref(false),
    writing = ref(false),
    unreadCount = ref<number>(),
    error = ref(''),
    countError = ref(''),
    visible = ref(false)
  let alive = true,
    epoch = 0,
    listRequest = 0,
    countRequest = 0,
    timer: ReturnType<typeof setInterval> | undefined
  const owner = () => ({ token: auth.token, epoch })
  const current = (k: ReturnType<typeof owner>) =>
    alive && !!k.token && auth.token === k.token && epoch === k.epoch
  const message = (cause: unknown, fallback: string) =>
    (cause as { response?: { data?: { message?: string } } })?.response?.data?.message ||
    (cause instanceof Error ? cause.message : fallback)
  async function fetchUnreadCount() {
    const k = owner(),
      request = ++countRequest
    if (!current(k)) return
    try {
      const r = await getUnreadNotificationCount()
      if (!current(k) || request !== countRequest) return
      if (!Number.isSafeInteger(r.data?.count) || r.data.count < 0) throw new Error('未读数量回执未确认')
      unreadCount.value = r.data.count
      countError.value = ''
    } catch (cause) {
      if (current(k) && request === countRequest) {
        unreadCount.value = undefined
        countError.value = message(cause, '未读数量读取失败')
      }
    }
  }
  async function readList(force = false) {
    if (!force && (loading.value || writing.value)) return
    const k = owner(),
      request = ++listRequest
    if (!current(k)) return
    loading.value = true
    error.value = ''
    notifications.value = []
    try {
      const r = await listNotifications({ page: 1, pageSize: 8 })
      if (!current(k) || request !== listRequest) return
      const rows = r.data?.records
      if (
        !Array.isArray(rows) ||
        rows.some((x) => !Number.isSafeInteger(x.id) || x.id <= 0 || typeof x.isRead !== 'boolean') ||
        new Set(rows.map((x) => x.id)).size !== rows.length
      )
        throw new Error('通知回执不完整，不能按空列表解释')
      notifications.value = rows
      await fetchUnreadCount()
    } catch (cause) {
      if (current(k) && request === listRequest) error.value = message(cause, '通知读取失败')
    } finally {
      if (current(k) && request === listRequest) loading.value = false
    }
  }
  function fetchNotifications() {
    return readList()
  }
  async function mark(ids: number[]) {
    if (writing.value || loading.value || !ids.length) return
    const k = owner()
    if (!current(k) || ids.some((id) => !notifications.value.some((x) => x.id === id && !x.isRead))) return
    writing.value = true
    error.value = ''
    try {
      const r =
        ids.length === 1
          ? await markNotificationAsRead(ids[0]!)
          : await markNotificationsBatchAsRead([...ids])
      if (!current(k)) return
      if (r?.code !== 200) throw new Error('已读回执未确认，请重读通知核对')
      // Only the visible IDs were sent. Re-read the real global count instead of manufacturing zero.
      await readList(true)
    } catch (cause) {
      if (current(k)) error.value = message(cause, '已读操作未确认，请先重读核对')
    } finally {
      if (current(k)) writing.value = false
    }
  }
  function readNotification(item: NotificationItem) {
    if (!item.isRead) return mark([item.id])
  }
  function readAllVisible() {
    return mark(notifications.value.filter((x) => !x.isRead).map((x) => x.id))
  }
  watch(
    () => auth.token,
    () => {
      epoch++
      listRequest++
      countRequest++
      notifications.value = []
      unreadCount.value = undefined
      loading.value = false
      writing.value = false
      error.value = ''
      countError.value = ''
      visible.value = false
      if (auth.token) void fetchUnreadCount()
    },
    { immediate: true, flush: 'sync' },
  )
  onMounted(() => {
    timer = setInterval(() => {
      void fetchUnreadCount()
    }, 60_000)
  })
  onBeforeUnmount(() => {
    alive = false
    epoch++
    if (timer) clearInterval(timer)
  })
  return {
    notifications,
    notificationLoading: loading,
    notificationWriting: writing,
    unreadCount,
    notificationError: error,
    notificationCountError: countError,
    notificationVisible: visible,
    fetchNotifications,
    fetchUnreadCount,
    readNotification,
    readAllVisible,
  }
}
