package com.dataocean.module.datasource.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.CollectionScheduleMapper.ScheduleRow;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;
import com.dataocean.module.datasource.source.SourceConnectionProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 按计划状态接口核对午夜和时区变更时最早可调度的真实时刻。
 */
class DatasourceSchedulerWindowTest {

    /**
     * 用固定 UTC 时钟验证窗口资格和下一次可运行时间。
     */
    @ParameterizedTest
    @MethodSource("windows")
    void calculatesEligibleTime(String zone, String start, String end, String nowText, String state, String expected) {
        var now = Instant.parse(nowText);
        var settings = new SourceConnectionProperties();
        settings.setTimeZone(zone);
        var scheduler = new DatasourceScheduler(
                null, null, null, null, settings, null, null, null, Clock.fixed(now, ZoneOffset.UTC));
        var source = new DatasourcePO();
        source.setLifecycle("ENABLED");
        var policy = new RuntimePolicyDTO();
        policy.setSyncHours(1);
        policy.setWindowStart(start);
        policy.setWindowEnd(end);
        var revision = new RevisionRow(1, 1, null, null, policy, null);
        // 计划已过到期点，下一次接收只受实际窗口约束。
        var anchor = LocalDateTime.ofInstant(now.minus(2, ChronoUnit.HOURS), ZoneOffset.UTC);
        var plan = new ScheduleRow(false, anchor, 0, 1, null, null);
        var result = scheduler.status(source, revision, plan);
        assertThat(result.getState()).isEqualTo(state);
        assertThat(result.getNextScheduledAt()).isEqualTo(Instant.parse(expected));
    }

    /**
     * 覆盖跨午夜边界、跳过部分或全部窗口及重复小时的二次开启。
     */
    private static Stream<Arguments> windows() {
        return Stream.of(
                Arguments.of(
                        "Asia/Shanghai", "23:00", "02:00", "2026-11-19T15:00:00Z", "READY", "2026-11-19T15:00:00Z"),
                Arguments.of(
                        "Asia/Shanghai", "23:00", "02:00", "2026-11-19T16:30:00Z", "READY", "2026-11-19T16:30:00Z"),
                Arguments.of(
                        "Asia/Shanghai",
                        "23:00",
                        "02:00",
                        "2026-11-19T18:00:00Z",
                        "WAITING_WINDOW",
                        "2026-11-20T15:00:00Z"),
                Arguments.of(
                        "America/New_York",
                        "02:30",
                        "04:00",
                        "2026-03-08T06:55:00Z",
                        "WAITING_WINDOW",
                        "2026-03-08T07:00:00Z"),
                Arguments.of(
                        "America/New_York",
                        "02:10",
                        "02:40",
                        "2026-03-08T06:55:00Z",
                        "WAITING_WINDOW",
                        "2026-03-09T06:10:00Z"),
                Arguments.of(
                        "America/New_York",
                        "01:30",
                        "02:10",
                        "2026-11-01T06:15:00Z",
                        "WAITING_WINDOW",
                        "2026-11-01T06:30:00Z"),
                Arguments.of(
                        "America/New_York",
                        "00:30",
                        "01:15",
                        "2026-11-01T05:30:00Z",
                        "WAITING_WINDOW",
                        "2026-11-01T06:00:00Z"));
    }
}
