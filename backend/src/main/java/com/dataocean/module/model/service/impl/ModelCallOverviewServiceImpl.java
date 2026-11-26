package com.dataocean.module.model.service.impl;

import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.overview.OverviewPeriod;
import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.model.entity.vo.ModelCallOverviewVO;
import com.dataocean.module.model.entity.vo.ModelCallOverviewVO.Point;
import com.dataocean.module.model.entity.vo.ModelCallOverviewVO.Rank;
import com.dataocean.module.model.entity.vo.ModelCallVO;
import com.dataocean.module.model.mapper.ModelCallOverviewMapper;
import com.dataocean.module.model.mapper.ModelMapper;
import com.dataocean.module.model.service.ModelCallOverviewService;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 共用诊断查看权限及真实回执口径，统计不包含连接测试或无调用回执的业务步骤。
 */
@Service
@RequiredArgsConstructor
public class ModelCallOverviewServiceImpl implements ModelCallOverviewService {

    /**
     * 可用于下钻的调用状态分类。
     */
    private static final Set<String> METRICS = Set.of("succeeded", "failed", "uncertain");

    /**
     * 权限变更与同次读取使用既有平台读保护。
     */
    private final GuardMapper guard;

    /**
     * 概览和调用列表共用诊断权限。
     */
    private final PermissionService permissions;

    /**
     * 聚合平台记录及当次模型快照。
     */
    private final ModelCallOverviewMapper overview;

    /**
     * 查询实际调用及其历史诊断字段。
     */
    private final ModelMapper models;

    /**
     * 在相同平台读快照中计算全站节点调用的三个汇总。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ModelCallOverviewVO overview(OverviewQuery query) {
        guard.shared();
        permissions.require("diagnostics.view");
        var period = OverviewPeriod.resolve(query, overview.now(), true);
        var windows = period.windowsWithTotal();
        var points = overview.usage(windows).stream()
                .map(row -> {
                    // 无回执时区分完全没有调用和有调用但用量未知。
                    var window = windows.get(row.ordinal());
                    Long input = row.calls() == 0 ? Long.valueOf(0) : row.input();
                    Long output = row.calls() == 0 ? Long.valueOf(0) : row.output();
                    Long total = input == null && output == null
                            ? null
                            : (input == null ? 0 : input) + (output == null ? 0 : output);
                    return new Point(
                            window.fromDate(),
                            window.toDate(),
                            input,
                            output,
                            total,
                            row.succeeded(),
                            row.failed(),
                            row.uncertain(),
                            row.calls(),
                            row.completeUsage());
                })
                .toList();
        var ranking = overview.ranking(period.bounds()).stream()
                .map(row -> new Rank(row.id(), row.name(), row.tokens()))
                .toList();
        int last = points.size() - 1;
        return new ModelCallOverviewVO(
                Times.instant(period.asOf()), period.grain(), points.subList(0, last), points.get(last), ranking);
    }

    /**
     * 保留既有无过滤列表，同时支持在原截止时刻查看同类明细。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<ModelCallVO> calls(OverviewQuery query) {
        guard.shared();
        permissions.require("diagnostics.view");
        String metric = query.getMetric();
        if (metric != null && !METRICS.contains(metric)) {
            throw BizIllegalException.invalid("无效的调用结果指标");
        }
        Window window = null;
        if (query.getFromDate() != null || query.getToDate() != null || query.getAsOf() != null) {
            // 图表中的起止业务日和截止时刻直接用于明细边界。
            window = OverviewPeriod.resolve(query, overview.now(), true).bounds();
        }
        return PageVO.from(models.calls(query.toFixedOrderMpPage(), window, metric), row -> row);
    }
}
