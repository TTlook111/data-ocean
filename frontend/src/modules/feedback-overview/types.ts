import type { DashboardSeries } from '../overview/types';

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
 * 反馈页面的图表仅引用自身可核对的指标。
 */
export type Series = DashboardSeries<Measure>;
