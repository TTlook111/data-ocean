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
 * 可追溯到原文档修订、发布任务或验证轮次的聚合贡献。
 */
type Contribution = {
  /** 该贡献记录的稳定身份。 */
  id: string;
  /** 归属来源。 */
  sourceId: string;
  /** 来源展示名。 */
  sourceName: string;
  /** 原文档。 */
  documentId: string | null;
  /** 期末实际使用的冻结文档修订。 */
  revisionId: string | null;
  /** 原发布任务。 */
  taskId: string | null;
  /** 原候选验证轮次。 */
  runId: string | null;
  /** 冻结的文档或任务标题。 */
  title: string;
  /** 审计事实类型。 */
  kind: string;
  /** 期末、批次核实时刻或运行发起时刻。 */
  occurredAt: string;
};

// 分别展示发布、验证和维护状态原有含义。
const kinds: Record<string, string> = {
  AVAILABLE: '正式可用',
  UPDATING: '待更新',
  PAUSED: '已暂停',
  FORBIDDEN: '禁止使用',
  PUBLISHED: '成功生效',
  FAILED: '未通过',
  EXECUTION_ERROR: '执行错误',
};

/**
 * 看板下钻固定统计期间与数据截止时间。
 */
export function KnowledgeOverviewDrawer({
  title,
  metric,
  view,
}: {
  /** 当前读数名称。 */
  title: string;
  /** 后端核对的指标编码。 */
  metric: string;
  /** 原看板及对账分页范围。 */
  view: ReturnType<typeof useOverview<DashboardRange>>;
}) {
  const { data, params, range } = view;
  const fromDate = params.get('detailFromDate') ?? range.fromDate;
  const toDate = params.get('detailToDate') ?? range.toDate;
  const query = queryString({
    sourceId: range.sourceId,
    fromDate,
    toDate,
    asOf: params.get('asOf'),
    metric,
    pageNum: params.get('detailPage'),
    pageSize: params.get('detailPageSize'),
  });
  const details = useQuery({
    queryKey: ['knowledge-overview-details', query],
    queryFn: () => api<Page<Contribution>>('/knowledge/overview/details?' + query),
    retry: false,
    gcTime: 0,
    refetchInterval: 4500,
    refetchOnWindowFocus: 'always',
  });
  const denied = details.error instanceof ApiError && [403003, 404001].includes(details.error.code);
  const sourceName =
    data?.sources.find((source) => source.id === range.sourceId)?.name ?? '全部数据源';
  const timeLabel =
    metric === 'PUBLISHED'
      ? '核实时间'
      : ['FAILED', 'EXECUTION_ERROR'].includes(metric)
        ? '运行发起时间'
        : '期末时刻';

  return (
    <Drawer title={title + '明细'} size={680} open onClose={view.closeDrill}>
      {denied ? (
        <ReadAccessDenied title="当前无权查看这些记录" />
      ) : (
        <>
          {/* 说明本次对账实际限定的来源和日期。 */}
          <p className="overview-drawer-range">
            {sourceName} · {fromDate === toDate ? toDate : fromDate + ' 至 ' + toDate}
          </p>
          <RecordTable<Contribution>
            rowKey="id"
            size="small"
            dataSource={details.data?.items}
            loading={details.isPending}
            error={details.error}
            retry={details.refetch}
            columns={[
              {
                title: '原记录',
                key: 'title',
                width: 240,
                render: (_, row) => {
                  // 从期末打开原冻结修订，从活动打开原任务或指定验证轮次。
                  const path = row.documentId
                    ? '/knowledge/' +
                      row.documentId +
                      '?' +
                      queryString({ source: row.sourceId, revisionId: row.revisionId })
                    : '/releases/' +
                      row.taskId +
                      '?' +
                      queryString({ source: row.sourceId, runId: row.runId });
                  return (
                    <Link to={path} state={{ knowledgeOverviewSearch: '?' + params.toString() }}>
                      {row.title}
                    </Link>
                  );
                },
              },
              { title: '数据源', dataIndex: 'sourceName', width: 130, ellipsis: true },
              {
                title: '类别',
                dataIndex: 'kind',
                width: 90,
                render: (value: string) => kinds[value] ?? value,
              },
              { title: timeLabel, dataIndex: 'occurredAt', width: 150, render: displayTime },
            ]}
            pagination={{
              current: details.data?.pageNum,
              pageSize: details.data?.pageSize,
              total: details.data?.total,
              onChange: view.changeDrillPage,
            }}
          />
        </>
      )}
    </Drawer>
  );
}
