package com.dataocean.module.versioning.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.versioning.entity.SnapshotAuditLog;

public interface SnapshotAuditLogService {

    void recordStatusChange(Long snapshotId, Long datasourceId, String action,
                            String oldStatus, String newStatus, Long operatorId, String reason);

    Page<SnapshotAuditLog> listAuditLogs(Long snapshotId, int page, int size);

    Page<SnapshotAuditLog> listByDatasource(Long datasourceId, String action, int page, int size);
}
