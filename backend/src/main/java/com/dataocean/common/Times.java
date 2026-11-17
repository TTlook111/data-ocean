package com.dataocean.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class Times {
    private Times() {}
    public static Instant instant(LocalDateTime time) { return time == null ? null : time.toInstant(ZoneOffset.UTC); }
}
