package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * 根据固定北京时间核对普通说法和确实需要澄清的日期。
 */
class QueryDatesTest {

    /**
     * 中英文的前天一致，周一作为自然周起点，两个日子不会合并。
     */
    @Test
    void resolvesUnambiguousCalendarLanguage() {
        var sunday = LocalDate.of(2026, 11, 22);
        assertThat(QueryDates.singleRelativeDay("前天的库存", sunday)).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(QueryDates.singleRelativeDay("the day before yesterday", sunday))
                .isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(QueryDates.singleRelativeDay("这周五", sunday)).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(QueryDates.singleRelativeDay("上周五", sunday)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(QueryDates.singleRelativeDay("前天到今天", sunday)).isNull();
        assertThat(QueryDates.singleRelativeDay("截至前天", sunday)).isNull();
    }

    /**
     * 完整单日只从原话取值，并服从后来补充的明确日期。
     */
    @Test
    void readsAnExactUserDateWithoutTreatingExamplesAndRangesAsSingleDays() {
        var anchor = LocalDate.of(2026, 11, 22);
        var exact = LocalDate.of(2026, 11, 19);
        assertThat(QueryDates.requestedDay("业务日改为2026-11-19，其他条件不变", anchor)).isEqualTo(exact);
        assertThat(QueryDates.requestedDay("查2026年11月19日的出库量", anchor)).isEqualTo(exact);
        assertThat(QueryDates.requestedDay("2026/11/18的库存", "2026/11/19", anchor))
                .isEqualTo(exact);
        assertThat(QueryDates.requestedDay("2026-11-18的库存", "改查昨天", anchor)).isEqualTo(anchor.minusDays(1));
        assertThat(QueryDates.requestedDay("20号的库存", "这个月", anchor)).isEqualTo(anchor.withDayOfMonth(20));
        assertThat(QueryDates.requestedDay("2026-11-19的库存", "改查20号", anchor)).isNull();
        assertThat(QueryDates.requestedDay("从2026-11-17到2026-11-19的走势", anchor)).isNull();
        assertThat(QueryDates.requestedDay("排除2026-11-19的库存", anchor)).isNull();
        assertThat(QueryDates.requestedDay("查2026-11-19之前的库存", anchor)).isNull();
        assertThat(QueryDates.requestedDay("只查SKU-2026-11-19", anchor)).isNull();
        assertThatThrownBy(() -> QueryDates.requestedDay("查2026-02-30的库存", anchor))
                .isInstanceOf(QueryFailure.class);
    }

    /**
     * 口语日号缺月时提醒，实际仓库序号不应触发日期澄清。
     */
    @Test
    void distinguishesBareDayFromWarehouseAndCalendarDate() {
        assertThat(QueryDates.needsMonth("20号还有多少原味燕麦奶？")).isTrue();
        assertThat(QueryDates.needsMonth("二十号的库存")).isTrue();
        assertThat(QueryDates.needsMonth("查11月20号的库存")).isFalse();
        assertThat(QueryDates.needsMonth("查20号仓的库存")).isFalse();
        assertThat(QueryDates.needsMonth("查 2026-11-20 日末库存")).isFalse();
        assertThat(QueryDates.singleRelativeDay("20号的库存\n补充：这个月", LocalDate.of(2026, 11, 22)))
                .isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(QueryDates.singleRelativeDay("这个月 20 号仓的库存", LocalDate.of(2026, 11, 22)))
                .isNull();
    }

    /** 自然周追问不覆盖含明确星期或其他条件的普通问法。 */
    @Test
    void recognizesPreviousWeekOnlyInPureFollowUp() {
        assertThat(QueryDates.previousWeekFollowup("那上周呢？")).isTrue();
        assertThat(QueryDates.previousWeekFollowup("那么上个星期的呢？")).isTrue();
        assertThat(QueryDates.previousWeekFollowup("那上周五呢？")).isFalse();
        assertThat(QueryDates.previousWeekFollowup("那上周只查二号仓")).isFalse();
        assertThat(QueryDates.previousMonday(LocalDate.of(2026, 11, 24)))
                .isEqualTo(LocalDate.of(2026, 11, 16));
    }
}
