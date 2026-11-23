import { useQuery } from '@tanstack/react-query';
import { Drawer } from 'antd';
import { Link } from 'react-router';
import { api, ApiError, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, ReadAccessDenied } from '../../shared/Records';
import type { Page } from '../iam/types';
import type { useOverview } from '../overview/useOverview';
import { valueText } from '../overview/types';
import type { DashboardRange } from '../overview/types';
import { DatasourceStatus } from './Presentation';

/**
 * 后台同一统计区间内贡献了某项读数的原始事实。
 */
type Contribution = {
  /** 采集任务 UUID 或结构变化编号。 */
  id: string;
  /** 原记录所属数据源。 */
  sourceId: string;
  /** 当前来源名称。 */
  sourceName: string;
  /** 采集任务对外编号，结构变化为空。 */
  taskNo: string | null;
  /** 实际任务终态或待核实快照分类。 */
  kind: string;
  /** 任务结束时间或待核实变化的发现时间。 */
  occurredAt: string;
  /** 任务实际执行秒数。 */
  durationSeconds: number | null;
};

/**
 * 对账使用结束时间或历史状态，办理详情链接继续复用原采集入口。
 */
export function SourceOverviewDrawer({
  view,
  metric,
  title,
}: {
  /** 原概览筛选和下钻分页。 */
  view: ReturnType<typeof useOverview<DashboardRange>>;
  /** 服务端识别的对账分类。 */
  metric: string;
  /** 图例中的指标标题。 */
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
    queryKey: ['source-overview-details', query],
    queryFn: () => api<Page<Contribution>>('/datasources/overview/details?' + query),
    retry: false,
    gcTime: 0,
    refetchInterval: 4500,
    refetchOnWindowFocus: 'always',
  });
  const sourceName =
    data?.sources.find((source) => source.id === range.sourceId)?.name ?? '全部数据源';
  const pending = metric === 'PENDING';
  const denied = rows.error instanceof ApiError && [403003, 404001].includes(rows.error.code);

  return (
    <Drawer title={title + '明细'} open onClose={view.closeDrill} size={680}>
      {denied ? (
        <ReadAccessDenied title="当前无权查看这些记录" />
      ) : (
        <>
          {/* 原期间绑定在统计对账，原记录可从当前标签原位回看。 */}
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
                title: pending ? '结构变化' : '采集任务',
                key: 'record',
                width: 168,
                render: (_, row) => {
                  // 详情按权打开原任务或结构变化，返回由浏览历史保留原统计页。
                  const path = pending ? '/collections/changes' : '/collections';
                  const search = queryString({
                    source: row.sourceId,
                    ...(pending ? { change: row.id } : { task: row.id }),
                  });
                  return <Link to={path + '?' + search}>{row.taskNo ?? '#' + row.id}</Link>;
                },
              },
              { title: '数据源', dataIndex: 'sourceName', width: 140, ellipsis: true },
              {
                title: '状态',
                dataIndex: 'kind',
                width: 90,
                render: (value: string) => <DatasourceStatus value={value} />,
              },
              {
                title: pending ? '发现时间' : '结束时间',
                dataIndex: 'occurredAt',
                width: 156,
                render: displayTime,
              },
              ...(!pending
                ? [
                    {
                      title: '耗时（秒）',
                      dataIndex: 'durationSeconds',
                      width: 88,
                      render: (value: number | null) => valueText(value, 1),
                    },
                  ]
                : []),
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
