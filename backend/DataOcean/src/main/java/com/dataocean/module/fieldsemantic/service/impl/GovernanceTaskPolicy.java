package com.dataocean.module.fieldsemantic.service.impl;

import java.util.Objects;

public final class GovernanceTaskPolicy {
    private GovernanceTaskPolicy() {}
    public static String summary(int total, int completed, int pending, int failed) {
        if (total == 0) return "EMPTY";
        if (completed == total) return "COMPLETED";
        if (failed == total) return "FAILED";
        return "PARTIAL";
    }
    public static boolean canFinish(String origin, Long before, Long after, String stage) {
        return "ROUND_COMPLETED".equals(stage)
                && (!"FEEDBACK".equals(origin) || before != null && after != null && !Objects.equals(before, after));
    }
}
