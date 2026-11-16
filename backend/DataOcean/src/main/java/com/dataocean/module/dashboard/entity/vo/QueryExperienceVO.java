package com.dataocean.module.dashboard.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 按提问时间归组的问数体验。两个区域各自判权，null 表示该账号没有可读的负责源。 */
public record QueryExperienceVO(String period, LocalDateTime asOf, LocalDateTime start, LocalDateTime end,
                                int targetSeconds, Usage usage, Feedback feedback) {

    /** completed 包含所有已完成任务，measured 只含可据起止时间计算等候秒数的已完成任务。 */
    public record Usage(long total, long completed, long measured, long failed, long timedOut, long processing,
                        long cancelled, long clarification, Double avgSeconds, Long p95Seconds, long slow,
                        Double previousAvgSeconds, List<Bucket> buckets) {}

    /** 没有可测样本的时段仍保留范围，均值为 null，折线可以据此留空。 */
    public record Bucket(LocalDateTime start, LocalDateTime end, long measured, long slow, Double avgSeconds) {}

    /** 分母取反馈负责源内的已完成回答，不能借审计域的回答数计算覆盖率。 */
    public record Feedback(long eligible, long likes, long dislikes, Long previousLikes, Long previousDislikes) {}
}
