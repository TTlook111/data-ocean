package com.dataocean.common.overview;

import com.dataocean.common.PageQuery;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 概览与下钻共用统计期间和数据源，下钻另用公共分页。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OverviewQuery extends PageQuery {

    /**
     * 首个统计业务日，包含该日。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fromDate;

    /**
     * 最后统计业务日，包含该日。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate toDate;

    /**
     * 解释日期的 IANA 时区，缺省为北京时间。
     */
    private String timeZone;

    /**
     * 当前有权查看的数据源编号，缺省汇总可见数据源。
     */
    private String sourceId;

    /**
     * 下钻的指标编码，汇总接口忽略此值。
     */
    private String metric;

    /**
     * 下钻所依据的原概览截止时刻。
     */
    private Instant asOf;
}
