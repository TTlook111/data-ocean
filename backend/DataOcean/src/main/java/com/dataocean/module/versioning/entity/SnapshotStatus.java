package com.dataocean.module.versioning.entity;

import java.util.Map;
import java.util.Set;

/**
 * 元数据快照生命周期状态。
 */
public enum SnapshotStatus {
    DRAFT,
    CHECKING,
    ISSUE_FOUND,
    APPROVED,
    PUBLISHED,
    EXPIRED;

    private static final Map<SnapshotStatus, Set<SnapshotStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(CHECKING),
            CHECKING, Set.of(ISSUE_FOUND, APPROVED),
            ISSUE_FOUND, Set.of(APPROVED),
            APPROVED, Set.of(PUBLISHED),
            PUBLISHED, Set.of(EXPIRED, APPROVED)
    );

    /**
     * 判断当前状态是否允许流转到目标状态。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     */
    public boolean canTransitionTo(SnapshotStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
