package com.dataocean.module.versioning.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.versioning.entity.SnapshotAuditLog;

/**
 * 快照审计日志服务。
 */
public interface SnapshotAuditLogService {

    /**
     * 记录快照状态变更日志。
     *
     * @param snapshotId    快照 ID
     * @param datasourceId  数据源 ID
     * @param action        操作类型
     * @param oldStatus     原状态
     * @param newStatus     新状态
     * @param operatorId    操作人 ID
     * @param reason        操作原因
     */
    void recordStatusChange(Long snapshotId, Long datasourceId, String action,
                             String oldStatus, String newStatus, Long operatorId, String reason);

    /**
     * 分页查询指定快照的审计日志。
     *
     * @param snapshotId 快照 ID
     * @param page       页码
     * @param size       每页条数
     * @return 审计日志分页结果
     */
    Page<SnapshotAuditLog> listAuditLogs(Long snapshotId, int page, int size);

    /**
     * 分页查询数据源维度的审计日志。
     *
     * @param datasourceId 数据源 ID
     * @param action       可选操作类型
     * @param page         页码
     * @param size         每页条数
     * @return 审计日志分页结果
     */
    Page<SnapshotAuditLog> listByDatasource(Long datasourceId, String action, int page, int size);
}
