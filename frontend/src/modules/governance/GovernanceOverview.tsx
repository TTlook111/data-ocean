import { ExportOutlined } from '@ant-design/icons';
import { Button, Empty } from 'antd';
import { Link } from 'react-router';
import { queryString } from '../../shared/api';
import { ReadAccessDenied, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { OverviewToolbar } from '../overview/OverviewControls';
import { OverviewPanel } from '../overview/OverviewPanel';
import type { DashboardPoint, DashboardSeries } from '../overview/types';
import { useOverview } from '../overview/useOverview';
import { GovernanceOverviewDrawer } from './GovernanceOverviewDrawer';
import './governance-overview.css';

/**
 * 映射显示读数和后端实际可核对的分类。
 */
const metrics = {
  tableConfirmed: 'TABLE',
  fieldConfirmed: 'COLUMN',
  relationConfirmed: 'RELATION',
  metricConfirmed: 'METRIC',
  pendingReview: 'RECHECK',
  complete: 'COMPLETE',
  inProgress: 'IN_PROGRESS',
  unstarted: 'UNSTARTED',
} as const;

/** 治理看板只能读取与下钻自身指标。 */
type Measure = keyof typeof metrics;

/** 趋势点与期末环图采用同一业务日范围。 */
type Point = DashboardPoint<Measure>;

/** 系列间共享本页的读数和查看入口。 */
type Series = DashboardSeries<Measure>;

// 沿用治理原型的绿青确认线及暖色复核趋势。
const teal = '#108577';
const green = '#548b67';
const slate = '#6c819f';
const amber = '#ad751b';
const confirmations: Series[] = [
  { measure: 'fieldConfirmed', label: '字段确认', color: teal, fill: 0.075 },
  { measure: 'tableConfirmed', label: '表说明确认', color: green, dashed: true },
];
const progress: Series[] = [
  { measure: 'complete', label: '已治理', color: teal },
  { measure: 'inProgress', label: '治理中', color: green },
  { measure: 'unstarted', label: '待治理', color: slate },
];
const rechecks: Series[] = [
  {
    measure: 'pendingReview',
    label: '业务条目待核对',
    summaryLabel: '项期末待核对',
    color: amber,
    step: true,
  },
];
const discovery: Series[] = [
  { measure: 'relationConfirmed', label: '关联确认', color: teal },
  { measure: 'metricConfirmed', label: '指标确认', color: slate, dashed: true },
];
const allSeries = [...confirmations, ...progress, ...rechecks, ...discovery];

/**
 * 按原型展示实际确认、历史期末治理进度及未结复核。
 */
export function GovernanceOverview() {
  const session = useSession();
  const view = useOverview<Point>('/governance/overview');
  const { data, report, range, params, sourceDenied } = view;
  const canDrill = allowed(session, 'governance.view');
  const onDrill = canDrill ? view.openDrill : undefined;
  const selection = canDrill
    ? allSeries.find((item) => item.measure === params.get('metric'))
    : undefined;
  const sourceSearch = range.sourceId ? '?' + queryString({ source: range.sourceId }) : '';

  return (
    <div className="overview-page governance-overview">
      {/* 侧栏保持常驻，工作区使用独立的统计筛选。 */}
      <OverviewToolbar view={view} refreshLabel="刷新治理统计" />
      {sourceDenied ? (
        <ReadAccessDenied title="当前数据源无法查看">
          <Button onClick={() => view.chooseSource('')}>查看全部数据源</Button>
        </ReadAccessDenied>
      ) : (
        <ReadState error={report.error} loading={report.isPending} retry={report.refetch}>
          {data &&
            (data.total ? (
              <div className="overview-grid">
                {/* 高主图在左，进度与待核对在右。 */}
                <OverviewPanel<Measure>
                  title="表字段确认趋势"
                  unit="项"
                  layout="hero"
                  points={data.points}
                  total={data.total}
                  series={confirmations}
                  onDrill={onDrill}
                  footer={
                    canDrill && (
                      <Link to={'/governance' + sourceSearch}>
                        治理工作台 <ExportOutlined />
                      </Link>
                    )
                  }
                />
                <OverviewPanel<Measure>
                  title="期末治理进度"
                  unit="个"
                  layout="ring"
                  ringLabel="个表与视图"
                  points={data.points}
                  total={data.total}
                  series={progress}
                  onDrill={onDrill}
                />
                <OverviewPanel<Measure>
                  title="变更后待核对"
                  unit="项"
                  layout="pending"
                  points={data.points}
                  total={data.total}
                  series={rechecks}
                  note="同一业务条目的多个未结复核只计一次"
                  onDrill={onDrill}
                />
                {/* 下方通栏承接关联和指标的独立审核。 */}
                <OverviewPanel<Measure>
                  title="关联与指标审核"
                  unit="项"
                  layout="relations"
                  points={data.points}
                  total={data.total}
                  series={discovery}
                  onDrill={onDrill}
                />
              </div>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可统计的数据源" />
            ))}
        </ReadState>
      )}
      {/* 明细仍受当前治理查看权限保护。 */}
      {selection && data && (
        <GovernanceOverviewDrawer
          title={selection.label}
          metric={metrics[selection.measure]}
          view={view}
        />
      )}
    </div>
  );
}
