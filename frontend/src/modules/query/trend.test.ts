import { describe, expect, it } from 'vitest';
import { importTrend, trendFromResult, trendRows } from './trend';

describe('业务日趋势', () => {
  /** 日期跨度以执行口径为准，真实零值和缺测分别保留。 */
  it('补齐最近七天的日期轴但不编造库存值', () => {
    const trend = trendFromResult({
      columns: [
        { name: 'date', label: '业务日期' },
        { name: 'value', label: '可售库存' },
      ],
      rows: [
        ['2026-11-20', '85'],
        ['2026-11-21', '0'],
      ],
      series: { start: '2026-11-16', end: '2026-11-22' },
      scope: '',
      dataUpdatedAt: null,
      truncated: false,
      unit: '箱',
    });
    expect(trend?.points).toHaveLength(7);
    expect(trend?.points.slice(-3)).toEqual([
      { day: '2026-11-20', value: '85' },
      { day: '2026-11-21', value: '0' },
      { day: '2026-11-22', value: null },
    ]);
  });

  /** 读取真实两列 CSV 时保留单位、空值与引号。 */
  it('导入后仍保留可导出的时间范围和内容', () => {
    const trend = importTrend(
      '\uFEFF"业务日期","A, ""B""（箱）"\r\n"2026-11-20","85"\r\n"2026-11-22","0"',
    );
    expect(trend.title).toBe('A, "B"');
    expect(trend.unit).toBe('箱');
    expect(trendRows(trend)).toEqual([
      ['业务日期', 'A, "B"（箱）'],
      ['2026-11-20', '85'],
      ['2026-11-21', null],
      ['2026-11-22', '0'],
    ]);
  });

  /** 拒绝重复日期、非法日期和将表达式当作图表数据。 */
  it.each([
    '日期,数值\n2026-11-20,85\n2026-11-20,90',
    '日期,数值\n2026-02-30,85',
    '日期,数值\n2026-11-20,=2+3',
  ])('无效 CSV 不进入预览', (csv) => {
    expect(() => importTrend(csv)).toThrow();
  });
});
