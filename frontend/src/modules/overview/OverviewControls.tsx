import { ReloadOutlined } from '@ant-design/icons';
import { Button, ConfigProvider, Segmented, Select } from 'antd';
import type { ThemeConfig } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import { LoadingIndicator } from '../../shared/LoadingIndicator';
import { QueryDateRange } from '../../shared/QueryDateRange';
import { displayTime } from '../../shared/Records';
import type { DashboardRange, DashboardReport, OverviewReport } from './types';
import type { useOverview } from './useOverview';

/**
 * 看板分段控件使用原型的中性轨道和白色选中项。
 */
const periodTheme: ThemeConfig = {
  components: {
    Segmented: {
      /** 日期组轨道。 */
      trackBg: '#f3f5f8',
      /** 当前统计期间。 */
      itemSelectedBg: '#ffffff',
      /** 当前分段文字。 */
      itemSelectedColor: '#1f2937',
    },
  },
};

const grainLabels = { DAY: '按天', WEEK: '按周', MONTH: '按月' };

/**
 * 来源和期间工具栏对两类看板保持统一尺度。
 */
export function OverviewToolbar<
  R extends OverviewReport<DashboardRange> &
    Partial<Pick<DashboardReport<DashboardRange>, 'sources'>>,
>({
  view,
  refreshLabel,
  showContext = true,
  datePicker,
}: {
  /** 已应用的来源、期间和读取结果。 */
  view: ReturnType<typeof useOverview<DashboardRange, R>>;
  /** 按模块区分手动刷新动作。 */
  refreshLabel: string;
  /** 按来源统计的看板展示来源和统计时效。 */
  showContext?: boolean;
  /** 在报表重新读取时由页面保持日历是否打开。 */
  datePicker?: { open: boolean; onOpenChange: (open: boolean) => void };
}) {
  const { data, report, range, period, today, apply, chooseSource } = view;
  const [refreshing, setRefreshing] = useState(false);

  /**
   * 手动刷新给出局部等待反馈；后台核权过程保持安静。
   */
  async function refreshReport() {
    setRefreshing(true);
    try {
      // 已有读请求时沿用该请求，避免重复打断。
      await report.refetch({ cancelRefetch: false });
    } finally {
      // 读完后恢复刷新入口。
      setRefreshing(false);
    }
  }

  return (
    <section className="overview-toolbar" aria-label="统计范围">
      {/* 来源和统计时效保持在同一组。 */}
      {showContext && (
        <div className="overview-filter">
          <Select
            aria-label="统计数据源"
            value={range.sourceId}
            disabled={!data?.sources?.length}
            options={[
              { value: '', label: '全部数据源' },
              ...(data?.sources ?? []).map((source) => ({ value: source.id, label: source.name })),
            ]}
            onChange={chooseSource}
          />
          {data?.total && (
            <span className="overview-as-of">
              {grainLabels[data.grain]} · 截至 {displayTime(data.asOf)}
            </span>
          )}
        </div>
      )}
      {/* 快捷范围和自定义日期共享实际应用条件。 */}
      <div className="overview-filter">
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
              // 打开自定义时保留当前区间供编辑。
              if (value === 'custom') {
                // 调用看板在选择自定义时直接打开起止日期。
                if (datePicker) datePicker.onOpenChange(true);
                else apply(range.sourceId, value, range.fromDate, range.toDate);
              } else {
                datePicker?.onOpenChange(false);
                apply(range.sourceId, value);
              }
            }}
          />
        </ConfigProvider>
        <QueryDateRange
          label="统计日期"
          value={[dayjs(range.fromDate), dayjs(range.toDate)]}
          allowClear={false}
          maxDate={today}
          open={datePicker?.open}
          onOpenChange={datePicker?.onOpenChange}
          onChange={(dates) => {
            // 成对选择由日期控件校验后应用。
            if (dates?.[0] && dates[1]) {
              datePicker?.onOpenChange(false);
              apply(
                range.sourceId,
                'custom',
                dates[0].format('YYYY-MM-DD'),
                dates[1].format('YYYY-MM-DD'),
              );
            }
          }}
        />
        <Button
          type="text"
          aria-label={refreshLabel}
          title={refreshLabel}
          icon={refreshing ? <LoadingIndicator /> : <ReloadOutlined />}
          disabled={report.isPending || refreshing}
          onClick={() => void refreshReport()}
        />
      </div>
    </section>
  );
}
