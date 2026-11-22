import { api } from '../../shared/api';
import type { Snapshot } from './types';
import { drawTrend } from './TrendChart';
import { trendFromResult, trendRows } from './trend';
import type { Trend } from './trend';

/**
 * 下载前走同一个实时授权的结果接口，导出本次限量数据。
 */
export async function downloadResult(id: string) {
  const { result } = await api<Snapshot>('/query/requests/' + id);
  if (!result) throw new Error('当前结果已不可下载。');
  const trend = trendFromResult(result);
  const table = trend
    ? trendRows(trend)
    : [result.columns.map((column) => column.label), ...result.rows];
  saveCsv(table, 'DataOcean-问数-' + id.slice(0, 8) + '.csv');
}

/** 保存之前再次核对正式结果的实时查看权限。 */
export async function downloadChart(id: string) {
  const { result } = await api<Snapshot>('/query/requests/' + id);
  const trend = result && trendFromResult(result);
  if (!trend) throw new Error('当前图表已不可下载。');
  await saveChart(trend, 'DataOcean-图表-' + id.slice(0, 8) + '.png');
}

/** 业务结果与本地预览复用同一 CSV 格式。 */
export function saveCsv(table: (string | null)[][], name: string) {
  const csv = table.map((row) => row.map(csvCell).join(',')).join('\r\n');
  saveFile(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }), name);
}

/** 图表图片从同一份可见的图表数据生成。 */
export async function saveChart(trend: Trend, name: string) {
  const canvas = document.createElement('canvas');
  drawTrend(canvas, trend);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
  if (!blob) throw new Error('无法生成图表图片。');
  saveFile(blob, name);
}

/** 避免未使用的临时对象 URL 长时间留在页面。 */
function saveFile(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  // 先让浏览器接收下载，再回收临时结果引用。
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/**
 * 保留数值并阻止自由文本在表格软件中被解释为公式。
 */
function csvCell(cell: string | null) {
  const value = cell ?? '';
  const formula = /^[\s\uFEFF]*[=+@-]/.test(value) && !/^[+-]?\d+(?:\.\d+)?$/.test(value);
  return '"' + (formula ? "'" : '') + value.replaceAll('"', '""') + '"';
}
