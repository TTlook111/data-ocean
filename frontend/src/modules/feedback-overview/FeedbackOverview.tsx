import { ExportOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, ConfigProvider, Segmented, Select } from 'antd';
import type { ThemeConfig } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, ApiError, queryString } from '../../shared/api';
import { LoadingIndicator } from '../../shared/LoadingIndicator';
import { QueryDateRange } from '../../shared/QueryDateRange';
import { displayTime, ReadAccessDenied, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { FeedbackContributionDrawer } from './FeedbackContributionDrawer';
import { FeedbackMetricPanel } from './FeedbackMetricPanel';
import type { Measure, Overview, Point, Series } from './types';
import './feedback-overview.css';

// 原型用暖橙标新增与等待，青绿表示实际解决，蓝灰承担期末存量。
const amber = '#bc7d1e';
const teal = '#118679';
const slate = '#7287a5';

/**
 * 看板日期使用原型的中性轨道和白色选中项。
 */
const periodTheme: ThemeConfig = {
  components: {
    Segmented: {
      /** 日期组轨道。 */
      trackBg: '#f3f5f8',
      /** 当前统计期间。 */
      itemSelectedBg: '#ffffff',
      /** 当前分段的文字。 */
      itemSelectedColor: '#1f2937',
    },
  },
};

// 各图的图例同时用于读数、数据显示和下钻。
const activity: Series[] = [
  { measure: 'added', label: '新增问题', color: amber, fill: true },
  { measure: 'resolved', label: '已解决', color: teal },
];
const backlog: Series[] = [
  { measure: 'open', label: '期末未结', color: slate, fill: true },
  { measure: 'awaitingVerification', label: '等待验证', color: amber, dashed: true },
];
const closures: Series[] = [
  activity[1],
  { measure: 'otherClosed', label: '其他结案', color: amber },
  { measure: 'merged', label: '已合并', color: slate },
];
const duration: Series[] = [
  { measure: 'averageResolutionHours', label: '平均解决耗时', color: teal },
];
const allSeries = [...activity, ...backlog, ...closures, ...duration];
const grainLabels = { DAY: '按天', WEEK: '按周', MONTH: '按月' };

/**
 * 在可见来源范围内查看反馈发生量、期末状态和解决耗时。
 */
export function FeedbackOverview() {
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const [refreshing, setRefreshing] = useState(false);
  const sourceId = params.get('sourceId') ?? '';
  const period = params.get('period') ?? '30';
  const to = dayjs(
    new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()),
  );
  const fromDate =
    params.get('fromDate') ?? to.subtract(period === '7' ? 6 : 29, 'day').format('YYYY-MM-DD');
  const toDate = params.get('toDate') ?? to.format('YYYY-MM-DD');
  const range = { sourceId, fromDate, toDate };
  const query = queryString(range);
  const canDrill = allowed(session, 'feedback.view');
  const report = useQuery({
    queryKey: ['feedback-overview', session.userId, query],
    queryFn: () => api<Overview>('/feedback/overview?' + query),
    retry: false,
    gcTime: 0,
    refetchInterval: 4500,
    refetchOnWindowFocus: 'always',
    refetchOnMount: 'always',
  });
  const data = report.isError ? undefined : report.data;
  const selected = params.get('metric') as Measure | null;
  const drill =
    canDrill && selected ? allSeries.find((item) => item.measure === selected) : undefined;
  const sourceDenied =
    report.error instanceof ApiError && report.error.code === 404001 && Boolean(sourceId);

  /**
   * 手动刷新有就地反馈，后台核权和轮询保持安静。
   */
  async function refreshReport() {
    setRefreshing(true);
    try {
      // 同一条件已有请求时复用这次读取。
      await report.refetch({ cancelRefetch: false });
    } finally {
      // 请求完成后恢复操作入口。
      setRefreshing(false);
    }
  }

  /**
   * 应用统计条件并退出旧下钻，侧栏保留本页已应用条件。
   */
  function apply(source: string, selection: string, from?: string, until?: string) {
    setParams(queryString({ sourceId: source, period: selection, fromDate: from, toDate: until }));
  }

  /**
   * 下钻固定所选来源、期间及当前图表的数据截止时间。
   */
  function openDrill(measure: Measure, point: Point) {
    if (!data || !canDrill) return;
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
   * 关闭对账时保留概览原筛选。
   */
  function closeDrill() {
    const search = new URLSearchParams(params);
    for (const key of [
      'metric',
      'detailFromDate',
      'detailToDate',
      'asOf',
      'detailPage',
      'detailPageSize',
    ]) {
      // 只清理属于明细的参数。
      search.delete(key);
    }
    setParams(search);
  }

  return (
    <div className="feedback-overview">
      {/* 沿用原型的左侧来源和右侧期间，数据截至时间紧邻当前口径。 */}
      <section className="feedback-overview-toolbar" aria-label="统计范围">
        <div className="feedback-overview-filter">
          <Select
            aria-label="统计数据源"
            value={sourceId}
            disabled={!data?.sources.length}
            options={[
              { value: '', label: '全部数据源' },
              ...(data?.sources ?? []).map((item) => ({ value: item.id, label: item.name })),
            ]}
            onChange={(source) =>
              apply(
                source,
                period,
                params.get('fromDate') ?? undefined,
                params.get('toDate') ?? undefined,
              )
            }
          />
          {data?.total && (
            <span className="feedback-overview-as-of">
              {grainLabels[data.grain]} · 截至 {displayTime(data.asOf)}
            </span>
          )}
        </div>
        <div className="feedback-overview-filter is-period">
          {/* 期间选中项的样式由看板局部主题提供。 */}
          <ConfigProvider theme={periodTheme}>
            <Segmented
              aria-label="统计时间范围"
              value={period}
              options={[
                { label: '近7天', value: '7' },
                { label: '近30天', value: '30' },
                { label: '自定义', value: 'custom' },
              ]}
              onChange={(value) => {
                // 从快捷范围切换到自定义时保留原范围供编辑。
                if (value === 'custom') apply(sourceId, value, fromDate, toDate);
                else apply(sourceId, value);
              }}
            />
          </ConfigProvider>
          <QueryDateRange
            label="统计日期"
            value={[dayjs(fromDate), dayjs(toDate)]}
            allowClear={false}
            maxDate={to}
            onChange={(dates) => {
              // 日期两端由 Ant Design 完成输入与范围校验。
              if (dates?.[0] && dates[1]) {
                apply(
                  sourceId,
                  'custom',
                  dates[0].format('YYYY-MM-DD'),
                  dates[1].format('YYYY-MM-DD'),
                );
              }
            }}
          />
          <Button
            type="text"
            aria-label="刷新反馈统计"
            title="刷新反馈统计"
            icon={refreshing ? <LoadingIndicator /> : <ReloadOutlined />}
            disabled={report.isPending || refreshing}
            onClick={() => void refreshReport()}
          />
        </div>
      </section>

      {/* 按本次权限和读取结果显示统计，拒绝后始终隐藏旧值。 */}
      {sourceDenied ? (
        <ReadAccessDenied title="当前无权查看该数据源的统计">
          <Button
            onClick={() =>
              apply(
                '',
                period,
                params.get('fromDate') ?? undefined,
                params.get('toDate') ?? undefined,
              )
            }
          >
            查看全部可见数据源
          </Button>
        </ReadAccessDenied>
      ) : (
        <ReadState loading={report.isPending} error={report.error} retry={report.refetch}>
          {data &&
            (data.total ? (
              <div className="feedback-overview-grid">
                <FeedbackMetricPanel
                  title="新增与解决趋势"
                  points={data.points}
                  total={data.total}
                  series={activity}
                  layout="hero"
                  onDrill={canDrill ? openDrill : undefined}
                  footer={
                    canDrill && (
                      <Link to={'/feedback' + (sourceId ? '?' + queryString({ sourceId }) : '')}>
                        问题队列 <ExportOutlined />
                      </Link>
                    )
                  }
                />
                <FeedbackMetricPanel
                  title="积压与等待验证"
                  points={data.points}
                  total={data.total}
                  series={backlog}
                  layout="backlog"
                  onDrill={canDrill ? openDrill : undefined}
                />
                <FeedbackMetricPanel
                  title="结案类型"
                  points={data.points}
                  total={data.total}
                  series={closures}
                  layout="ring"
                  onDrill={canDrill ? openDrill : undefined}
                />
                <FeedbackMetricPanel
                  title="解决耗时"
                  points={data.points}
                  total={data.total}
                  series={duration}
                  layout="duration"
                  onDrill={canDrill ? openDrill : undefined}
                />
              </div>
            ) : (
              <ReadAccessDenied title="当前没有可统计的数据源" />
            ))}
        </ReadState>
      )}

      {/* 明细依赖同一来源权限、期间和读取截止点。 */}
      {drill && data && (
        <FeedbackContributionDrawer
          measure={drill.measure}
          label={drill.label}
          params={params}
          range={range}
          sourceName={data.sources.find((source) => source.id === sourceId)?.name ?? '全部数据源'}
          onClose={closeDrill}
          onPage={(page, pageSize) => {
            // 翻页保留原概览和贡献集合。
            const search = new URLSearchParams(params);
            search.set('detailPage', String(page));
            search.set('detailPageSize', String(pageSize));
            setParams(search);
          }}
        />
      )}
    </div>
  );
}
