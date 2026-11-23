/**
 * 统计区间按业务日期展示，发生量与期末存量分别读取。
 */
export type Point = {
  /** 区间首日，包含在内。 */
  fromDate: string;
  /** 区间末日，包含在内。 */
  toDate: string;
  /** 区间内新建事项数。 */
  added: number;
  /** 区间内实际验收解决的结案次数。 */
  resolved: number;
  /** 区间内其他结案次数。 */
  otherClosed: number;
  /** 区间内合并次数。 */
  merged: number;
  /** 截至区间末的未结事项数。 */
  open: number;
  /** 截至区间末的待验证数，历史不足时为空。 */
  awaitingVerification: number | null;
  /** 从事项创建到实际解决的平均小时数，无样本时为空。 */
  averageResolutionHours: number | null;
};

/**
 * 当前授权范围内的趋势及全期统计。
 */
export type Overview = {
  /** 可用于筛选的来源编号和名称。 */
  sources: { id: string; name: string }[];
  /** 数据库读取截止时刻，供下钻复用。 */
  asOf: string;
  /** 服务端计算的业务粒度。 */
  grain: 'DAY' | 'WEEK' | 'MONTH';
  /** 从早到晚的区间统计。 */
  points: Point[];
  /** 全期计算结果，无来源时为空。 */
  total: Point | null;
};

/**
 * 图表可显示字段与下钻接口分类的对应关系。
 */
export const metrics = {
  added: 'ADDED',
  resolved: 'RESOLVED',
  otherClosed: 'CLOSED_OTHER',
  merged: 'MERGED',
  open: 'OPEN',
  awaitingVerification: 'PENDING_VERIFICATION',
  averageResolutionHours: 'RESOLVED',
} as const;

/**
 * 可见统计字段不包含日期及元数据。
 */
export type Measure = keyof typeof metrics;

/**
 * 下钻只读取统计事实；事项详情仍经过既有页面核验。
 */
export type Contribution = {
  /** 关联事项编号。 */
  issueId: string;
  /** 贡献活动对应的事项版本，同一事项的每次结案独立计数。 */
  issueVersion: number;
  /** 数据源当前名称。 */
  sourceName: string;
  /** 入队、结案或期末状态依据的时间。 */
  occurredAt: string;
  /** 贡献记录所属的统计分类。 */
  kind: (typeof metrics)[Measure];
  /** 当次解决耗时，单位为小时。 */
  resolutionHours: number | null;
};

/**
 * 指标的可见图例和线条样式。
 */
export type Series = {
  /** 当前图对应的统计字段。 */
  measure: Measure;
  /** 图例、数据表与读屏共用的名称。 */
  label: string;
  /** 原型图表颜色。 */
  color: string;
  /** 第一条主线可带浅色面积。 */
  fill?: boolean;
  /** 子集使用虚线以便区分。 */
  dashed?: boolean;
};

/**
 * 以等权的中文格式展示计数及一位小数的小时数。
 */
export function valueText(value: number | null, measure: Measure) {
  return value === null
    ? '—'
    : value.toLocaleString('zh-CN', {
        maximumFractionDigits: measure === 'averageResolutionHours' ? 1 : 0,
      });
}

/**
 * 图表提示和数据表保留区间的起止业务日。
 */
export function periodLabel(point: Point) {
  return point.fromDate === point.toDate ? point.toDate : `${point.fromDate} 至 ${point.toDate}`;
}
