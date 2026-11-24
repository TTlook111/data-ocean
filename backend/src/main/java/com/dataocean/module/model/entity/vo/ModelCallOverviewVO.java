package com.dataocean.module.model.entity.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 同一个统计快照下的模型用量、排行及诊断结果。
 */
@Data
@AllArgsConstructor
public class ModelCallOverviewVO {

    /**
     * 数据库 UTC 截止时间，下钻沿用此值。
     */
    private Instant asOf;

    /**
     * 业务日、周或月的分组粒度。
     */
    private String grain;

    /**
     * 按业务日期排列的趋势数据。
     */
    private List<Point> points;

    /**
     * 全期用量与调用状态。
     */
    private Point total;

    /**
     * 按已知 Token 合计降序排列的模型配置。
     */
    private List<Rank> ranking;

    /**
     * 区间内已知用量及诊断结果。
     *
     * @param fromDate 包含的业务起始日
     * @param toDate 包含的业务结束日
     * @param input 已知输入 Token，调用存在但全部缺失时为空
     * @param output 已知输出 Token，调用存在但全部缺失时为空
     * @param tokens 已知输入及输出合计，缺失双方用量时为空
     * @param succeeded 已成功的回执数
     * @param failed 已失败且结果明确的回执数
     * @param uncertain 结果待核查的回执数
     * @param calls 区间回执总数
     * @param completeUsage 输入和输出用量都已记录的回执数
     */
    public record Point(
            LocalDate fromDate,
            LocalDate toDate,
            Long input,
            Long output,
            Long tokens,
            long succeeded,
            long failed,
            long uncertain,
            long calls,
            long completeUsage) {}

    /**
     * 以配置标识汇总，旧记录保留当次可知的实际模型。
     *
     * @param id 可安全在前端作为标识使用的字符串
     * @param name 所选区间最近一个已知用量回执所对应的名称
     * @param tokens 区间内已知输入及输出 Token 合计
     */
    public record Rank(String id, String name, long tokens) {}
}
