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
import { KnowledgeOverviewDrawer } from './KnowledgeOverviewDrawer';
import './knowledge-overview.css';

/** 每个显示字段对应一个独立的台账指标。 */
const metrics = {
  available: 'AVAILABLE',
  published: 'PUBLISHED',
  failed: 'FAILED',
  errors: 'EXECUTION_ERROR',
  updating: 'UPDATING',
  paused: 'PAUSED',
  forbidden: 'FORBIDDEN',
} as const;

/** 仅使用知识模块统计返回的数值。 */
type Measure = keyof typeof metrics;

/** 知识看板共享带日期的数值和显示系列。 */
type Series = DashboardSeries<Measure>;

// 原型使用靛蓝作为知识和发布主色，维护分类使用独立颜色。
const indigo = '#5366ae';
const amber = '#ac771c';
const red = '#b35558';
const available: Series[] = [
  {
    measure: 'available',
    label: '可用正式文档',
    summaryLabel: '篇期末可用',
    color: indigo,
    fill: 0.075,
    step: true,
  },
];
const publication: Series[] = [
  {
    measure: 'published',
    label: '成功生效批次',
    summaryLabel: '批成功生效',
    color: indigo,
    fill: 0.055,
  },
];
const validation: Series[] = [
  { measure: 'failed', label: '未通过', color: amber },
  { measure: 'errors', label: '执行错误', color: red, dashed: true },
];
const attention: Series[] = [
  { measure: 'updating', label: '待更新', color: indigo },
  { measure: 'paused', label: '已暂停', color: amber },
  { measure: 'forbidden', label: '禁止使用', color: red },
];
const all = [...available, ...publication, ...validation, ...attention];

/**
 * 知识概览展示实际正式版本、验证和独立维护状态。
 */
export function KnowledgeOverview() {
  const session = useSession();
  const view = useOverview<DashboardPoint<Measure>>('/knowledge/overview');
  const { data, report, range, params, sourceDenied } = view;
  const documents = allowed(session, 'knowledge.view');
  const releases = allowed(session, 'releases.view');
  const documentDrill = documents ? view.openDrill : undefined;
  const releaseDrill = releases ? view.openDrill : undefined;
  const selected = all.find((item) => item.measure === params.get('metric'));
  const activity = selected && ['published', 'failed', 'errors'].includes(selected.measure);
  const drill = selected && (activity ? releases : documents) ? selected : undefined;
  const sourceSearch = range.sourceId ? '?' + queryString({ source: range.sourceId }) : '';
  const incomplete = data?.points.some((point) => point.available === null);

  return (
    <div className="overview-page knowledge-overview">
      {/* 保留统一看板工具栏和各自来源选择。 */}
      <OverviewToolbar view={view} refreshLabel="刷新知识统计" />
      {sourceDenied ? (
        <ReadAccessDenied title="当前数据源无法查看">
          <Button onClick={() => view.chooseSource('')}>查看全部数据源</Button>
        </ReadAccessDenied>
      ) : (
        <ReadState error={report.error} loading={report.isPending} retry={report.refetch}>
          {data &&
            (data.total ? (
              <div className="overview-grid">
                {/* 正式可用存量采用通栏阶梯趋势。 */}
                <OverviewPanel<Measure>
                  title="正式知识可用变化"
                  unit="篇"
                  layout="hero"
                  points={data.points}
                  total={data.total}
                  series={available}
                  note={incomplete ? '部分历史期末缺少来源或模型状态证据，曲线留空' : undefined}
                  onDrill={documentDrill}
                  footer={
                    documents && (
                      <Link to={'/knowledge' + sourceSearch}>
                        知识文档 <ExportOutlined />
                      </Link>
                    )
                  }
                />
                {/* 三列分别承接批次、逐题验证与维护。 */}
                <OverviewPanel<Measure>
                  title="发布动态"
                  unit="批"
                  layout="publishing"
                  points={data.points}
                  total={data.total}
                  series={publication}
                  onDrill={releaseDrill}
                  footer={
                    releases && (
                      <Link to={'/releases' + sourceSearch}>
                        发布任务 <ExportOutlined />
                      </Link>
                    )
                  }
                />
                <OverviewPanel<Measure>
                  title="验证异常"
                  unit="题次"
                  layout="validation"
                  points={data.points}
                  total={data.total}
                  series={validation}
                  onDrill={releaseDrill}
                  footer={
                    allowed(session, 'test-cases.view') && (
                      <Link to={'/test-cases' + sourceSearch}>
                        标准题单 <ExportOutlined />
                      </Link>
                    )
                  }
                />
                <OverviewPanel<Measure>
                  title="期末维护关注"
                  unit="篇"
                  layout="dots"
                  points={data.points}
                  total={data.total}
                  series={attention}
                  note="同一文档的多个维护状态分别计数"
                  onDrill={documentDrill}
                />
              </div>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可统计的数据源" />
            ))}
        </ReadState>
      )}
      {/* 跳转原业务前仍核对当前页面权限。 */}
      {drill && data && (
        <KnowledgeOverviewDrawer title={drill.label} metric={metrics[drill.measure]} view={view} />
      )}
    </div>
  );
}
