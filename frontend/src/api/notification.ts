import { http } from './http'
import type { ApiResult, PageResult } from './types'

export interface NotificationItem {
  id: number
  type: string
  title: string
  content: string
  targetUserId?: number
  isRead: boolean
  createdAt: string
}

export async function listNotifications(params: { page?: number; pageSize?: number }) {
  const { data } = await http.get<ApiResult<PageResult<NotificationItem>>>('/api/notifications', { params })
  return data
}

export async function markNotificationAsRead(id: number) {
  const { data } = await http.patch<ApiResult<null>>(`/api/notifications/${id}/read`)
  return data
}

export async function getUnreadNotificationCount() {
  const { data } = await http.get<ApiResult<{ count: number }>>('/api/notifications/unread-count')
  return data
}
