package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(QueryDates.singleRelativeDay("the day before yesterday", sunday)).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(QueryDates.singleRelativeDay("这周五", sunday)).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(QueryDates.singleRelativeDay("上周五", sunday)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(QueryDates.singleRelativeDay("前天到今天", sunday)).isNull();
        assertThat(QueryDates.singleRelativeDay("截至前天", sunday)).isNull();
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
        assertThat(QueryDates.singleRelativeDay("这个月 20 号仓的库存", LocalDate.of(2026, 11, 22))).isNull();
    }
}
