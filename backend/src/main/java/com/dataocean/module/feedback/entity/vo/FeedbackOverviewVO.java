package com.dataocean.module.feedback.entity.vo;

import com.dataocean.module.query.entity.vo.QuerySourceOptionVO;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 同一期间、数据源和读快照下的反馈统计。
 */
@Data
@AllArgsConstructor
public class FeedbackOverviewVO {

    /**
     * 仅供本次统计切换的可见来源。
     */
    private List<QuerySourceOptionVO> sources;

    /**
     * 事实读取截止时刻，下钻沿用该时刻。
     */
    private Instant asOf;

    /**
     * 图表粒度，取 DAY、WEEK 或 MONTH。
     */
    private String grain;

    /**
     * 按业务日期升序排列的区间统计。
     */
    private List<Point> points;

    /**
     * 全期间发生量和期末存量。
     */
    private Point total;

    /**
     * 一段日期的发生量、期末存量与已解决样本耗时。
     */
    @Data
    @AllArgsConstructor
    public static class Point {

        /**
         * 区间首日，包含在内。
         */
        private LocalDate fromDate;

        /**
         * 区间末日，包含在内。
         */
        private LocalDate toDate;

        /**
         * 期间新建事项数。
         */
        private long added;

        /**
         * 期间实际验收解决的结案次数。
         */
        private long resolved;

        /**
         * 期间说明结案次数。
         */
        private long otherClosed;

        /**
         * 期间原事项并入主事项的次数。
         */
        private long merged;

        /**
         * 期末未结事项数。
         */
        private long open;

        /**
         * 期末待验证事项数，旧期间状态无法重建时为空。
         */
        private Long awaitingVerification;

        /**
         * 本期已解决事项从创建到结案的平均小时数，无样本时为空。
         */
        private Double averageResolutionHours;
    }

    /**
     * 对账明细只公开事项身份、来源与统计用的时间事实。
     */
    @Data
    @AllArgsConstructor
    public static class Detail {

        /**
         * 事项编号，用于进入权限独立的办理详情。
         */
        private String issueId;

        /**
         * 对账事实所对应的事项版本，新建事件使用零。
         */
        private int issueVersion;

        /**
         * 当前统计范围内的来源名称。
         */
        private String sourceName;

        /**
         * 进入当前统计分类的活动时间。
         */
        private Instant occurredAt;

        /**
         * 统计时使用的状态或事件编码。
         */
        private String kind;

        /**
         * 已解决明细从创建到结案的小时数，其他分类为空。
         */
        private Double resolutionHours;
    }
}
