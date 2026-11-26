import { ExportOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { Link } from 'react-router';
import { queryString } from '../../shared/api';
import { ReadAccessDenied, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { OverviewToolbar } from '../overview/OverviewControls';
import { useOverview } from '../overview/useOverview';
import { OverviewPanel } from '../overview/OverviewPanel';
import { FeedbackContributionDrawer } from './FeedbackContributionDrawer';
import type { Measure, Point, Series } from './types';
import './feedback-overview.css';

// 原型用暖橙标新增与等待，青绿表示实际解决，蓝灰承担期末存量。
const amber = '#bc7d1e';
const teal = '#118679';
const slate = '#7287a5';

// 各图的图例同时用于读数、数据显示和下钻。
const activity: Series[] = [
  { measure: 'added', label: '新增问题', color: amber, fill: 0.075 },
  { measure: 'resolved', label: '已解决', color: teal },
];
const backlog: Series[] = [
  { measure: 'open', label: '期末未结', color: slate, fill: 0.055 },
  { measure: 'awaitingVerification', label: '等待验证', color: amber, dashed: true },
];
const closures: Series[] = [
  activity[1],
  { measure: 'otherClosed', label: '其他结案', color: amber },
  { measure: 'merged', label: '已合并', color: slate },
];
const duration: Series[] = [
  {
    measure: 'averageResolutionHours',
    label: '平均解决耗时',
    color: teal,
    decimals: 1,
    summaryLabel: '小时平均解决',
  },
];
const allSeries = [...activity, ...backlog, ...closures, ...duration];

/**
 * 在可见来源范围内查看反馈发生量、期末状态和解决耗时。
 */
export function FeedbackOverview() {
  const session = useSession();
  const view = useOverview<Point>('/feedback/overview');
  const { data, report, range, params, sourceDenied } = view;
  const canDrill = allowed(session, 'feedback.view');
  const selected = params.get('metric') as Measure | null;
  const drill = canDrill ? allSeries.find((item) => item.measure === selected) : undefined;
  const onDrill = canDrill ? view.openDrill : undefined;
  const historyGap = data?.points.some((point) => point.awaitingVerification === null);

  return (
    <div className="overview-page feedback-overview">
      {/* 来源、期间及实际截止时刻沿用统一看板工具栏。 */}
      <OverviewToolbar view={view} refreshLabel="刷新反馈统计" />
      {/* 读取失败时仅展示与当前范围相关的反馈。 */}
      {sourceDenied ? (
        <ReadAccessDenied title="当前无权查看该数据源的统计">
          <Button onClick={() => view.chooseSource('')}>查看全部可见数据源</Button>
        </ReadAccessDenied>
      ) : (
        <ReadState loading={report.isPending} error={report.error} retry={report.refetch}>
          {data &&
            (data.total ? (
              <div className="overview-grid">
                <OverviewPanel<Measure>
                  title="新增与解决趋势"
                  points={data.points}
                  total={data.total}
                  series={activity}
                  layout="hero"
                  unit="个"
                  onDrill={onDrill}
                  footer={
                    canDrill && (
                      <Link
                        to={
                          '/feedback' +
                          (range.sourceId ? '?' + queryString({ sourceId: range.sourceId }) : '')
                        }
                      >
                        问题队列 <ExportOutlined />
                      </Link>
                    )
                  }
                />
                <OverviewPanel<Measure>
                  title="积压与等待验证"
                  points={data.points}
                  total={data.total}
                  series={backlog}
                  layout="backlog"
                  unit="个"
                  onDrill={onDrill}
                  note={historyGap ? '历史待验证状态缺失的时段留空' : undefined}
                />
                <OverviewPanel<Measure>
                  title="结案类型"
                  points={data.points}
                  total={data.total}
                  series={closures}
                  layout="ring"
                  unit="个"
                  ringLabel="个期间结案"
                  ringSurface="#faf8f3"
                  onDrill={onDrill}
                />
                <OverviewPanel<Measure>
                  title="解决耗时"
                  points={data.points}
                  total={data.total}
                  series={duration}
                  layout="duration"
                  unit="小时"
                  emptyText="所选期间暂无已解决样本"
                  onDrill={onDrill}
                />
              </div>
            ) : (
              <ReadAccessDenied title="当前没有可统计的数据源" />
            ))}
        </ReadState>
      )}
      {/* 对账与原反馈事项查看权分别核验。 */}
      {drill && data && (
        <FeedbackContributionDrawer
          measure={drill.measure}
          label={drill.label}
          params={params}
          range={range}
          sourceName={
            data.sources.find((source) => source.id === range.sourceId)?.name ?? '全部数据源'
          }
          onClose={view.closeDrill}
          onPage={view.changeDrillPage}
        />
      )}
    </div>
  );
}
