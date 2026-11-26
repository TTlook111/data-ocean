package com.dataocean.module.governance.entity.vo;

import com.dataocean.common.overview.OverviewSourceVO;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 治理确认发生量、业务复核存量和期末对象治理进度。
 */
@Data
@AllArgsConstructor
public class GovernanceOverviewVO {

    /**
     * 当前可用于统计的后台数据源。
     */
    private List<OverviewSourceVO> sources;

    /**
     * 读取快照的 UTC 截止时刻。
     */
    private Instant asOf;

    /**
     * 趋势粒度，日、自然周或自然月。
     */
    private String grain;

    /**
     * 按期间分组的确认发生量及待核对期末值。
     */
    private List<Point> points;

    /**
     * 整个期间的汇总及结束时点的治理进度。
     */
    private Point total;

    /**
     * 期间计数和该期间末的存量。
     */
    @Data
    @AllArgsConstructor
    public static class Point {

        /**
         * 区间首日。
         */
        private LocalDate fromDate;

        /**
         * 区间末日。
         */
        private LocalDate toDate;

        /**
         * 表说明确认次数。
         */
        private long tableConfirmed;

        /**
         * 字段确认次数。
         */
        private long fieldConfirmed;

        /**
         * 关联确认次数。
         */
        private long relationConfirmed;

        /**
         * 指标确认次数。
         */
        private long metricConfirmed;

        /**
         * 区间结束前仍有变化复核待办的业务条目数。
         */
        private long pendingReview;

        /**
         * 所选期间末已经完成主治理的表或视图数量，仅全期返回。
         */
        private Long complete;

        /**
         * 所选期间末已开始但未完成主治理的对象数量，仅全期返回。
         */
        private Long inProgress;

        /**
         * 所选期间末尚未开始主治理的对象数量，仅全期返回。
         */
        private Long unstarted;
    }

    /**
     * 汇总贡献的最小投影，可定位既有治理记录。
     */
    @Data
    @AllArgsConstructor
    public static class Detail {

        /**
         * 参与统计的事实编号。
         */
        private String id;

        /**
         * 数据源稳定编号。
         */
        private String sourceId;

        /**
         * 数据源当前展示名。
         */
        private String sourceName;

        /**
         * 原表或视图稳定编号。
         */
        private String objectId;

        /**
         * 被核对条目、字段或候选的稳定编号。
         */
        private String targetId;

        /**
         * 表说明、字段、关联、指标或治理进度分类。
         */
        private String kind;

        /**
         * 可回看的对象路径或业务名称。
         */
        private String label;

        /**
         * 确认或待核对发生时间；对象进度使用期间期末。
         */
        private Instant occurredAt;
    }
}
