package com.dataocean.module.datasource.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.overview.OverviewPeriod;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.overview.OverviewSourceVO;
import com.dataocean.module.datasource.entity.vo.SourceOverviewVO;
import com.dataocean.module.datasource.entity.vo.SourceOverviewVO.Detail;
import com.dataocean.module.datasource.entity.vo.SourceOverviewVO.Point;
import com.dataocean.module.datasource.mapper.SourceOverviewMapper;
import com.dataocean.module.datasource.mapper.SourceOverviewMapper.DetailRow;
import com.dataocean.module.datasource.mapper.SourceStateMapper;
import com.dataocean.module.datasource.service.SourceOverviewService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 由接入管理权限限定汇总，由采集查看权限限定原记录。
 */
@Service
@RequiredArgsConstructor
public class SourceOverviewServiceImpl implements SourceOverviewService {

    /**
     * 各终态、成功耗时有效样本及结构事实在办存量。
     */
    private static final Set<String> METRICS = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "DURATION", "PENDING");

    /**
     * 与角色和账号权限变更串行核对。
     */
    private final GuardMapper guard;

    /**
     * 区分汇总访问和明细查看。
     */
    private final PermissionService permissions;

    /**
     * 沿用接入后台既有来源选项。
     */
    private final SourceStateMapper sources;

    /**
     * 从平台台账计算统计与贡献记录。
     */
    private final SourceOverviewMapper mapper;

    /**
     * 在一个读快照确定统计范围、分组值与全期值。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SourceOverviewVO overview(OverviewQuery query) {
        guard.shared();
        permissions.require("sources-dashboard.view");
        var period = OverviewPeriod.resolve(query, mapper.now(), false);
        var options = sources.options().stream()
                // 汇总页只返回编号和名称，不读取连接资料。
                .map(source -> new OverviewSourceVO(Ids.text(source.getId()), source.getName()))
                .toList();
        Long source = Ids.nullable(query.getSourceId());
        if (source != null && options.stream().noneMatch(item -> item.getId().equals(Ids.text(source)))) {
            // 显式点选的来源必须在接入管理范围内。
            throw BizIllegalException.missing();
        }
        if (options.isEmpty()) {
            // 保留未接入和已接入但没有任务的区别。
            return new SourceOverviewVO(options, Times.instant(period.asOf()), period.grain(), List.of(), null);
        }
        var bounds = period.bounds();
        var windows = period.windowsWithTotal();
        var totals = mapper.totals(source, bounds.start(), bounds.end(), windows);
        var points = totals.stream()
                .map(row -> {
                    // 日期使用业务分组标签，数值由同一 SQL 读快照提供。
                    var window = windows.get(row.ordinal());
                    return new Point(
                            window.fromDate(),
                            window.toDate(),
                            row.succeeded(),
                            row.failed(),
                            row.cancelled(),
                            row.pending(),
                            row.averageCollectionSeconds());
                })
                .toList();
        int last = points.size() - 1;
        return new SourceOverviewVO(
                options, Times.instant(period.asOf()), period.grain(), points.subList(0, last), points.get(last));
    }

    /**
     * 分页对账使用同一统计截止时刻并重新核验当前权限。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<Detail> details(OverviewQuery query) {
        guard.shared();
        permissions.require("sources-dashboard.view");
        permissions.require("collections.view");
        if (query.getMetric() == null || !METRICS.contains(query.getMetric())) {
            // 仅接受页面可核对的分类。
            throw BizIllegalException.invalid("无效的接入统计指标");
        }
        Long source = Ids.nullable(query.getSourceId());
        if (source != null
                && sources.options().stream().noneMatch(item -> item.getId().equals(source))) {
            // 显式来源仍以接入管理范围为准。
            throw BizIllegalException.missing();
        }
        var bounds = OverviewPeriod.resolve(query, mapper.now(), true).bounds();
        var page = query.<DetailRow>toFixedOrderMpPage();
        // 保留 CTE 的同一统计范围用于计数。
        page.setOptimizeCountSql(false);
        return PageVO.from(
                mapper.details(page, source, bounds.start(), bounds.end(), query.getMetric()),
                row -> new Detail(
                        row.id(),
                        Ids.text(row.sourceId()),
                        row.sourceName(),
                        row.taskNo(),
                        row.kind(),
                        Times.instant(row.occurredAt()),
                        row.durationSeconds()));
    }
}
