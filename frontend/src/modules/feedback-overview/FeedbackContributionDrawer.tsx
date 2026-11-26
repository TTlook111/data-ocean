import { useQuery } from '@tanstack/react-query';
import { Drawer } from 'antd';
import { Link } from 'react-router';
import { api, ApiError, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, ReadAccessDenied } from '../../shared/Records';
import type { Page } from '../iam/types';
import { valueText } from '../overview/types';
import { metrics } from './types';
import type { Contribution, Measure } from './types';

/**
 * 以概览原有的统计范围和截止时刻对账，并沿用既有事项详情入口。
 */
export function FeedbackContributionDrawer({
  measure,
  label,
  params,
  range,
  sourceName,
  onClose,
  onPage,
}: {
  /** 当前打开的指标字段。 */
  measure: Measure;
  /** 与图例一致的指标名称。 */
  label: string;
  /** 维持明细与概览的浏览位置。 */
  params: URLSearchParams;
  /** 概览选择的数据源、开始日和结束日。 */
  range: { sourceId: string; fromDate: string; toDate: string };
  /** 当前实际统计的数据源名称。 */
  sourceName: string;
  /** 回到同一筛选的图表。 */
  onClose: () => void;
  /** 只切换明细记录的分页。 */
  onPage: (page: number, pageSize: number) => void;
}) {
  const fromDate = params.get('detailFromDate') ?? range.fromDate;
  const toDate = params.get('detailToDate') ?? range.toDate;
  const asOf = params.get('asOf');
  const pageNum = params.get('detailPage');
  const pageSize = params.get('detailPageSize');
  const query = queryString({
    ...range,
    fromDate,
    toDate,
    asOf,
    pageNum,
    pageSize,
    metric: metrics[measure],
  });
  const detail = useQuery({
    queryKey: ['feedback-overview-details', query],
    queryFn: () => api<Page<Contribution>>('/feedback/overview/details?' + query),
    retry: false,
    gcTime: 0,
    refetchInterval: 4500,
    refetchOnWindowFocus: 'always',
  });
  const denied = detail.error instanceof ApiError && [403003, 404001].includes(detail.error.code);
  const stock = measure === 'open' || measure === 'awaitingVerification';
  const resolution = measure === 'resolved' || measure === 'averageResolutionHours';

  return (
    <Drawer title={label + '明细'} open onClose={onClose} size={640}>
      {/* 来源名称和记录均随当前授权重新校验。 */}
      {denied ? (
        <ReadAccessDenied title="当前无权查看这些记录" />
      ) : (
        <>
          <p className="overview-drawer-range">
            {sourceName} · {fromDate === toDate ? toDate : fromDate + ' 至 ' + toDate}
          </p>
          <RecordTable<Contribution>
            rowKey={(row) => `${row.issueId}:${row.issueVersion}`}
            size="small"
            loading={detail.isPending}
            error={detail.error}
            retry={detail.refetch}
            dataSource={detail.data?.items}
            columns={[
              {
                title: '事项',
                key: 'issue',
                width: 100,
                render: (_, row) => (
                  <Link
                    to={'/feedback/' + row.issueId}
                    state={{ feedbackOverviewSearch: '?' + params.toString() }}
                  >
                    #{row.issueId}
                  </Link>
                ),
              },
              { title: '数据源', dataIndex: 'sourceName', width: 156, ellipsis: true },
              {
                title: stock ? '状态记录时间' : measure === 'added' ? '建单时间' : '办理时间',
                dataIndex: 'occurredAt',
                width: 155,
                render: displayTime,
              },
              ...(resolution
                ? [
                    {
                      title: '耗时（小时）',
                      dataIndex: 'resolutionHours',
                      width: 95,
                      render: (value: number | null) => valueText(value, 1),
                    },
                  ]
                : []),
            ]}
            pagination={{
              current: detail.data?.pageNum,
              pageSize: detail.data?.pageSize,
              total: detail.data?.total,
              onChange: onPage,
            }}
          />
        </>
      )}
    </Drawer>
  );
}
