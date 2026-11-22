import type { QueryResult } from './types';

/**
 * 图表保留原值用于导出，缺测和零分开。
 */
export type TrendPoint = { day: string; value: string | null };

/**
 * 来源查询和本地导入共用同一日序列形状。
 */
export type Trend = { title: string; unit: string; points: TrendPoint[] };

const dayMs = 86_400_000;
const maxDays = 366;
const numberText = /^[+-]?\d+(?:\.\d+)?$/;

/**
 * 只接受标准 ISO 业务日，避免浏览器把无效月日自动进位。
 */
function dayTime(day: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new Error('日期需使用 YYYY-MM-DD。');
  const time = Date.parse(day + 'T00:00:00Z');
  if (!Number.isFinite(time) || new Date(time).toISOString().slice(0, 10) !== day) {
    throw new Error('文件包含无效日期。');
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
 * 保留日轴、标题和单位，以便导出后重新打开同一组图表数据。
 */
export function trendRows(trend: Trend) {
  const label = trend.unit ? trend.title + '（' + trend.unit + '）' : trend.title;
  return [['业务日期', label], ...trend.points.map(({ day, value }) => [day, value])];
}

/**
 * 区分 CSV 引号内的分隔符、转义引号及普通行尾。
 */
function readRows(text: string) {
  const rows: string[][] = [];
  let row: string[] = [];
  let value = '';
  let quoted = false;
  let closed = false;
  const content = text.replace(/^\uFEFF/, '');

  /** 提交一个字段或一行，仅保留有内容的行。 */
  function append(endRow: boolean) {
    row.push(value);
    value = '';
    closed = false;
    if (endRow) {
      if (row.some(Boolean)) rows.push(row);
      row = [];
    }
  }

  for (let i = 0; i < content.length; i++) {
    const char = content[i];
    if (quoted) {
      if (char === '"' && content[i + 1] === '"') {
        value += '"';
        i++;
      } else if (char === '"') {
        quoted = false;
        closed = true;
      } else {
        value += char;
      }
    } else if (char === ',') {
      append(false);
    } else if (char === '\r' || char === '\n') {
      append(true);
      if (char === '\r' && content[i + 1] === '\n') i++;
    } else if (closed || (char === '"' && value)) {
      throw new Error('CSV 引号或分隔符不正确。');
    } else if (char === '"') {
      quoted = true;
    } else {
      value += char;
    }
  }
  if (quoted) throw new Error('CSV 内有未闭合的引号。');
  if (value || row.length || closed) append(true);
  return rows;
}

/**
 * 导入日期和值两列，在本地预览，数字字段无需执行表达式。
 */
export function importTrend(text: string): Trend {
  const [header, ...rows] = readRows(text);
  if (!header || header.length !== 2 || !header[1].trim() || !rows.length) {
    throw new Error('需要带表头的两列 CSV，第一列日期，第二列数值。');
  }
  const actual = new Map<string, string | null>();
  for (const row of rows) {
    const [day, value] = row.map((cell) => cell.trim());
    if (row.length !== 2 || actual.has(day)) throw new Error('日期重复或数据列数不正确。');
    dayTime(day);
    if (
      value &&
      (value.length > 100 || !numberText.test(value) || !Number.isFinite(Number(value)))
    ) {
      throw new Error('数值列只能是数字或空值。');
    }
    actual.set(day, value || null);
  }
  const dates = [...actual.keys()].sort();
  const unitLabel = header[1].trim().match(/^(.+)（([^（）]+)）$/);
  return {
    title: unitLabel?.[1] ?? header[1].trim(),
    unit: unitLabel?.[2] ?? '',
    points: axis(dates[0], dates[dates.length - 1], actual),
  };
}
