package com.dataocean.module.datasource.entity.vo;

import com.dataocean.common.overview.OverviewSourceVO;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 数据源接入的任务趋势、终态分布和历史待核实存量。
 */
@Data
@AllArgsConstructor
public class SourceOverviewVO {

    /**
     * 后台接入统计可选的来源编号和名称。
     */
    private List<OverviewSourceVO> sources;

    /**
     * 截止时刻随下钻作为统计上下文回传。
     */
    private Instant asOf;

    /**
     * 日、自然周或者自然月。
     */
    private String grain;

    /**
     * 期间内按业务日期升序的各分组。
     */
    private List<Point> points;

    /**
     * 整个期间独立计算的终态量、均值及期末存量。
     */
    private Point total;

    /**
     * 单个业务日期区间上的统计结果。
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
         * 区间内结束的成功任务数。
         */
        private long succeeded;

        /**
         * 区间内结束的失败任务数。
         */
        private long failed;

        /**
         * 区间内确认取消的任务数。
         */
        private long cancelled;

        /**
         * 期末仍需事实核实的结构变化数。
         */
        private long pending;

        /**
         * 有实际起止时间的成功采集平均执行秒数，无样本时为空。
         */
        private Double averageCollectionSeconds;
    }

    /**
     * 一条可定位原采集任务或结构变化的统计贡献。
     */
    @Data
    @AllArgsConstructor
    public static class Detail {

        /**
         * 采集任务 UUID 或结构变化编号。
         */
        private String id;

        /**
         * 当前来源编号，用于回到原记录。
         */
        private String sourceId;

        /**
         * 当前来源名称。
         */
        private String sourceName;

        /**
         * 采集任务编号；结构变化无任务展示编号。
         */
        private String taskNo;

        /**
         * 原始任务终态或待核实分类。
         */
        private String kind;

        /**
         * 任务结束时刻或待核实变化发现时刻。
         */
        private Instant occurredAt;

        /**
         * 按实际起止时间计算的执行秒数，未执行或结构变化为空。
         */
        private Double durationSeconds;
    }
}
