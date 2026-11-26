import type { QueryResult } from './types';

/**
 * 图表保留原值用于导出，缺测和零分开。
 */
export type TrendPoint = { day: string; value: string | null };

/**
 * 来源查询的图表与导出共用同一日序列形状。
 */
export type Trend = { title: string; unit: string; points: TrendPoint[] };

const dayMs = 86_400_000;
const maxDays = 366;

/**
 * 只接受标准 ISO 业务日，避免浏览器把无效月日自动进位。
 */
function dayTime(day: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new Error('日期需使用 YYYY-MM-DD。');
  const time = Date.parse(day + 'T00:00:00Z');
  if (!Number.isFinite(time) || new Date(time).toISOString().slice(0, 10) !== day) {
    throw new Error('结果包含无效业务日期。');
  }
  return time;
}

/**
 * 视图补齐日期轴，缺少源记录时只留空。
 */
function axis(start: string, end: string, actual: Map<string, string | null>) {
  const first = dayTime(start);
  const length = (dayTime(end) - first) / dayMs + 1;
  if (length < 1 || length > maxDays) throw new Error('日期范围最多支持 366 天。');
  return Array.from({ length }, (_, index) => {
    const day = new Date(first + index * dayMs).toISOString().slice(0, 10);
    return { day, value: actual.get(day) ?? null };
  });
}

/**
 * 仅按后端标记的真实时间序列呈现趋势图。
 */
export function trendFromResult(result: QueryResult): Trend | null {
  if (!result.series || result.columns[0]?.name !== 'date' || result.columns[1]?.name !== 'value') {
    return null;
  }
  const actual = new Map<string, string | null>();
  for (const [day, value] of result.rows) {
    if (day) actual.set(day, value ?? null);
  }
  return {
    title: result.columns[1].label,
    unit: result.unit,
    points: axis(result.series.start, result.series.end, actual),
  };
}

/**
 * 导出保留原业务日轴、标题和计量单位。
 */
export function trendRows(trend: Trend) {
  const label = trend.unit ? trend.title + '（' + trend.unit + '）' : trend.title;
  return [['业务日期', label], ...trend.points.map(({ day, value }) => [day, value])];
}
