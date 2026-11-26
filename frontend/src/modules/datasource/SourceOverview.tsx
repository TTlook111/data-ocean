import { ExportOutlined } from '@ant-design/icons';
import { Button, Empty } from 'antd';
import { Link } from 'react-router';
import { queryString } from '../../shared/api';
import { ReadAccessDenied, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { OverviewToolbar } from '../overview/OverviewControls';
import { useOverview } from '../overview/useOverview';
import { OverviewPanel } from '../overview/OverviewPanel';
import type { DashboardRange, DashboardSeries } from '../overview/types';
import { SourceOverviewDrawer } from './SourceOverviewDrawer';
import './source-overview.css';

/**
 * 接入概览的读数分别映射到任务终态、有效样本和日末存量。
 */
export const sourceOverviewMetrics = {
  succeeded: 'SUCCEEDED',
  failed: 'FAILED',
  cancelled: 'CANCELLED',
  pending: 'PENDING',
  averageCollectionSeconds: 'DURATION',
} as const;

/**
 * 页面可读取和下钻的指标名称来自接口字段。
 */
export type SourceMeasure = keyof typeof sourceOverviewMetrics;

/**
 * 采集任务是期间发生量，待事实核实是期末存量。
 */
type SourcePoint = DashboardRange & {
  /** 期间结束的成功任务。 */
  succeeded: number;
  /** 期间结束的失败任务。 */
  failed: number;
  /** 期间确认取消的任务。 */
  cancelled: number;
  /** 截至分组末仍待核实的变化。 */
  pending: number;
  /** 成功采集实际执行的平均秒数。 */
  averageCollectionSeconds: number | null;
};

type Series = DashboardSeries<SourceMeasure>;

// 沿用接入原型的蓝青主色、失败红色虚线与存量蓝灰。
const blue = '#3175cb';
const red = '#b44f54';
const cyan = '#087e90';
const slate = '#637b9a';

const collection: Series[] = [
  { measure: 'succeeded', label: '采集成功', color: blue, fill: 0.075 },
  { measure: 'failed', label: '采集失败', color: red, dashed: true },
];
const outcomes: Series[] = [
  { measure: 'succeeded', label: '成功', color: blue },
  { measure: 'failed', label: '失败', color: red },
  { measure: 'cancelled', label: '已取消', color: slate },
];
const duration: Series[] = [
  {
    measure: 'averageCollectionSeconds',
    label: '平均采集耗时',
    color: cyan,
    fill: 0.055,
    decimals: 1,
    summaryLabel: '秒平均耗时',
  },
];
const pending: Series[] = [
  {
    measure: 'pending',
    label: '待事实核实',
    color: slate,
    step: true,
    summaryLabel: '项期末待核实',
  },
];
const allSeries = [...collection, ...outcomes, ...duration, ...pending];

/**
 * 接入概览显示平台采集台账及按原时间区间可核对的贡献记录。
 */
export function SourceOverview() {
  const session = useSession();
  const view = useOverview<SourcePoint>('/datasources/overview');
  const { data, report, range, params, sourceDenied } = view;
  const canDrill = allowed(session, 'collections.view');
  const drill = canDrill
    ? allSeries.find((item) => item.measure === params.get('metric'))
    : undefined;
  const onDrill = canDrill ? view.openDrill : undefined;
  const sourceSearch = range.sourceId ? '?' + queryString({ source: range.sourceId }) : '';

  return (
    <div className="overview-page sources-overview">
      {/* 顶栏沿用原型中分开的来源与期间区。 */}
      <OverviewToolbar view={view} refreshLabel="刷新接入统计" />
      {sourceDenied ? (
        <ReadAccessDenied title="当前数据源无法查看">
          <Button onClick={() => view.chooseSource('')}>查看全部数据源</Button>
        </ReadAccessDenied>
      ) : (
        <ReadState error={report.error} loading={report.isPending} retry={report.refetch}>
          {data &&
            (data.total ? (
              <div className="overview-grid">
                {/* 上排左侧任务趋势与右侧终态分布。 */}
                <OverviewPanel<SourceMeasure>
                  title="采集运行趋势"
                  unit="次"
                  layout="hero"
                  points={data.points}
                  total={data.total}
                  series={collection}
                  onDrill={onDrill}
                  footer={
                    canDrill && (
                      <Link to={'/collections' + sourceSearch}>
                        采集记录 <ExportOutlined />
                      </Link>
                    )
                  }
                />
                <OverviewPanel<SourceMeasure>
                  title="采集结果分布"
                  unit="次"
                  layout="ring"
                  ringLabel="次已结束采集"
                  points={data.points}
                  total={data.total}
                  series={outcomes}
                  onDrill={onDrill}
                />
                {/* 下排左侧成功耗时与右侧事实待核实存量。 */}
                <OverviewPanel<SourceMeasure>
                  title="采集耗时"
                  unit="秒"
                  layout="duration"
                  emptyText="所选期间暂无成功采集耗时样本"
                  points={data.points}
                  total={data.total}
                  series={duration}
                  onDrill={onDrill}
                />
                <OverviewPanel<SourceMeasure>
                  title="待核实变化"
                  unit="项"
                  layout="pending"
                  note="每个区间取期末仍待事实核实的结构变化"
                  points={data.points}
                  total={data.total}
                  series={pending}
                  onDrill={onDrill}
                  footer={
                    allowed(session, 'catalog.view') && (
                      <Link to={'/catalog' + sourceSearch}>
                        元数据目录 <ExportOutlined />
                      </Link>
                    )
                  }
                />
              </div>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可统计的数据源" />
            ))}
        </ReadState>
      )}
      {/* 明细只在当前身份获准时打开，截止时刻随原数值固定。 */}
      {drill && data && (
        <SourceOverviewDrawer
          metric={sourceOverviewMetrics[drill.measure]}
          title={drill.label}
          view={view}
        />
      )}
    </div>
  );
}
