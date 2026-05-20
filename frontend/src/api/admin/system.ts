import { http } from '../http'
import type { ApiResult } from './user'

export interface SyncScheduleInfo {
  cron: string
  enabled: boolean
  running: boolean
}

export interface SyncSchedulePayload {
  cron: string
  enabled?: boolean
}

export async function getSyncSchedule() {
  const { data } = await http.get<ApiResult<SyncScheduleInfo>>('/api/admin/system/sync-schedule')
  return data
}

export async function updateSyncSchedule(payload: SyncSchedulePayload) {
  const { data } = await http.put<ApiResult<SyncScheduleInfo>>('/api/admin/system/sync-schedule', payload)
  return data
}
