import { BarChart, LineChart, PieChart, ScatterChart } from 'echarts/charts';
import type {
  BarSeriesOption,
  LineSeriesOption,
  PieSeriesOption,
  ScatterSeriesOption,
} from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import type { GridComponentOption, TooltipComponentOption } from 'echarts/components';
import { init, use as registerCharts } from 'echarts/core';
import type { ComposeOption, EChartsType } from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { periodLabel, valueText } from './types';
import type { DashboardPoint, DashboardSeries } from './types';

// 懒加载页面只注册趋势和分类图需要的渲染组件。
registerCharts([
  BarChart,
  LineChart,
  PieChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  SVGRenderer,
]);

/**
 * 限制图表选项为此页面实际使用的组件。
 */
type Options = ComposeOption<
  | BarSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | ScatterSeriesOption
  | GridComponentOption
  | TooltipComponentOption
>;

/**
 * 在一个焦点内读取各图的数据点，完整数值另有可展开数据表。
 */
export function OverviewChart<M extends string>({
  title,
  points,
  series,
  ring = false,
  dots = false,
  rank = false,
  stacked = false,
  ringSurface = '#ffffff',
  onDrill,
}: {
  /** 图表的可访问名称。 */
  title: string;
  /** 趋势点或环图对应的全期统计。 */
  points: DashboardPoint<M>[];
  /** 保持顺序的指标图例。 */
  series: DashboardSeries<M>[];
  /** 结案分布使用环图。 */
  ring?: boolean;
  /** 可重叠维护状态使用分类点图。 */
  dots?: boolean;
  /** 按总用量排列的水平条形图。 */
  rank?: boolean;
  /** 输入及输出在同一时间柱内堆叠。 */
  stacked?: boolean;
  /** 环图分割线使用面板底色。 */
  ringSurface?: string;
  /** 从选中的指标和时间点打开原回执。 */
  onDrill?: (measure: M, point: DashboardPoint<M>) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const chart = useRef<EChartsType>(null);
  const [active, setActive] = useState<number | null>(null);
  const distribution = ring || dots || rank;
  const width = distribution ? series.length : points.length;
  const position = active === null ? null : Math.min(active, width - 1);
  const options = useMemo<Options>(() => {
    // 环形分布使用全期互斥分类，直接对应图例和统计明细。
    if (ring) {
      return {
        animation: false,
        tooltip: { trigger: 'item', confine: true, renderMode: 'richText' },
        series: [
          {
            type: 'pie',
            radius: ['66%', '86%'],
            label: { show: false },
            stillShowZeroSum: false,
            showEmptyCircle: true,
            emptyCircleStyle: { color: '#e7e2d8' },
            itemStyle: { borderWidth: 2, borderColor: ringSurface, borderRadius: 1 },
            data: series.map((item) => ({
              name: item.label,
              value: points[0]?.[item.measure] ?? 0,
              itemStyle: { color: item.color },
            })),
          },
        ],
      };
    }
    if (dots) {
      // 各分类使用同一数量轴，类别重叠时各自显示原值。
      return {
        animation: false,
        grid: { left: 8, right: 30, top: 18, bottom: 12, outerBoundsMode: 'same' },
        tooltip: { trigger: 'item', confine: true, renderMode: 'richText' },
        xAxis: {
          type: 'value',
          min: 0,
          minInterval: 1,
          splitNumber: 4,
          axisLabel: { color: '#526279' },
          splitLine: { lineStyle: { color: '#e8edf2' } },
        },
        yAxis: {
          type: 'category',
          inverse: true,
          data: series.map((item) => item.label),
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#526279' },
        },
        series: [
          {
            type: 'scatter',
            symbolSize: 10,
            encode: { x: 0, y: 1, tooltip: [0] },
            label: {
              show: true,
              position: 'right',
              formatter: ({ value }) => (Array.isArray(value) ? String(value[0]) : ''),
            },
            data: series.map((item, index) => ({
              name: item.label,
              value: [points[0]?.[item.measure] ?? '-', index],
              itemStyle: { color: item.color },
            })),
          },
        ],
      };
    }
    if (rank) {
      // 长模型名称在轴上限宽，提示和数据表保留全称与精确用量。
      return {
        animation: false,
        grid: { left: 4, right: 60, top: 8, bottom: 12, outerBoundsMode: 'same' },
        tooltip: {
          trigger: 'item',
          confine: true,
          renderMode: 'richText',
          valueFormatter: (value) => valueText(Number(value)) + ' Token',
        },
        xAxis: { type: 'value', show: false, min: 0 },
        yAxis: {
          type: 'category',
          inverse: true,
          data: series.map((item) => item.label),
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#526279', width: 142, overflow: 'truncate' },
        },
        series: [
          {
            type: 'bar',
            barMaxWidth: 22,
            itemStyle: { borderRadius: [0, 3, 3, 0] },
            label: {
              show: true,
              position: 'right',
              color: '#1f2937',
              formatter: ({ value }) => valueText(Number(value)),
            },
            data: series.map((item) => ({
              name: item.label,
              value: points[0]?.[item.measure] ?? 0,
              itemStyle: { color: item.color },
            })),
          },
        ],
      };
    }
    // 峰值采用已知用量之和，强调色沿用当次系列的主题色。
    const stacks = stacked
      ? points.map((point) => series.reduce((sum, item) => sum + (point[item.measure] ?? 0), 0))
      : [];
    const peak = stacks.reduce((largest, amount) => Math.max(largest, amount), 0);
    const peakIndex = peak > 0 ? stacks.indexOf(peak) : -1;
    const peakSeries = series.reduce((last, item, index) => {
      // 只在该柱最后一个可见分段上显示总峰值。
      return (points[peakIndex]?.[item.measure] ?? 0) > 0 ? index : last;
    }, -1);
    return {
      animation: false,
      grid: { left: 4, right: 16, top: stacked ? 28 : 14, bottom: 4, outerBoundsMode: 'same' },
      tooltip: {
        trigger: 'axis',
        confine: true,
        renderMode: 'richText',
        axisPointer: { type: 'line' },
      },
      xAxis: {
        type: 'category',
        boundaryGap: stacked,
        data: points.map((point) => point.toDate),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          color: '#526279',
          hideOverlap: true,
          showMinLabel: true,
          showMaxLabel: true,
          formatter: (date: string, index: number) => {
            // 有调用但全段未知时标出原因，区别于没有调用的零值。
            const label = date.slice(5).replace('-', '/');
            return stacked && series.every((item) => points[index]?.[item.measure] === null)
              ? label + '\n用量未知'
              : label;
          },
        },
      },
      yAxis: {
        type: 'value',
        min: 0,
        minInterval: series.some((item) => item.decimals) ? undefined : 1,
        splitNumber: 4,
        axisLabel: {
          color: '#526279',
          formatter: stacked
            ? (value: number) => (value >= 1000 ? `${value / 1000}k` : String(value))
            : undefined,
        },
        splitLine: { lineStyle: { color: '#e8edf2' } },
      },
      series: series.map((item, index): LineSeriesOption | BarSeriesOption => {
        // 少量真实发生日用圆点标示，连续趋势维持原型的纯线条。
        const values = points.map((point) => point[item.measure]);
        if (stacked) {
          // 两种用量共用柱宽，峰值标签贴在该柱最后一个非零分段上。
          return {
            name: item.label,
            type: 'bar',
            stack: 'usage',
            barMaxWidth: 68,
            data: values,
            itemStyle: {
              color: ({ dataIndex }) =>
                dataIndex === peakIndex ? (item.peakColor ?? item.color) : item.color,
              borderRadius: index === series.length - 1 ? [5, 5, 0, 0] : 0,
            },
            label: {
              show: index === peakSeries,
              position: 'top',
              distance: 8,
              color: series[0]?.peakColor,
              fontWeight: 600,
              fontSize: 14,
              formatter: ({ dataIndex }) => (dataIndex === peakIndex ? valueText(peak) : ''),
            },
          };
        }
        const decimalSeries = Boolean(item.decimals);
        const sparse = values.filter((value) => value !== null && value > 0).length <= 4;
        return {
          name: item.label,
          type: 'line',
          data: values,
          connectNulls: false,
          step: item.step ? 'end' : undefined,
          symbol: 'circle',
          showSymbol: Boolean(onDrill) || decimalSeries || sparse,
          symbolSize: (_value, { dataIndex }) => {
            // 连续零值无需逐点标记，单日和耗时仍可识别。
            return decimalSeries || points.length === 1 || (values[dataIndex] ?? 0) > 0 ? 6 : 0;
          },
          itemStyle: { color: item.color },
          lineStyle: { color: item.color, type: item.dashed ? 'dashed' : 'solid', width: 2 },
          areaStyle: item.fill ? { color: item.color, opacity: item.fill } : undefined,
        };
      }),
    };
  }, [points, ring, dots, rank, stacked, ringSurface, series, onDrill]);

  useEffect(() => {
    // 容器宽度随侧栏和工作区变化，图表随容器重排。
    if (!host.current) return;
    const instance = init(host.current, null, { renderer: 'svg' });
    chart.current = instance;
    const observer = new ResizeObserver(() => instance.resize());
    observer.observe(host.current);
    return () => {
      // 路由离开后释放图表以及观察者。
      observer.disconnect();
      instance.dispose();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    // 后台统计刷新时用完整选项替换数据，保留组件与键盘入口。
    chart.current?.setOption(options, { notMerge: true });
  }, [options]);

  useEffect(() => {
    // 明细只针对有记录的图点。取消订阅时保留其他图表事件。
    const instance = chart.current;
    if (!onDrill || !instance) return;
    const read = ({ dataIndex, seriesIndex }: { dataIndex: number; seriesIndex?: number }) => {
      const point = points[dataIndex],
        item = series[seriesIndex ?? -1];
      if (point && item && (point[item.measure] ?? 0) > 0) onDrill(item.measure, point);
    };
    instance.on('click', 'series', read);
    return () => {
      instance.off('click', read);
    };
  }, [onDrill, points, series]);

  /**
   * 方向键移动一格，Home 和 End 定位端点，Esc 关闭提示。
   */
  function readPoint(event: KeyboardEvent<HTMLDivElement>) {
    if (!width) return;
    if (event.key === 'Escape') {
      // 关闭当前提示并保留键盘焦点。
      event.preventDefault();
      clearPoint();
      return;
    }
    if (event.key === 'Enter' && position !== null && onDrill) {
      // 同一点的各状态仍可在数据表逐项选择，键盘图点进入第一个有记录的状态。
      const point = points[position];
      const item = series.find(({ measure }) => (point[measure] ?? 0) > 0);
      if (item) onDrill(item.measure, point);
      return;
    }
    const next = {
      ArrowLeft: position === null ? width - 1 : Math.max(0, position - 1),
      ArrowRight: position === null ? 0 : Math.min(width - 1, position + 1),
      Home: 0,
      End: width - 1,
    }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    setActive(next);
    if (distribution) {
      // 分类图键盘读数时同时高亮当前类别。
      chart.current?.dispatchAction({ type: 'downplay', seriesIndex: 0 });
      chart.current?.dispatchAction({ type: 'highlight', seriesIndex: 0, dataIndex: next });
    }
    chart.current?.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: next });
  }

  /**
   * 结束读数时清理提示与对读屏器的临时播报。
   */
  function clearPoint() {
    setActive(null);
    chart.current?.dispatchAction({ type: 'hideTip' });
    if (distribution) {
      // 分类图退出辅助读数后恢复常态。
      chart.current?.dispatchAction({ type: 'downplay', seriesIndex: 0 });
    } else {
      // 趋势图的轴线提示独立于提示框，离开读数时一并关闭。
      chart.current?.dispatchAction({ type: 'updateAxisPointer', currTrigger: 'leave' });
    }
  }

  const point = position === null ? null : points[distribution ? 0 : position];
  const readings = distribution && position !== null ? [series[position]] : series;
  return (
    <div
      className={ring ? 'overview-plot is-ring' : 'overview-plot'}
      role="group"
      aria-label={title + '，使用左右方向键读数' + (onDrill ? '，按 Enter 查看明细' : '')}
      tabIndex={0}
      onKeyDown={readPoint}
      onBlur={clearPoint}
    >
      {/* 图形内容的可访问数值由下方播报与数据表提供。 */}
      <div className="overview-chart-host" aria-hidden="true" ref={host} />
      <span className="sr-only" aria-live="polite">
        {point &&
          [
            periodLabel(point),
            ...readings.map(
              ({ label, measure, decimals }) => `${label} ${valueText(point[measure], decimals)}`,
            ),
          ].join('，')}
      </span>
    </div>
  );
}
