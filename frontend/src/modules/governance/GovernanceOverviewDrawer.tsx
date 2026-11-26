import { useQuery } from '@tanstack/react-query';
import { Drawer } from 'antd';
import { Link } from 'react-router';
import { api, ApiError, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, ReadAccessDenied } from '../../shared/Records';
import type { Page } from '../iam/types';
import type { DashboardRange } from '../overview/types';
import type { useOverview } from '../overview/useOverview';

/**
 * 一条可定位的统计事实，不在看板展示受限制的治理正文。
 */
type Contribution = {
  /** 统计事实编号。 */
  id: string;
  /** 归属来源编号。 */
  sourceId: string;
  /** 归属来源展示名。 */
  sourceName: string;
  /** 表或视图稳定编号。 */
  objectId: string;
  /** 字段编号、候选编号或原对象编号。 */
  targetId: string;
  /** 事实分类。 */
  kind: string;
  /** 固定记录的结构名或业务名称。 */
  label: string;
  /** 确认、待复核起点或快照结束时刻。 */
  occurredAt: string;
};

// 对账标签与治理工作台的进度命名一致。
const kindNames: Record<string, string> = {
  TABLE: '表说明',
  COLUMN: '字段',
  RELATION: '关联',
  METRIC: '指标',
  COMPLETE: '已治理',
  IN_PROGRESS: '治理中',
  UNSTARTED: '待治理',
};

/**
 * 在同一统计期间和截止时刻核对治理事实。
 */
export function GovernanceOverviewDrawer({
  view,
  metric,
  title,
}: {
  /** 原看板过滤和下钻分页。 */
  view: ReturnType<typeof useOverview<DashboardRange>>;
  /** 后端接收的事实分类。 */
  metric: string;
  /** 当前图例显示名称。 */
  title: string;
}) {
  const { params, range, data } = view;
  const fromDate = params.get('detailFromDate') ?? range.fromDate;
  const toDate = params.get('detailToDate') ?? range.toDate;
  const query = queryString({
    sourceId: range.sourceId,
    fromDate,
    toDate,
    metric,
    asOf: params.get('asOf'),
    pageNum: params.get('detailPage'),
    pageSize: params.get('detailPageSize'),
  });
  const rows = useQuery({
    queryKey: ['governance-overview-details', query],
    queryFn: () => api<Page<Contribution>>('/governance/overview/details?' + query),
    retry: false,
    gcTime: 0,
    refetchInterval: 4500,
    refetchOnWindowFocus: 'always',
  });
  const denied = rows.error instanceof ApiError && [403003, 404001].includes(rows.error.code);
  const sourceName =
    data?.sources.find((source) => source.id === range.sourceId)?.name ?? '全部数据源';
  const timestampTitle =
    metric === 'RECHECK'
      ? '进入待核对'
      : ['COMPLETE', 'IN_PROGRESS', 'UNSTARTED'].includes(metric)
        ? '期末时刻'
        : '确认时间';

  return (
    <Drawer title={title + '明细'} open onClose={view.closeDrill} size={680}>
      {denied ? (
        <ReadAccessDenied title="当前无权查看这些记录" />
      ) : (
        <>
          {/* 来源和期间延续原图表的过滤信息。 */}
          <p className="overview-drawer-range">
            {sourceName} · {fromDate === toDate ? toDate : fromDate + ' 至 ' + toDate}
          </p>
          <RecordTable<Contribution>
            rowKey="id"
            size="small"
            loading={rows.isPending}
            error={rows.error}
            retry={rows.refetch}
            dataSource={rows.data?.items}
            columns={[
              {
                title: '对象或条目',
                key: 'record',
                width: 200,
                render: (_, row) => {
                  // 深链接使用原稳定标识定位详情页签与候选。
                  const focus =
                    row.kind === 'TABLE'
                      ? { section: 'table' }
                      : row.kind === 'COLUMN'
                        ? { section: 'columns', entry: row.targetId }
                        : row.kind === 'RELATION'
                          ? { section: 'relations', candidate: row.targetId }
                          : row.kind === 'METRIC'
                            ? { section: 'metrics', candidate: row.targetId }
                            : {};
                  const search = queryString({ source: row.sourceId, ...focus });
                  return (
                    <Link
                      to={'/governance/' + row.objectId + '?' + search}
                      state={{ governanceOverviewSearch: '?' + params.toString() }}
                    >
                      {row.label || '#' + row.targetId}
                    </Link>
                  );
                },
              },
              { title: '数据源', dataIndex: 'sourceName', width: 140, ellipsis: true },
              {
                title: '分类',
                dataIndex: 'kind',
                width: 88,
                render: (value: string) => kindNames[value] ?? value,
              },
              { title: timestampTitle, dataIndex: 'occurredAt', width: 150, render: displayTime },
            ]}
            pagination={{
              current: rows.data?.pageNum,
              pageSize: rows.data?.pageSize,
              total: rows.data?.total,
              onChange: view.changeDrillPage,
            }}
          />
        </>
      )}
    </Drawer>
  );
}
