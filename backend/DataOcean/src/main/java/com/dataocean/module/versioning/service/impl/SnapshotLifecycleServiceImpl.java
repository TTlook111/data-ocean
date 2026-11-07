package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.service.SchemaDiffService;
import com.dataocean.module.versioning.enums.SnapshotStatus;
import com.dataocean.module.versioning.entity.vo.SnapshotVersionHistoryVO;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.service.SnapshotLifecycleService;
import com.dataocean.module.versioning.service.SnapshotReleaseGateService;
import com.dataocean.module.user.service.UserService;
import com.dataocean.module.user.entity.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 快照生命周期服务实现。
 * <p>
 * 管理快照状态机、发布前置条件、版本历史聚合和快照差异比较。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SnapshotLifecycleServiceImpl implements SnapshotLifecycleService {

    private final MetadataSnapshotMapper snapshotMapper;
    private final SnapshotReleaseGateService releaseGateService;
    private final SchemaDiffService schemaDiffService;
    private final SnapshotAuditLogService auditLogService;
    private final UserService userService;
    private final DatasourceMapper datasourceMapper;

    /**
     * {@inheritDoc}
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override
    public void changeStatus(Long snapshotId, String expectedStatus, String targetStatus, Long operatorId, String reason) {
        // 来源锁内重读状态和审核门禁，避免审核使用等待另一个来源写事务之前的数据库快照。
        snapshotMapper.lockReleaseSourceForSnapshot(snapshotId);
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException("快照不存在");
        }

        SnapshotStatus current = SnapshotStatus.valueOf(snapshot.getStatus());
        SnapshotStatus target;
        try {
            target = SnapshotStatus.valueOf(targetStatus);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(400, "非法快照状态：" + targetStatus);
        }

        if (target == SnapshotStatus.PUBLISHED) {
            throw new BusinessException("发布操作请使用专用发布接口");
        }

        if (current == SnapshotStatus.PUBLISHED) {
            throw new BusinessException(409, "正式版本只能通过发布替换或专用撤回接口变更");
        }
        if (!current.name().equals(expectedStatus)) {
            throw new BusinessException(409, "快照状态已变化，请重新核对审核依据");
        }

        if (!current.canTransitionTo(target)) {
            throw new BusinessException("非法状态流转：" + current + " → " + target);
        }

        if (target == SnapshotStatus.APPROVED) {
            releaseGateService.assertApprovable(snapshot);
        }

        String oldStatus = snapshot.getStatus();
        LambdaUpdateWrapper<MetadataSnapshot> update = new LambdaUpdateWrapper<MetadataSnapshot>()
                .eq(MetadataSnapshot::getId, snapshotId).eq(MetadataSnapshot::getStatus, expectedStatus)
                .set(MetadataSnapshot::getStatus, targetStatus);
        if (target == SnapshotStatus.APPROVED) {
            // 人工批准保留实际审核人；发布另由发布审计记录其操作者。
            update.set(MetadataSnapshot::getReviewedBy, operatorId)
                    .set(MetadataSnapshot::getReviewedAt, java.time.LocalDateTime.now());
        }
        if (snapshotMapper.update(null, update) != 1) throw new BusinessException(409, "快照状态已变化，请重新核对");

        auditLogService.recordStatusChange(snapshotId, snapshot.getDatasourceId(),
                "STATUS_TRANSITION", oldStatus, targetStatus, operatorId, reason);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<SnapshotVersionHistoryVO> listVersionHistory(Long datasourceId, int page, int size) {
        return listVersionHistoryInDatasources(
                datasourceId == null ? null : List.of(datasourceId), page, size);
    }

    @Override
    public Page<SnapshotVersionHistoryVO> listVersionHistoryInDatasources(Collection<Long> datasourceIds,
                                                                          int page, int size) {
        // 空集合表示没有任何负责源：直接返回空页，不能退化成“全部数据源”。
        if (datasourceIds != null && datasourceIds.isEmpty()) {
            return new Page<>(page, size, 0);
        }
        Page<MetadataSnapshot> snapshotPage = snapshotMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .in(datasourceIds != null, MetadataSnapshot::getDatasourceId, datasourceIds)
                        .orderByDesc(MetadataSnapshot::getCreatedAt)
        );

        Page<SnapshotVersionHistoryVO> result = new Page<>(page, size, snapshotPage.getTotal());
        Map<Long, String> datasourceNames = datasourceMapper.selectList(
                        new LambdaQueryWrapper<Datasource>().eq(Datasource::getDeleted, 0L))
                .stream()
                .collect(Collectors.toMap(Datasource::getId, Datasource::getName));
        List<SnapshotVersionHistoryVO> records = snapshotPage.getRecords().stream()
                .map(snapshot -> toHistoryVO(snapshot, datasourceNames))
                .collect(Collectors.toList());
        result.setRecords(records);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SchemaDiffVO compareVersions(Long oldSnapshotId, Long newSnapshotId) {
        // 版本差异以两个获权快照的真实来源为准，避免页面借用元数据目录才能辨认方向。
        MetadataSnapshot before = snapshotMapper.selectById(oldSnapshotId);
        MetadataSnapshot after = snapshotMapper.selectById(newSnapshotId);
        if (before == null || after == null) throw new BusinessException(404, "对比快照不存在");
        if (before.getDatasourceId() == null || !before.getDatasourceId().equals(after.getDatasourceId())) {
            throw new BusinessException(400, "只能对比同一数据源的两个版本");
        }
        SchemaDiffVO diff = schemaDiffService.compareSnapshots(oldSnapshotId, newSnapshotId);
        diff.setOldSnapshotId(oldSnapshotId);
        diff.setNewSnapshotId(newSnapshotId);
        diff.setOldSnapshotVersion(before.getSnapshotVersion());
        diff.setNewSnapshotVersion(after.getSnapshotVersion());
        diff.setDatasourceId(before.getDatasourceId());
        return diff;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MetadataSnapshot getPublishedSnapshot(Long datasourceId) {
        return snapshotMapper.selectOne(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                        .last("LIMIT 1")
        );
    }

    private SnapshotVersionHistoryVO toHistoryVO(MetadataSnapshot snapshot, Map<Long, String> datasourceNames) {
        SnapshotVersionHistoryVO vo = new SnapshotVersionHistoryVO();
        vo.setSnapshotId(snapshot.getId());
        vo.setDatasourceId(snapshot.getDatasourceId());
        vo.setDatasourceName(datasourceNames.getOrDefault(snapshot.getDatasourceId(), "未知"));
        vo.setSnapshotVersion(snapshot.getSnapshotVersion());
        vo.setStatus(snapshot.getStatus());
        vo.setQualityScore(snapshot.getQualityScore());
        vo.setTableCount(snapshot.getTableCount());
        vo.setColumnCount(snapshot.getColumnCount());
        vo.setSchemaHash(snapshot.getSchemaHash());
        vo.setCreatedAt(snapshot.getCreatedAt());
        vo.setPublishedAt(snapshot.getPublishedAt());
        vo.setExpiredAt(snapshot.getExpiredAt());
        if (snapshot.getReviewedBy() != null) {
            try {
                UserVO user = userService.getUserById(snapshot.getReviewedBy());
                vo.setReviewedBy(user != null ? user.getRealName() : null);
            } catch (Exception ignored) {}
        }
        return vo;
    }
}
