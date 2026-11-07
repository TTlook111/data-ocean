package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.versioning.event.SnapshotExpiredEvent;
import com.dataocean.module.versioning.event.SnapshotPublishedEvent;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.service.SnapshotPublishService;
import com.dataocean.module.versioning.service.SnapshotReleaseGateService;
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
    private final SnapshotReleaseGateService releaseGateService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public void publishSnapshot(Long snapshotId, String expectedStatus, Long expectedPublishedSnapshotId, Long operatorId) {
        // 所有同源指针及阻断事实先取得相同来源锁。等待结束后才核对当前状态、门禁和正式指针。
        snapshotMapper.lockReleaseSourceForSnapshot(snapshotId);
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException("快照不存在");
        }
        if (!MetadataSnapshot.STATUS_APPROVED.equals(expectedStatus) || !expectedStatus.equals(snapshot.getStatus())) {
            throw new BusinessException(409, "只有原状态仍为 APPROVED 的快照才能发布");
        }
        // 门禁必须在任何写操作之前执行：失败时旧版本不失效、新版本不发布、不发事件。
        releaseGateService.assertPublishable(snapshot);

        Long datasourceId = snapshot.getDatasourceId();
        Long previousSnapshotId = null;

        List<MetadataSnapshot> publishedList = checkedPointer(datasourceId, expectedPublishedSnapshotId);

        for (MetadataSnapshot old : publishedList) {
            previousSnapshotId = old.getId();
            requireChanged(snapshotMapper.update(null, stateUpdate(old.getId(), datasourceId, MetadataSnapshot.STATUS_PUBLISHED,
                    MetadataSnapshot.STATUS_EXPIRED).set(MetadataSnapshot::getExpiredAt, LocalDateTime.now())));

            auditLogService.recordStatusChange(old.getId(), datasourceId, "EXPIRE",
                    MetadataSnapshot.STATUS_PUBLISHED, MetadataSnapshot.STATUS_EXPIRED,
                    operatorId, "新版本发布，旧版本自动过期");

            eventPublisher.publishEvent(new SnapshotExpiredEvent(this, old.getId(),
                    datasourceId, snapshotId));
        }

        requireChanged(snapshotMapper.update(null, stateUpdate(snapshotId, datasourceId, expectedStatus,
                MetadataSnapshot.STATUS_PUBLISHED).set(MetadataSnapshot::getPublishedAt, LocalDateTime.now())));

        auditLogService.recordStatusChange(snapshotId, datasourceId, "PUBLISH",
                MetadataSnapshot.STATUS_APPROVED, MetadataSnapshot.STATUS_PUBLISHED,
                operatorId, "快照发布成功");

        eventPublisher.publishEvent(new SnapshotPublishedEvent(this, snapshotId,
                datasourceId, operatorId, previousSnapshotId));
    }

    /**
     * {@inheritDoc}
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public void revokeSnapshot(Long snapshotId, String expectedStatus, Long expectedPublishedSnapshotId, Long operatorId, String reason) {
        snapshotMapper.lockReleaseSourceForSnapshot(snapshotId);
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException("快照不存在");
        }
        if (!MetadataSnapshot.STATUS_PUBLISHED.equals(expectedStatus) || !expectedStatus.equals(snapshot.getStatus())
                || !snapshotId.equals(expectedPublishedSnapshotId)) {
            throw new BusinessException(409, "只能撤回仍为当前正式版本的 PUBLISHED 快照");
        }
        checkedPointer(snapshot.getDatasourceId(), expectedPublishedSnapshotId);
        requireChanged(snapshotMapper.update(null, stateUpdate(snapshotId, snapshot.getDatasourceId(),
                expectedStatus, MetadataSnapshot.STATUS_APPROVED)));

        auditLogService.recordStatusChange(snapshotId, snapshot.getDatasourceId(), "REVOKE",
                MetadataSnapshot.STATUS_PUBLISHED, MetadataSnapshot.STATUS_APPROVED,
                operatorId, reason);
    }

    /** 指针预期和候选状态是独立条件，同源两次针对同一旧正式版本的发布只能成功一次。 */
    private List<MetadataSnapshot> checkedPointer(Long datasourceId, Long expectedId) {
        List<MetadataSnapshot> rows = snapshotMapper.selectList(new LambdaQueryWrapper<MetadataSnapshot>()
                .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED).last("LIMIT 2"));
        Long currentId = rows.isEmpty() ? null : rows.get(0).getId();
        if (rows.size() > 1 || !java.util.Objects.equals(currentId, expectedId)) {
            throw new BusinessException(409, "当前正式版本已变化，请重新核对目标和替换范围");
        }
        return rows;
    }

    private LambdaUpdateWrapper<MetadataSnapshot> stateUpdate(Long id, Long source, String before, String after) {
        return new LambdaUpdateWrapper<MetadataSnapshot>().eq(MetadataSnapshot::getId, id)
                .eq(MetadataSnapshot::getDatasourceId, source).eq(MetadataSnapshot::getStatus, before)
                .set(MetadataSnapshot::getStatus, after);
    }

    private void requireChanged(int rows) {
        if (rows != 1) throw new BusinessException(409, "快照或正式指针已变化，本次操作未提交");
    }
}
