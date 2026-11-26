import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { appTheme } from '../../app/theme';
import { queryString } from '../../shared/api';
import { ReadState } from '../../shared/Records';
import { OverviewToolbar } from '../overview/OverviewControls';
import { OverviewPanel } from '../overview/OverviewPanel';
import type {
  DashboardPoint,
  DashboardRange,
  DashboardSeries,
  OverviewReport,
} from '../overview/types';
import { useOverview } from '../overview/useOverview';
import './model-overview.css';

/**
 * 用量和结果分别读取同一批诊断回执的独立指标。
 */
type UsageMeasure = 'input' | 'output' | 'tokens';
type OutcomeMeasure = 'succeeded' | 'failed' | 'uncertain';
type RankMeasure = `model:${string}`;

/**
 * 已知用量与调用结果按同一业务日期分组。
 */
type ModelPoint = DashboardPoint<UsageMeasure | OutcomeMeasure>;

/**
 * 概览汇总全站模型调用，没有数据源筛选维度。
 */
type ModelReport = OverviewReport<ModelPoint> & {
  /** 按已知用量降序的模型配置。 */
  ranking: { id: string; name: string; tokens: number }[];
};

// 堆叠柱的色阶与已确认的主题蓝原型一致。
const usage: DashboardSeries<UsageMeasure>[] = [
  {
    measure: 'input',
    label: '输入 Token',
    summaryLabel: '输入',
    color: appTheme.token?.colorPrimary ?? '#2f63d5',
    peakColor: appTheme.token?.colorPrimaryHover ?? '#244eaa',
  },
  {
    measure: 'output',
    label: '输出 Token',
    summaryLabel: '输出',
    color: '#a3c3f5',
    peakColor: '#82acef',
  },
];
const headlines: DashboardSeries<UsageMeasure>[] = [
  { measure: 'tokens', label: '总用量', summaryLabel: 'Token', color: '#1f2937' },
  ...usage,
];
const outcomes: DashboardSeries<OutcomeMeasure>[] = [
  { measure: 'succeeded', label: '成功', color: '#5876a0', fill: 0.07 },
  { measure: 'failed', label: '失败', color: '#b74f46', dashed: true },
  { measure: 'uncertain', label: '待核查', color: '#926916', dashed: true },
];

/**
 * 在系统配置中展示真实调用的用量趋势、排名和结果。
 */
export function ModelOverview() {
  // 日期组件跨加载和成功状态仍保留开启状态，快速切换期间不会关闭日历。
  const [calendarOpen, setCalendarOpen] = useState(false);
  const view = useOverview<ModelPoint, ModelReport>('/models/calls/overview', '7');
  const { data, range, period, report } = view;
  const navigate = useNavigate();
  const ranking = useMemo(() => {
    // 用稳定配置编号作为类别，图表和数据表共享同一组实际排名。
    const ranked = data?.ranking ?? [];
    return {
      total: {
        fromDate: data?.total?.fromDate ?? '',
        toDate: data?.total?.toDate ?? '',
        ...Object.fromEntries(ranked.map(({ id, tokens }) => [`model:${id}`, tokens])),
      },
      series: ranked.map(({ id, name }, index): DashboardSeries<RankMeasure> => ({
        measure: `model:${id}`,
        label: name,
        color: index === 0 ? '#6282ae' : '#a0b2cb',
      })),
    };
  }, [data]);

  /**
   * 下钻固定图点日期及此前的数据库时刻，并保留返回的原筛选条件。
   */
  function openCalls(metric: OutcomeMeasure, point: DashboardRange) {
    if (!data) return;
    void navigate(
      '/diagnostics?' +
        queryString({
          fromDate: point.fromDate,
          toDate: point.toDate,
          asOf: data.asOf,
          metric,
          overviewPeriod: period,
          overviewFromDate: range.fromDate,
          overviewToDate: range.toDate,
        }),
    );
  }

  const controls = (
    <OverviewToolbar
      view={view}
      refreshLabel="刷新模型调用统计"
      showContext={false}
      datePicker={{ open: calendarOpen, onOpenChange: setCalendarOpen }}
    />
  );
  const total = data?.total;
  return (
    <div className="overview-page model-usage-overview">
      {/* 请求失败时仍可调整原期间或重新读取。 */}
      {!total && controls}
      <ReadState error={report.error} loading={report.isPending} retry={report.refetch}>
        {data && total && (
          <div className="overview-grid">
            {/* 通栏主图直接呈现总量、输入与输出以及期间峰值。 */}
            <OverviewPanel<UsageMeasure>
              title="Token 消耗趋势"
              layout="hero"
              unit=""
              stacked
              points={data.points}
              total={total}
              series={usage}
              headlineSeries={headlines}
              controls={controls}
            />
            {/* 用量排行与调用结果并排，窄桌面按阅读顺序往下排列。 */}
            <OverviewPanel<RankMeasure>
              title="模型用量排行"
              layout="rank"
              unit="Token"
              points={[]}
              total={ranking.total}
              series={ranking.series}
              emptyText="所选期间暂无已记录的 Token 用量"
            />
            <OverviewPanel<OutcomeMeasure>
              title="调用结果趋势"
              layout="trend"
              unit="次"
              points={data.points}
              total={total}
              series={outcomes}
              chartDrill
              onDrill={openCalls}
            />
          </div>
        )}
      </ReadState>
    </div>
  );
}
