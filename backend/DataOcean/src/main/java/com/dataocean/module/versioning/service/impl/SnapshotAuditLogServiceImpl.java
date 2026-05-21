package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.versioning.entity.SnapshotAuditLog;
import com.dataocean.module.versioning.mapper.SnapshotAuditLogMapper;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SnapshotAuditLogServiceImpl implements SnapshotAuditLogService {

    private final SnapshotAuditLogMapper auditLogMapper;

    @Override
    public void recordStatusChange(Long snapshotId, Long datasourceId, String action,
                                   String oldStatus, String newStatus, Long operatorId, String reason) {
        SnapshotAuditLog log = new SnapshotAuditLog();
        log.setSnapshotId(snapshotId);
        log.setDatasourceId(datasourceId);
        log.setAction(action);
        log.setOldStatus(oldStatus);
        log.setNewStatus(newStatus);
        log.setOperatorId(operatorId);
        log.setReason(reason);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    @Override
    public Page<SnapshotAuditLog> listAuditLogs(Long snapshotId, int page, int size) {
        return auditLogMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<SnapshotAuditLog>()
                        .eq(SnapshotAuditLog::getSnapshotId, snapshotId)
                        .orderByDesc(SnapshotAuditLog::getCreatedAt)
        );
    }

    @Override
    public Page<SnapshotAuditLog> listByDatasource(Long datasourceId, String action, int page, int size) {
        LambdaQueryWrapper<SnapshotAuditLog> wrapper = new LambdaQueryWrapper<SnapshotAuditLog>()
                .eq(SnapshotAuditLog::getDatasourceId, datasourceId)
                .eq(action != null, SnapshotAuditLog::getAction, action)
                .orderByDesc(SnapshotAuditLog::getCreatedAt);
        return auditLogMapper.selectPage(new Page<>(page, size), wrapper);
    }
}
