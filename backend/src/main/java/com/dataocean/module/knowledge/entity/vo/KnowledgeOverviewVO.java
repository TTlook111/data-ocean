package com.dataocean.module.knowledge.entity.vo;

import com.dataocean.common.overview.OverviewSourceVO;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 知识可用存量、发布活动、验证结果和维护关注。
 */
@Data
@AllArgsConstructor
public class KnowledgeOverviewVO {

    /** 后台看板可选来源。 */
    private List<OverviewSourceVO> sources;

    /** 本次读取的 UTC 截止时刻。 */
    private Instant asOf;

    /** 日、自然周或自然月。 */
    private String grain;

    /** 按业务日期排序的趋势分组。 */
    private List<Point> points;

    /** 所选期间汇总。 */
    private Point total;

    /**
     * 期间发生量与期末存量。
     */
    @Data
    @AllArgsConstructor
    public static class Point {

        /** 业务区间首日。 */
        private LocalDate fromDate;

        /** 业务区间末日。 */
        private LocalDate toDate;

        /** 期末实际可用正式文档数，证据不足时为空。 */
        private Long available;

        /** 期内实际核实成功的发布批次。 */
        private long published;

        /** 所选运行日期内的未通过题次。 */
        private long failed;

        /** 所选运行日期内的执行错误题次。 */
        private long errors;

        /** 期末已有较新正文的正式文档数。 */
        private long updating;

        /** 期末确有暂停限制的正式文档数。 */
        private long paused;

        /** 期末冻结引用触及禁止的正式文档数。 */
        private long forbidden;
    }

    /**
     * 核对一条真实贡献，详细正文或运行证据由原页面授权。
     */
    @Data
    @AllArgsConstructor
    public static class Detail {

        /** 对账行的稳定身份。 */
        private String id;

        /** 来源编号。 */
        private String sourceId;

        /** 来源标签。 */
        private String sourceName;

        /** 原文档编号，发布及验证使用各自任务。 */
        private String documentId;

        /** 当时期末对应的原文档修订编号。 */
        private String revisionId;

        /** 原发布任务编号。 */
        private String taskId;

        /** 原逐题验证轮次编号。 */
        private String runId;

        /** 固定绑定或任务标题。 */
        private String title;

        /** 核对的业务分类。 */
        private String kind;

        /** 期末、核实生效或运行开始时刻。 */
        private Instant occurredAt;
    }
}
