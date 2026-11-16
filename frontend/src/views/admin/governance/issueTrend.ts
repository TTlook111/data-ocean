import type { IssueTrendDay, IssueTrendPeriod, QualityIssueTrend } from '../../../api/admin/governance'

/** 年月日以服务端统计时区为准；UTC 仅用于历日加减，避免浏览器时区再次移动日期。 */
function precedingDay(day: string, distance: number) {
  const time = Date.parse(`${day}T00:00:00Z`)
  return Number.isFinite(time) ? new Date(time - distance * 86_400_000).toISOString().slice(0, 10) : ''
}

export function validIssueTrend(
  trend: QualityIssueTrend | undefined, datasourceId: number, snapshotId: number, period: IssueTrendPeriod,
) {
  if (trend?.datasourceId !== datasourceId || trend.snapshotId !== snapshotId ||
      !Array.isArray(trend.days) || trend.days.length !== period ||
      typeof trend.asOf !== 'string' || typeof trend.coverageStart !== 'string') return false
  const today = trend.asOf.slice(0, 10)
  // 同范围也可能收到另一时间窗；缺日、重复日、错属回执和无法核验的数量均不绘成趋势。
  return trend.days.every((day, index) => {
    if (!day || day.date !== precedingDay(today, period - 1 - index)) return false
    const counts = [day.remaining, day.added, day.reopened, day.resolved, day.otherClosed]
    if (day.coverage === 'NONE') return counts.every(count => count == null)
    return ['PARTIAL', 'FULL'].includes(day.coverage) &&
      counts.every(count => typeof count === 'number' && Number.isSafeInteger(count) && count >= 0)
  })
}

/** 余额与当日流量分开；驳回、自动关闭都记入其他移出，不冒充人工解决。 */
export function issueDayFlows(day: IssueTrendDay) {
  if (day.added == null || day.reopened == null || day.resolved == null || day.otherClosed == null) return null
  return { entered: day.added + day.reopened, left: day.resolved + day.otherClosed }
}
