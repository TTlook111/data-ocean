package com.dataocean.module.feedback.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.entity.query.FeedbackOverviewQuery;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO.Detail;
import com.dataocean.module.feedback.entity.vo.FeedbackOverviewVO.Point;
import com.dataocean.module.feedback.mapper.FeedbackOverviewMapper;
import com.dataocean.module.feedback.mapper.FeedbackOverviewMapper.DetailRow;
import com.dataocean.module.feedback.mapper.FeedbackOverviewMapper.Totals;
import com.dataocean.module.feedback.mapper.FeedbackOverviewMapper.Window;
import com.dataocean.module.feedback.service.FeedbackOverviewService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.entity.vo.QuerySourceOptionVO;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
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
    public FeedbackOverviewVO overview(FeedbackOverviewQuery query) {
        var scope = scope(query, false);
        var period = scope.period();
        long days = ChronoUnit.DAYS.between(period.start(), period.end()) + 1;
        String grain = days <= 60 ? "DAY" : days <= 180 ? "WEEK" : "MONTH";
        if (scope.ids().isEmpty()) {
            // 有页面权限但没有可统计来源时，客户端显示范围状态。
            return new FeedbackOverviewVO(scope.options(), Times.instant(scope.asOf()), grain, List.of(), null);
        }

        var dates = new ArrayList<Period>();
        LocalDate start = period.start();
        while (!start.isAfter(period.end())) {
            // 周按周一至周日，月按自然月，首末区间保留实际选择的日期。
            LocalDate end =
                    switch (grain) {
                        case "WEEK" -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                        case "MONTH" -> start.with(TemporalAdjusters.lastDayOfMonth());
                        default -> start;
                    };
            end = end.isAfter(period.end()) ? period.end() : end;
            dates.add(new Period(start, end));
            start = end.plusDays(1);
        }

        // 末行独立计算全期间加权均值及期末状态。
        dates.add(period);
        var windows = new ArrayList<Window>();
        for (int index = 0; index < dates.size(); index++) {
            // 业务日期转 UTC 后与当前数据库截止时间取交集。
            windows.add(window(dates.get(index), scope, index));
        }
        var totals = mapper.totals(scope.ids(), windows.get(windows.size() - 1).end(), windows);
        var points =
                totals.stream().map(row -> point(dates.get(row.ordinal()), row)).toList();
        int last = points.size() - 1;
        return new FeedbackOverviewVO(
                scope.options(), Times.instant(scope.asOf()), grain, points.subList(0, last), points.get(last));
    }

    /**
     * 重新核权后读取原界面截止时刻内的贡献记录。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<Detail> details(FeedbackOverviewQuery query) {
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
        var bounds = window(scope.period(), scope, 0);
        // 复杂 CTE 计数由数据库对同一查询求值，保留查询中的状态及来源条件。
        page.setOptimizeCountSql(false);
        return PageVO.from(mapper.details(page, scope.ids(), bounds.start(), bounds.end(), metric), this::detail);
    }

    /**
     * 不论汇总还是点选来源，都以当前有权范围为准。
     */
    private Scope scope(FeedbackOverviewQuery query, boolean details) {
        guard.shared();
        permissions.require("feedback-dashboard.view");
        if (details) {
            // 概览权限单独授予时仍可看汇总。
            permissions.require("feedback.view");
        }
        LocalDateTime asOf = mapper.now();
        if (details && query.getAsOf() != null) {
            // 历史对账可固定截止，不能读取未来事件。
            var requested = LocalDateTime.ofInstant(query.getAsOf(), ZoneOffset.UTC);
            if (requested.isAfter(asOf)) {
                throw BizIllegalException.invalid("统计截止时间超出当前时间");
            }
            asOf = requested;
        }
        ZoneId zone;
        try {
            // 日期只在声明的业务时区解释。
            zone = ZoneId.of(query.getTimeZone() == null ? "Asia/Shanghai" : query.getTimeZone());
        } catch (DateTimeException invalid) {
            // 不把 JDK 时区解析细节回传给调用者。
            throw BizIllegalException.invalid("无效的统计时区");
        }
        LocalDate today = asOf.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate();
        var period = period(query, today);

        var options = access.current(UserContext.getUser()).stream()
                // 只转交名称与编号，不带出授权来源及输出规则。
                .map(item -> new QuerySourceOptionVO(
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
        return new Scope(options, ids, period, zone, asOf);
    }

    /**
     * 缺省读取近三十天，自定义须成对且受可处理的图表区间限制。
     */
    private Period period(FeedbackOverviewQuery query, LocalDate today) {
        LocalDate start = query.getFromDate();
        LocalDate end = query.getToDate();
        if (start == null && end == null) {
            // 首次访问采用原型的默认期间。
            return new Period(today.minusDays(29), today);
        }
        if (start == null
                || end == null
                || end.isBefore(start)
                || end.isAfter(today)
                || start.isBefore(end.minusYears(10))) {
            // 最长区间可按月比较，仍限制单次查询所需的分组数量。
            throw BizIllegalException.invalid("请选择有效的日期范围，最长十年且不能超过今天");
        }
        return new Period(start, end);
    }

    /**
     * 日期两端包含，数据库窗采用 UTC 左闭右开。
     */
    private Window window(Period period, Scope scope, int index) {
        var start = period.start().atStartOfDay(scope.zone()).toInstant();
        var end = period.end().plusDays(1).atStartOfDay(scope.zone()).toInstant();
        var utcEnd = LocalDateTime.ofInstant(end, ZoneOffset.UTC);
        return new Window(
                index,
                LocalDateTime.ofInstant(start, ZoneOffset.UTC),
                utcEnd.isAfter(scope.asOf()) ? scope.asOf() : utcEnd);
    }

    /**
     * 期末与期间指标沿用 SQL 结果，均值从累计时长与有效样本数计算。
     */
    private Point point(Period period, Totals total) {
        Double hours = total.resolved() == 0 ? null : total.resolutionMillis() / (3_600_000.0 * total.resolved());
        return new Point(
                period.start(),
                period.end(),
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
     * 页面使用的统计业务日范围。
     */
    private record Period(
            /** 区间首日。 */
            LocalDate start,
            /** 区间末日。 */
            LocalDate end) {}

    /**
     * 在本次事务获得的权威范围与截止时间。
     */
    private record Scope(
            /** 当前会话可选择的全部数据源。 */
            List<QuerySourceOptionVO> options,
            /** 本次实际参与统计的来源编号。 */
            List<Long> ids,
            /** 请求使用的实际期间。 */
            Period period,
            /** 统计业务时区。 */
            ZoneId zone,
            /** 台账读取的 UTC 截止时间。 */
            LocalDateTime asOf) {}
}
