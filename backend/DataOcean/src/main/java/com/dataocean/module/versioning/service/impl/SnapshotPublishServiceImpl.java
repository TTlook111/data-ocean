package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.versioning.event.SnapshotExpiredEvent;
import com.dataocean.module.versioning.event.SnapshotPublishedEvent;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.service.SnapshotPublishService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 快照发布服务实现。
 * <p>
 * 发布新快照时自动过期同一数据源旧发布版本，并记录审计日志和发布生命周期事件。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SnapshotPublishServiceImpl implements SnapshotPublishService {

    private final MetadataSnapshotMapper snapshotMapper;
    private final SnapshotAuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void publishSnapshot(Long snapshotId, Long operatorId) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException("快照不存在");
        }
        if (!MetadataSnapshot.STATUS_APPROVED.equals(snapshot.getStatus())) {
            throw new BusinessException("只有 APPROVED 状态的快照才能发布");
        }

        Long datasourceId = snapshot.getDatasourceId();
        Long previousSnapshotId = null;

        List<MetadataSnapshot> publishedList = snapshotMapper.selectList(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
        );

        for (MetadataSnapshot old : publishedList) {
            previousSnapshotId = old.getId();
            old.setStatus(MetadataSnapshot.STATUS_EXPIRED);
            old.setExpiredAt(LocalDateTime.now());
            snapshotMapper.updateById(old);

            auditLogService.recordStatusChange(old.getId(), datasourceId, "EXPIRE",
                    MetadataSnapshot.STATUS_PUBLISHED, MetadataSnapshot.STATUS_EXPIRED,
                    operatorId, "新版本发布，旧版本自动过期");

            eventPublisher.publishEvent(new SnapshotExpiredEvent(this, old.getId(),
                    datasourceId, snapshotId));
        }

        snapshot.setStatus(MetadataSnapshot.STATUS_PUBLISHED);
        snapshot.setPublishedAt(LocalDateTime.now());
        snapshot.setReviewedBy(operatorId);
        snapshotMapper.updateById(snapshot);

        auditLogService.recordStatusChange(snapshotId, datasourceId, "PUBLISH",
                MetadataSnapshot.STATUS_APPROVED, MetadataSnapshot.STATUS_PUBLISHED,
                operatorId, "快照发布成功");

        eventPublisher.publishEvent(new SnapshotPublishedEvent(this, snapshotId,
                datasourceId, operatorId, previousSnapshotId));
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void revokeSnapshot(Long snapshotId, Long operatorId, String reason) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException("快照不存在");
        }
        if (!MetadataSnapshot.STATUS_PUBLISHED.equals(snapshot.getStatus())) {
            throw new BusinessException("只有 PUBLISHED 状态的快照才能撤回");
        }

        snapshot.setStatus(MetadataSnapshot.STATUS_APPROVED);
        snapshotMapper.updateById(snapshot);

        auditLogService.recordStatusChange(snapshotId, snapshot.getDatasourceId(), "REVOKE",
                MetadataSnapshot.STATUS_PUBLISHED, MetadataSnapshot.STATUS_APPROVED,
                operatorId, reason);
    }
}
