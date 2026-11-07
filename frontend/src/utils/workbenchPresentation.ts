import type { LocationQuery } from 'vue-router'
import type { ExperienceBucket, ExperienceDetailScope, ExperiencePeriod, QueryExperience } from '../api/admin/dashboard'

export const experiencePeriods = { today: '今天', yesterday: '昨天', week: '近 7 天' }

// 外部书签必须带完整来源时段。坏的 period、截止时间或桶一律不降级为别的业务集合。
export function isExperiencePeriod(value: unknown): value is ExperiencePeriod {
  return value === 'today' || value === 'yesterday' || value === 'week'
}

function isLocalTime(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?$/.test(value)
}

// 在展示边界拒绝缺字段的统计：缺票数不能变成 0%，缺耗时不能触发 toFixed 或生成假趋势。
export function validExperience(report: QueryExperience | null, period: ExperiencePeriod): boolean {
  if (report?.period !== period || ![report.start, report.end, report.asOf].every(isLocalTime) || !(report.targetSeconds > 0)) return false
  const count = (n: unknown) => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0
  const seconds = (n: unknown) => n === null || (typeof n === 'number' && Number.isFinite(n) && n >= 0)
  const { usage: u, feedback: f } = report
  const validUsage = u === null || !!u &&
    [u.total, u.completed, u.measured, u.slow, u.cancelled, u.clarification, u.failed, u.timedOut, u.processing].every(count) &&
    [u.avgSeconds, u.p95Seconds, u.previousAvgSeconds].every(seconds) && Array.isArray(u.buckets) &&
    u.buckets.every(b => b && isLocalTime(b.start) && isLocalTime(b.end) && count(b.measured) && count(b.slow) && seconds(b.avgSeconds))
  // 覆盖条按已返回回答计数，同一回答最多一张有效票；原生进度条会把越界值静默截成满幅。
  const validFeedback = f === null || !!f && [f.eligible, f.likes, f.dislikes].every(count) &&
    f.likes + f.dislikes <= f.eligible &&
    [f.previousLikes, f.previousDislikes].every(n => n === null || count(n))
  return validUsage && validFeedback
}

export function readDetailScope(query: LocationQuery): ExperienceDetailScope | null {
  const { period, kind, asOf, bucketStart } = query
  if (!isExperiencePeriod(period) || !isLocalTime(asOf)) return null
  if (kind === 'slow' && isLocalTime(bucketStart)) return { period, kind, asOf, bucketStart }
  if (kind === 'feedback' && bucketStart === undefined) return { period, kind, asOf }
  return null
}

// 服务端负责自然日及分桶。这里只截取其本地钟面，不让浏览器时区移动日期或重算桶边界。
export function timeLabel(value: string): string { return value.substring(11, 16) }
export function dateLabel(value: string): string { return value.substring(0, 10) }
// 红线严格按原始均值判断。略超目标但四舍五入与目标相同时，读数仍要能解释红色。
export function waitLabel(seconds: number | null, target: number): string {
  if (seconds === null) return '—'
  const rounded = seconds.toFixed(1)
  return seconds > target && Number(rounded) === target ? '>' + rounded : rounded
}
export function bucketLabel(bucket: ExperienceBucket, period: ExperiencePeriod): string {
  if (period === 'week') return dateLabel(bucket.start)
  const end = bucket.end.slice(0, 10) > bucket.start.slice(0, 10) && timeLabel(bucket.end) === '00:00' ? '24:00' : timeLabel(bucket.end)
  return `${timeLabel(bucket.start)}–${end}`
}

// 优先选有样本且平均等候时间最长的桶；没有有效测量时不创建虚构的默认排查对象。
export function slowestMeasuredBucket(buckets: ExperienceBucket[]): ExperienceBucket | undefined {
  return buckets.reduce<ExperienceBucket | undefined>((best, item) =>
    item.measured > 0 && item.avgSeconds !== null && (!best || item.avgSeconds > best.avgSeconds!) ? item : best, undefined)
}
