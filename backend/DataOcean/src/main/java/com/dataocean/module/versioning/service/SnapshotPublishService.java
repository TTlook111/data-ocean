package com.dataocean.module.versioning.service;

public interface SnapshotPublishService {

    void publishSnapshot(Long snapshotId, Long operatorId);

    void revokeSnapshot(Long snapshotId, Long operatorId, String reason);
}
