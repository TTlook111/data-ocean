package com.dataocean.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 统一将平台时间转换为 UTC 毫秒精度，避免数据库与接口比较漂移。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Times {

    /**
     * 将数据库 UTC 时间转换为明确的对外 UTC 时刻。
     */
    public static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
