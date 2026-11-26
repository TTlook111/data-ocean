package com.dataocean.module.dashboard.entity.vo;

import com.dataocean.common.overview.OverviewSourceVO;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 总览只返回五张趋势所需的统计和可核对的时间范围。
 */
public record DashboardOverviewVO(
        /** 当前账号可用于至少一张图的来源。 */
        List<OverviewSourceVO> sources,
        /** 五张图共用的平台统计截止时刻。 */
        Instant asOf,
        /** 日、自然周或自然月的分组粒度。 */
        String grain,
        /** 按业务日排序的统计区间。 */
        List<Point> points,
        /** 全期发生量及期末存量，无来源时为空。 */
        Point total,
        /** 当前可查询且有统计来源的图表。 */
        Set<String> panels,
        /** 问数和反馈共同使用的当前授权来源数，用于标明部分覆盖。 */
        int authorizedSourceCount) {

    /**
     * 一组趋势读数，无统计权限、无来源或历史证据不足时读数为空。
     */
    public record Point(
            /** 区间首日。 */
            LocalDate fromDate,
            /** 区间末日。 */
            LocalDate toDate,
            /** 期间首次提交的唯一正式问数。 */
            Long requests,
            /** 同批问数在截止时刻前已交付结构化结果的次数。 */
            Long delivered,
            /** 期间成功结束的采集任务。 */
            Long succeeded,
            /** 期间失败结束的采集任务。 */
            Long failed,
            /** 期间确认的表说明。 */
            Long tableConfirmed,
            /** 期间确认的字段。 */
            Long fieldConfirmed,
            /** 区间期末可用的正式文档。 */
            Long available,
            /** 期间新增的反馈事项。 */
            Long added,
            /** 期间实际复验解决的反馈事项。 */
            Long resolved) {}
}
