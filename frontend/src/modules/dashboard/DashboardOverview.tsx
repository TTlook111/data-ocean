import { ExportOutlined } from '@ant-design/icons';
import { Button, Empty } from 'antd';
import { Link, useNavigate } from 'react-router';
import { queryString } from '../../shared/api';
import { ReadAccessDenied, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { OverviewToolbar } from '../overview/OverviewControls';
import { OverviewPanel } from '../overview/OverviewPanel';
import type {
  DashboardPoint,
  DashboardRange,
  DashboardReport,
  DashboardSeries,
} from '../overview/types';
import { useOverview } from '../overview/useOverview';
import './dashboard-overview.css';

/**
 * 总览展示各模块的选定指标，字段沿用原概览名称以直接定位贡献记录。
 */
type Measure =
  | 'requests'
  | 'delivered'
  | 'succeeded'
  | 'failed'
  | 'fieldConfirmed'
  | 'tableConfirmed'
  | 'available'
  | 'added'
  | 'resolved';

/**
 * 五张图依照原型位置和职责编号。
 */
type Panel = 'query' | 'collections' | 'governance' | 'knowledge' | 'feedback';

/**
 * 总览复用概览响应，额外标识实际可统计的范围。
 */
type Report = DashboardReport<DashboardPoint<Measure>> & {
  /** 当前有权且有统计来源的图表。 */
  panels: Panel[];
  /** 问数与反馈使用的当前授权来源数量。 */
  authorizedSourceCount: number;
};

/**
 * 原型图表定义和已存在的明细入口。
 */
type Chart = {
  /** 后端图表范围编号。 */
  panel: Panel;
  /** 图表名称。 */
  title: string;
  /** 指标的计数单位。 */
  unit: string;
  /** 图例和读数。 */
  series: DashboardSeries<Measure>[];
  /** 只在需要理解指标时展示的说明。 */
  note?: string;
  /** 归属模块的现有对账入口。 */
  details?: {
    /** 模块概览路由，汇总数在这里展开为明细。 */
    route: string;
    /** 模块聚合统计权限。 */
    overviewPermission: string;
    /** 进入原始业务记录所需的权限。 */
    permission: string;
    /** 图表底部的原业务入口。 */
    workbench: string;
    /** 业务入口显示名称。 */
    label: string;
  };
};

// 原型的线色、填充和短标签对应已确认的五组读数。
const blue = '#4c8bf5';
const teal = '#20a49a';
const slate = '#7b8ca9';
const red = '#d47869';

const charts: Chart[] = [
  {
    panel: 'query',
    title: '问数使用趋势',
    unit: '次',
    note: '按首次提交归组，同一问数的补充与重试只计一次。结果交付与业务正确性分别衡量。',
    series: [
      {
        measure: 'requests',
        label: '问数请求',
        summaryLabel: '次问数请求',
        color: blue,
        fill: 0.075,
      },
      {
        measure: 'delivered',
        label: '已交付结果',
        summaryLabel: '次已交付结果',
        color: teal,
        dashed: true,
      },
    ],
  },
  {
    panel: 'collections',
    title: '采集运行',
    unit: '次',
    series: [
      { measure: 'succeeded', label: '采集成功', summaryLabel: '次成功', color: blue, fill: 0.055 },
      { measure: 'failed', label: '采集失败', summaryLabel: '次失败', color: red, dashed: true },
    ],
    details: {
      route: '/sources-dashboard',
      overviewPermission: 'sources-dashboard.view',
      permission: 'collections.view',
      workbench: '/collections',
      label: '采集记录',
    },
  },
  {
    panel: 'governance',
    title: '治理确认',
    unit: '项',
    note: '统计已确认的表说明与字段。',
    series: [
      {
        measure: 'fieldConfirmed',
        label: '字段',
        summaryLabel: '项字段确认',
        color: teal,
        fill: 0.055,
      },
      {
        measure: 'tableConfirmed',
        label: '表说明',
        summaryLabel: '项表说明确认',
        color: slate,
        dashed: true,
      },
    ],
    details: {
      route: '/governance-dashboard',
      overviewPermission: 'governance-dashboard.view',
      permission: 'governance.view',
      workbench: '/governance',
      label: '治理工作台',
    },
  },
  {
    panel: 'knowledge',
    title: '可用知识',
    unit: '篇',
    note: '各区间取期末可用正式文档数。缺少历史证据的区间留空。',
    series: [
      {
        measure: 'available',
        label: '可用正式文档',
        summaryLabel: '篇期末可用',
        color: blue,
        fill: 0.055,
        step: true,
      },
    ],
    details: {
      route: '/knowledge-dashboard',
      overviewPermission: 'knowledge-dashboard.view',
      permission: 'knowledge.view',
      workbench: '/knowledge',
      label: '知识文档',
    },
  },
  {
    panel: 'feedback',
    title: '反馈处理',
    unit: '个',
    note: '已解决取实际复验通过并按已解决结案的事项。',
    series: [
      { measure: 'added', label: '新增问题', summaryLabel: '个新增问题', color: slate },
      { measure: 'resolved', label: '已解决', summaryLabel: '个已解决', color: teal, fill: 0.055 },
    ],
    details: {
      route: '/feedback-dashboard',
      overviewPermission: 'feedback-dashboard.view',
      permission: 'feedback.view',
      workbench: '/feedback',
      label: '问题队列',
    },
  },
];

/**
 * 总览按统一范围显示五张正式趋势，使用归属模块的对账抽屉。
 */
export function DashboardOverview() {
  const session = useSession();
  const navigate = useNavigate();
  const view = useOverview<DashboardPoint<Measure>, Report>('/dashboard/overview');
  const { data, range, period, report, sourceDenied } = view;
  const total = data?.total;

  /**
   * 进入原模块时保留整页范围和点选区间，抽屉使用总览的冻结时点。
   */
  function openDetails(route: string, measure: Measure, point: DashboardRange) {
    void navigate(
      route +
        '?' +
        queryString({
          ...range,
          period,
          metric: measure,
          asOf: data?.asOf,
          detailFromDate: point.fromDate,
          detailToDate: point.toDate,
        }),
    );
  }

  return (
    <div className="overview-page dashboard-overview-page">
      {/* 五张图共用同一范围和统计截止时间。 */}
      <OverviewToolbar view={view} refreshLabel="刷新总览" />
      {sourceDenied ? (
        <ReadAccessDenied title="当前数据源无法查看">
          <Button onClick={() => view.chooseSource('')}>查看全部数据源</Button>
        </ReadAccessDenied>
      ) : (
        <ReadState error={report.error} loading={report.isPending} retry={report.refetch}>
          {/* 保留主趋势通栏与下方四图的原型结构。 */}
          {data &&
            (total ? (
              <div className="overview-grid dashboard-overview-grid">
                {charts.map(({ panel, title, unit, series, note, details }) => {
                  // 模块聚合与业务明细使用各自的当前权限。
                  const unavailable = !data.panels.includes(panel);
                  const restricted = details && !allowed(session, details.overviewPermission);
                  const drill =
                    details && allowed(session, details.permission) ? details : undefined;
                  // 问题队列的来源字段沿用自身列表查询契约。
                  const sourceFilter = queryString({
                    [panel === 'feedback' ? 'sourceId' : 'source']: range.sourceId,
                  });
                  const partial =
                    !range.sourceId &&
                    (panel === 'query' || panel === 'feedback') &&
                    data.authorizedSourceCount < data.sources.length;
                  if (unavailable) {
                    // 无权限、无范围与有范围内的真实零值分别呈现。
                    return (
                      <section
                        key={panel}
                        className={'overview-panel is-' + (panel === 'query' ? 'hero' : 'trend')}
                        aria-label={title}
                      >
                        <h2>{title}</h2>
                        <div className="dashboard-overview-placeholder">
                          {restricted ? '无统计权限' : '暂无可统计的数据源'}
                        </div>
                      </section>
                    );
                  }
                  return (
                    <OverviewPanel<Measure>
                      key={panel}
                      title={title}
                      unit={unit}
                      layout={panel === 'query' ? 'hero' : 'trend'}
                      points={data.points}
                      total={total}
                      series={series}
                      note={
                        partial
                          ? [note, `当前统计覆盖 ${data.authorizedSourceCount} 个授权数据源。`]
                              .filter(Boolean)
                              .join('')
                          : note
                      }
                      onDrill={
                        drill
                          ? (measure, point) => openDetails(drill.route, measure, point)
                          : undefined
                      }
                      footer={
                        drill && (
                          <Link to={drill.workbench + (sourceFilter ? '?' + sourceFilter : '')}>
                            {drill.label} <ExportOutlined />
                          </Link>
                        )
                      }
                    />
                  );
                })}
              </div>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可统计的数据源" />
            ))}
        </ReadState>
      )}
    </div>
  );
}
