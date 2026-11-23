package com.dataocean.common.overview;

import com.dataocean.common.exception.BizIllegalException;

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

/**
 * 运营看板共享业务日、自然周月和数据库截止时间。
 *
 * @param fromDate 统计期间首日
 * @param toDate 统计期间末日
 * @param zone 解释业务日期的时区
 * @param asOf 平台库截止时间，按 UTC 保存
 */
public record OverviewPeriod(LocalDate fromDate, LocalDate toDate, ZoneId zone, LocalDateTime asOf) {

    /**
     * 首次汇总默认近三十天，下钻可沿用已显示的截止时间。
     */
    public static OverviewPeriod resolve(OverviewQuery query, LocalDateTime now, boolean useAsOf) {
        LocalDateTime asOf = now;
        if (useAsOf && query.getAsOf() != null) {
            // 校验回看截止点后再由 Java 转成数据库的 UTC 时刻。
            if (query.getAsOf().isAfter(now.toInstant(ZoneOffset.UTC))) {
                throw BizIllegalException.invalid("统计截止时间超出当前时间");
            }
            asOf = LocalDateTime.ofInstant(query.getAsOf(), ZoneOffset.UTC);
        }
        ZoneId zone;
        try {
            // 缺省按现行后台统一的北京时间。
            zone = ZoneId.of(query.getTimeZone() == null ? "Asia/Shanghai" : query.getTimeZone());
        } catch (DateTimeException invalid) {
            // 不对外返回 JDK 的时区解析细节。
            throw BizIllegalException.invalid("无效的统计时区");
        }

        LocalDate today = asOf.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate();
        LocalDate from = query.getFromDate();
        LocalDate to = query.getToDate();
        if (from == null && to == null) {
            // 近三十天包含当前业务日。
            return new OverviewPeriod(today.minusDays(29), today, zone, asOf);
        }
        if (from == null || to == null || to.isBefore(from) || to.isAfter(today) || from.isBefore(to.minusYears(10))) {
            // 成对日期限定图表统计期间和分桶数量。
            throw BizIllegalException.invalid("请选择有效的日期范围，最长十年且不能超过今天");
        }
        return new OverviewPeriod(from, to, zone, asOf);
    }

    /**
     * 与已确认的看板筛选规则共用分桶粒度。
     */
    public String grain() {
        long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        return days <= 60 ? "DAY" : days <= 180 ? "WEEK" : "MONTH";
    }

    /**
     * 先输出趋势区间，最后附整个期间供计算全期均值和存量。
     */
    public List<Window> windowsWithTotal() {
        String grain = grain();
        var windows = new ArrayList<Window>();
        LocalDate start = fromDate;
        while (!start.isAfter(toDate)) {
            // 首尾按实际范围截取，周口径以周日结束。
            LocalDate end =
                    switch (grain) {
                        case "WEEK" -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                        case "MONTH" -> start.with(TemporalAdjusters.lastDayOfMonth());
                        default -> start;
                    };
            end = end.isAfter(toDate) ? toDate : end;
            windows.add(window(start, end, windows.size()));
            start = end.plusDays(1);
        }
        windows.add(window(fromDate, toDate, windows.size()));
        return windows;
    }

    /**
     * 下钻按请求的完整期间读取，截止点可早于今天的日末。
     */
    public Window bounds() {
        return window(fromDate, toDate, 0);
    }

    /**
     * 日期两端包含，SQL 使用 UTC 左闭右开区间。
     */
    private Window window(LocalDate from, LocalDate to, int index) {
        var start = LocalDateTime.ofInstant(from.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        var end = LocalDateTime.ofInstant(to.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
        return new Window(index, from, to, start, end.isAfter(asOf) ? asOf : end);
    }

    /**
     * 带原业务日期的聚合窗口。
     *
     * @param index 区间在本次查询中的顺序
     * @param fromDate 含首日的业务起始日期
     * @param toDate 含末日的业务结束日期
     * @param start 首日业务午夜对应的 UTC 时刻
     * @param end 期末次日午夜和数据截止时间中较早的 UTC 时刻
     */
    public record Window(int index, LocalDate fromDate, LocalDate toDate, LocalDateTime start, LocalDateTime end) {}
}
