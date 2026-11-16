import { http } from '../http'
import type { ApiResult, PageResult } from '../types'

// 工作台以任务创建时间归组；服务端返回的本地时间边界也用于精确明细，客户端不换算时区。
export type ExperiencePeriod = 'today' | 'yesterday' | 'week'
export type ExperienceDetailKind = 'slow' | 'feedback'
export interface ExperienceBucket {
  start: string
  end: string
  measured: number
  slow: number
  avgSeconds: number | null
}
export interface ExperienceUsage {
  total: number
  completed: number
  measured: number
  failed: number
  timedOut: number
  processing: number
  cancelled: number
  clarification: number
  avgSeconds: number | null
  p95Seconds: number | null
  slow: number
  previousAvgSeconds: number | null
  buckets: ExperienceBucket[]
}
export interface ExperienceFeedback {
  eligible: number
  likes: number
  dislikes: number
  previousLikes: number | null
  previousDislikes: number | null
}
export interface QueryExperience {
  period: ExperiencePeriod
  asOf: string
  start: string
  end: string
  targetSeconds: number
  usage: ExperienceUsage | null
  feedback: ExperienceFeedback | null
}
export interface ExperienceDetailScope {
  kind: ExperienceDetailKind
  period: ExperiencePeriod
  asOf: string
  bucketStart?: string
}
export interface ExperienceDetail {
  taskId: string
  datasourceId: number
  createdAt: string
  seconds?: number | null
  reasonCode?: string | null
  comment?: string | null
}

// 一个响应冻结截止时间。审计和反馈各自只在有权范围内聚合；无权区域由服务端返回 null。
export async function getQueryExperience(period: ExperiencePeriod) {
  const { data } = await http.get<ApiResult<QueryExperience>>('/api/admin/dashboard/query-experience', { params: { period } })
  return data.data
}

// 只发送当前明细的冻结时段及页号，任务标识只用于核对，不用它打开他人的会话。
export async function getExperienceDetails(scope: ExperienceDetailScope, pageNo: number) {
  const { data } = await http.get<ApiResult<PageResult<ExperienceDetail>>>('/api/admin/dashboard/query-experience/details', {
    params: { ...scope, pageNo, pageSize: 10 },
  })
  return data.data
}
