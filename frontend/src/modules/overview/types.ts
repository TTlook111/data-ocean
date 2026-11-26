/**
 * 看板数据区间使用包含起止日期的业务日。
 */
export type DashboardRange = {
  /** 区间首日。 */
  fromDate: string;
  /** 区间末日。 */
  toDate: string;
};

/**
 * 各模块读数使用限定字段，缺少样本时保留空值。
 */
export type DashboardPoint<M extends string> = DashboardRange & Record<M, number | null>;

/**
 * 两类看板的筛选和读取状态使用同一份接口元数据。
 */
export type OverviewReport<P extends DashboardRange> = {
  /** 统计快照截止时刻。 */
  asOf: string;
  /** 日、自然周或自然月。 */
  grain: 'DAY' | 'WEEK' | 'MONTH';
  /** 按业务日期排列的分组。 */
  points: P[];
  /** 整个期间的统计，无可统计来源时为空。 */
  total: P | null;
};

/**
 * 按来源统计的看板另附当前可见的来源选项。
 */
export type DashboardReport<P extends DashboardRange> = OverviewReport<P> & {
  /** 当前可统计的来源。 */
  sources: { id: string; name: string }[];
};

/**
 * 各模块共享指标图例、读数格式和系列线型。
 */
export type DashboardSeries<M extends string> = {
  /** 图形使用的统计字段。 */
  measure: M;
  /** 图例、数值表及读屏显示名称。 */
  label: string;
  /** 标题读数专用的短单位和标签。 */
  summaryLabel?: string;
  /** 与原型一致的系列颜色。 */
  color: string;
  /** 堆叠柱最高用量的同色系强调色。 */
  peakColor?: string;
  /** 主线下面的面积透明度。 */
  fill?: number;
  /** 辅助线以虚线区分。 */
  dashed?: boolean;
  /** 期末状态之间是否以阶梯连接。 */
  step?: boolean;
  /** 显示的最大小数位，计数默认用整数。 */
  decimals?: number;
};

/**
 * 共用读数格式，空样本保留明确的空缺。
 */
export function valueText(value: number | null, decimals = 0) {
  return value === null ? '—' : value.toLocaleString('zh-CN', { maximumFractionDigits: decimals });
}

/**
 * 提示和数据表保持完整的业务日期。
 */
export function periodLabel(point: DashboardRange) {
  return point.fromDate === point.toDate ? point.toDate : `${point.fromDate} 至 ${point.toDate}`;
}
