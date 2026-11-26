import { describe, expect, it } from 'vitest';
import { trendFromResult, trendRows } from './trend';

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

  /** 正式趋势的单位和缺测同时保留在导出数据中。 */
  it('导出行沿用图表的业务日期与单位', () => {
    expect(
      trendRows({
        title: '可售库存',
        unit: '箱',
        points: [
          { day: '2026-11-20', value: '85' },
          { day: '2026-11-21', value: null },
          { day: '2026-11-22', value: '0' },
        ],
      }),
    ).toEqual([
      ['业务日期', '可售库存（箱）'],
      ['2026-11-20', '85'],
      ['2026-11-21', null],
      ['2026-11-22', '0'],
    ]);
  });
});
