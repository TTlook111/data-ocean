package com.dataocean.module.versioning.entity;

import java.util.Map;
import java.util.Set;

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

    public boolean canTransitionTo(SnapshotStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
