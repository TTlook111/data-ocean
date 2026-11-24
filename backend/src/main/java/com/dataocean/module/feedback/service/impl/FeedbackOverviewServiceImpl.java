package com.dataocean.module.feedback.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.overview.OverviewPeriod;
import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.overview.OverviewSourceVO;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO.Detail;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO.Point;
import com.dataocean.module.feedback.mapper.FeedbackOverviewMapper;
import com.dataocean.module.feedback.mapper.FeedbackOverviewMapper.DetailRow;
import com.dataocean.module.feedback.mapper.FeedbackOverviewMapper.Totals;
import com.dataocean.module.feedback.service.FeedbackOverviewService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 概览和下钻共享当前源授权、期间语义和数据库截止时刻。
 */
@Service
@RequiredArgsConstructor
public class FeedbackOverviewServiceImpl implements FeedbackOverviewService {

    /**
     * 可对账的贡献事实以及期末状态。
     */
    private static final Set<String> METRICS =
            Set.of("ADDED", "RESOLVED", "CLOSED_OTHER", "MERGED", "OPEN", "PENDING_VERIFICATION");

    /**
     * 与当前人员状态和源授权变更串行核对。
     */
    private final GuardMapper guard;

    /**
     * 看板访问与事项明细查看分别授权。
     */
    private final PermissionService permissions;

    /**
     * 复用当前人员实际获得的来源授权。
     */
    private final AccessScopeService access;

    /**
     * 从事项及台账读取统计事实。
     */
    private final FeedbackOverviewMapper mapper;

    /**
     * 汇总保留期末存量与平均值的原始口径，趋势按日期切分。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FeedbackOverviewVO overview(OverviewQuery query) {
        var scope = scope(query, false);
        var period = scope.period();
        String grain = period.grain();
        if (scope.ids().isEmpty()) {
            // 有页面权限但没有可统计来源时，客户端显示范围状态。
            return new FeedbackOverviewVO(scope.options(), Times.instant(period.asOf()), grain, List.of(), null);
        }

        var windows = period.windowsWithTotal();
        var totals = mapper.totals(scope.ids(), period.bounds().end(), windows);
        var points = totals.stream()
                .map(row -> point(windows.get(row.ordinal()), row))
                .toList();
        int last = points.size() - 1;
        return new FeedbackOverviewVO(
                scope.options(), Times.instant(period.asOf()), grain, points.subList(0, last), points.get(last));
    }

    /**
     * 重新核权后读取原界面截止时刻内的贡献记录。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<Detail> details(OverviewQuery query) {
        var scope = scope(query, true);
        String metric = query.getMetric();
        if (metric == null || !METRICS.contains(metric)) {
            // 错误分类应得到明确的校验反馈。
            throw BizIllegalException.invalid("无效的反馈统计指标");
        }
        var page = query.<DetailRow>toFixedOrderMpPage();
        if (scope.ids().isEmpty()) {
            // 空授权直接返回空页，不进入可能缺少来源条件的查询。
            return PageVO.from(page, this::detail);
        }
        var bounds = scope.period().bounds();
        // 复杂 CTE 计数由数据库对同一查询求值，保留查询中的状态及来源条件。
        page.setOptimizeCountSql(false);
        return PageVO.from(mapper.details(page, scope.ids(), bounds.start(), bounds.end(), metric), this::detail);
    }

    /**
     * 不论汇总还是点选来源，都以当前有权范围为准。
     */
    private Scope scope(OverviewQuery query, boolean details) {
        guard.shared();
        permissions.require("feedback-dashboard.view");
        if (details) {
            // 概览权限单独授予时仍可看汇总。
            permissions.require("feedback.view");
        }
        var period = OverviewPeriod.resolve(query, mapper.now(), true);

        var options = access.current(UserContext.getUser()).stream()
                // 只转交名称与编号，不带出授权来源及输出规则。
                .map(item -> new OverviewSourceVO(
                        item.getSource().getId(), item.getSource().getName()))
                .toList();
        var ids = options.stream().map(item -> Ids.parse(item.getId())).toList();
        Long source = Ids.nullable(query.getSourceId());
        if (source != null) {
            // 对不存在和已撤权来源给出同一外部结果。
            if (!ids.contains(source)) {
                throw BizIllegalException.missing();
            }
            ids = List.of(source);
        }
        return new Scope(options, ids, period);
    }

    /**
     * 期末与期间指标沿用 SQL 结果，均值从累计时长与有效样本数计算。
     */
    private Point point(Window period, Totals total) {
        Double hours = total.resolved() == 0 ? null : total.resolutionMillis() / (3_600_000.0 * total.resolved());
        return new Point(
                period.fromDate(),
                period.toDate(),
                total.added(),
                total.resolved(),
                total.otherClosed(),
                total.merged(),
                total.openCount(),
                total.awaiting(),
                hours);
    }

    /**
     * 对外使用字符串编号及标准 UTC 时刻。
     */
    private Detail detail(DetailRow row) {
        Double hours = row.resolutionMillis() == null ? null : row.resolutionMillis() / 3_600_000.0;
        return new Detail(
                Ids.text(row.issueId()),
                row.issueVersion(),
                row.sourceName(),
                Times.instant(row.occurredAt()),
                row.kind(),
                hours);
    }

    /**
     * 在本次事务获得的权威范围与截止时间。
     */
    private record Scope(
            /** 当前会话可选择的全部数据源。 */
            List<OverviewSourceVO> options,
            /** 本次实际参与统计的来源编号。 */
            List<Long> ids,
            /** 请求使用的实际期间及数据库截止时刻。 */
            OverviewPeriod period) {}
}
