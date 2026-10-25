package com.dataocean.module.fieldsemantic.service;

import java.util.Map;

public interface GovernanceTaskService {
    Map<String, Object> discover(Long snapshotId, Long userId, boolean aiFirst);
    Map<String, Object> list(Long snapshotId, Long userId, int page, int size);
    void retry(Long id, Long userId);
    void queueFeedback(Long columnId, Long snapshotId, Long revisionId, Long userId);
    void processQueued();
    void pause(Long snapshotId,Long userId);
}
