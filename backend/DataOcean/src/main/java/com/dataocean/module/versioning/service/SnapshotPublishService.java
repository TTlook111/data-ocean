package com.dataocean.module.versioning.service;

/**
 * 快照发布服务。
 */
public interface SnapshotPublishService {

    /**
     * 发布快照，并使同一数据源的旧发布版本过期。
     *
     * @param snapshotId 快照 ID
     * @param operatorId 操作人 ID
     */
    void publishSnapshot(Long snapshotId, Long operatorId);

    /**
     * 撤回已发布快照。
     *
     * @param snapshotId 快照 ID
     * @param operatorId 操作人 ID
     * @param reason     撤回原因
     */
    void revokeSnapshot(Long snapshotId, Long operatorId, String reason);
}
