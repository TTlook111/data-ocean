import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useSearchParams } from 'react-router';
import { api, ApiError, queryString } from '../../shared/api';
import { useSession } from '../../shared/session';
import type { DashboardRange, DashboardReport } from './types';

// 下钻条件与主图时间范围独立，关闭详情只删除这些条件。
const drillKeys = [
  'metric',
  'detailFromDate',
  'detailToDate',
  'asOf',
  'detailPage',
  'detailPageSize',
];

/**
 * 两类看板共享筛选地址、重新核权和下钻时间快照。
 */
export function useOverview<P extends DashboardRange>(endpoint: string) {
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const sourceId = params.get('sourceId') ?? '';
  const period = params.get('period') ?? '30';
  const today = dayjs(
    new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()),
  );
  const fromDate =
    params.get('fromDate') ?? today.subtract(period === '7' ? 6 : 29, 'day').format('YYYY-MM-DD');
  const toDate = params.get('toDate') ?? today.format('YYYY-MM-DD');
  const range = { sourceId, fromDate, toDate };
  const query = queryString(range);
  const report = useQuery({
    queryKey: ['overview', endpoint, session.userId, query],
    queryFn: () => api<DashboardReport<P>>(endpoint + '?' + query),
    retry: false,
    gcTime: 0,
    refetchInterval: 4500,
    refetchOnWindowFocus: 'always',
    refetchOnMount: 'always',
  });
  const data = report.isError ? undefined : report.data;
  const sourceDenied =
    report.error instanceof ApiError && report.error.code === 404001 && Boolean(sourceId);

  /**
   * 应用来源或时间条件，同时退出原有下钻。
   */
  function apply(source: string, selection: string, from?: string, until?: string) {
    setParams(queryString({ sourceId: source, period: selection, fromDate: from, toDate: until }));
  }

  /**
   * 切换来源时保留原选定的期间。
   */
  function chooseSource(source: string) {
    apply(source, period, params.get('fromDate') ?? undefined, params.get('toDate') ?? undefined);
  }

  /**
   * 由点击的读数固定来源、期间和截止时刻。
   */
  function openDrill(measure: string, point: DashboardRange) {
    if (!data) return;
    const search = new URLSearchParams(params);
    search.set('metric', measure);
    search.set('detailFromDate', point.fromDate);
    search.set('detailToDate', point.toDate);
    search.set('asOf', data.asOf);
    search.delete('detailPage');
    search.delete('detailPageSize');
    setParams(search);
  }

  /**
   * 返回同一概览及其筛选条件。
   */
  function closeDrill() {
    const search = new URLSearchParams(params);
    for (const key of drillKeys) {
      // 逐项清除只属于明细的参数。
      search.delete(key);
    }
    setParams(search);
  }

  /**
   * 对账分页沿用已固定的事实集合。
   */
  function changeDrillPage(page: number, size: number) {
    const search = new URLSearchParams(params);
    search.set('detailPage', String(page));
    search.set('detailPageSize', String(size));
    setParams(search);
  }

  return {
    data,
    report,
    range,
    period,
    today,
    params,
    sourceDenied,
    apply,
    chooseSource,
    openDrill,
    closeDrill,
    changeDrillPage,
  };
}
