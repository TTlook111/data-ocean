package com.dataocean.module.dashboard.entity.po;

/** 按时间桶聚合的任务计数。durationSum 存原始秒数之和，以便按样本量计算整个时段的均值。 */
public record QueryExperienceCounts(int slot, long total, long completed, long measured, long failed,
                                    long timedOut, long processing, long cancelled, long clarification,
                                    long durationSum, long slow) {}
