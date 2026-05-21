import { http } from '../http'
import type { ApiResult } from './user'

export interface DashboardStats {
  totalUsers: number
  totalDatasources: number
  activeDatasources: number
  totalSnapshots: number
  publishedSnapshots: number
  totalTables: number
  totalColumns: number
  openIssues: number
  resolvedIssues: number
  avgQualityScore: number | null
  recentActivities: RecentActivity[]
}

export interface RecentActivity {
  type: string
  description: string
  time: string
}

export async function getDashboardStats() {
  const { data } = await http.get<ApiResult<DashboardStats>>('/api/admin/dashboard/stats')
  return data
}
