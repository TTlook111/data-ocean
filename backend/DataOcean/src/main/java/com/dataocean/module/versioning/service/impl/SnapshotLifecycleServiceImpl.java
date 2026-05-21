package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.service.SchemaDiffService;
import com.dataocean.module.versioning.entity.SnapshotStatus;
import com.dataocean.module.versioning.entity.vo.SnapshotVersionHistoryVO;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.service.SnapshotLifecycleService;
import com.dataocean.module.user.service.UserService;
import com.dataocean.module.user.entity.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SnapshotLifecycleServiceImpl implements SnapshotLifecycleService {

    private final MetadataSnapshotMapper snapshotMapper;
    private final MetadataQualityIssueMapper qualityIssueMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final SchemaDiffService schemaDiffService;
    private final SnapshotAuditLogService auditLogService;
    private final UserService userService;

    @Transactional
    @Override
    public void changeStatus(Long snapshotId, String targetStatus, Long operatorId, String reason) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException("快照不存在");
        }

        SnapshotStatus current = SnapshotStatus.valueOf(snapshot.getStatus());
        SnapshotStatus target = SnapshotStatus.valueOf(targetStatus);

        if (target == SnapshotStatus.PUBLISHED) {
            throw new BusinessException("发布操作请使用专用发布接口");
        }

        if (current == SnapshotStatus.PUBLISHED && target == SnapshotStatus.APPROVED) {
            throw new BusinessException("撤回操作请使用专用撤回接口");
        }

        if (!current.canTransitionTo(target)) {
            throw new BusinessException("非法状态流转：" + current + " → " + target);
        }

        checkPreconditions(snapshot, current, target);

        String oldStatus = snapshot.getStatus();
        snapshot.setStatus(targetStatus);
        snapshotMapper.updateById(snapshot);

        auditLogService.recordStatusChange(snapshotId, snapshot.getDatasourceId(),
                "STATUS_TRANSITION", oldStatus, targetStatus, operatorId, reason);
    }

    @Override
    public Page<SnapshotVersionHistoryVO> listVersionHistory(Long datasourceId, int page, int size) {
        Page<MetadataSnapshot> snapshotPage = snapshotMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                        .orderByDesc(MetadataSnapshot::getCreatedAt)
        );

        Page<SnapshotVersionHistoryVO> result = new Page<>(page, size, snapshotPage.getTotal());
        List<SnapshotVersionHistoryVO> records = snapshotPage.getRecords().stream()
                .map(this::toHistoryVO)
                .collect(Collectors.toList());
        result.setRecords(records);
        return result;
    }

    @Override
    public SchemaDiffVO compareVersions(Long oldSnapshotId, Long newSnapshotId) {
        return schemaDiffService.compareSnapshots(oldSnapshotId, newSnapshotId);
    }

    @Override
    public MetadataSnapshot getPublishedSnapshot(Long datasourceId) {
        return snapshotMapper.selectOne(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                        .last("LIMIT 1")
        );
    }

    private void checkPreconditions(MetadataSnapshot snapshot, SnapshotStatus current, SnapshotStatus target) {
        if (current == SnapshotStatus.APPROVED && target == SnapshotStatus.PUBLISHED) {
            long unresolvedHigh = qualityIssueMapper.selectCount(
                    new LambdaQueryWrapper<MetadataQualityIssue>()
                            .eq(MetadataQualityIssue::getSnapshotId, snapshot.getId())
                            .eq(MetadataQualityIssue::getSeverity, "HIGH")
                            .in(MetadataQualityIssue::getStatus, "OPEN", "CONFIRMED")
            );
            if (unresolvedHigh > 0) {
                throw new BusinessException(409, "无法发布：存在 " + unresolvedHigh + " 个未解决的高风险问题");
            }

            long validTables = tableMetaMapper.selectCount(
                    new LambdaQueryWrapper<DbTableMeta>()
                            .eq(DbTableMeta::getSnapshotId, snapshot.getId())
                            .in(DbTableMeta::getGovernanceStatus, "NORMAL", "RECOMMENDED")
            );
            if (validTables == 0) {
                throw new BusinessException(409, "无法发布：快照中无 NORMAL/RECOMMENDED 状态的表");
            }
        }

        if (current == SnapshotStatus.ISSUE_FOUND && target == SnapshotStatus.APPROVED) {
            long unresolvedHigh = qualityIssueMapper.selectCount(
                    new LambdaQueryWrapper<MetadataQualityIssue>()
                            .eq(MetadataQualityIssue::getSnapshotId, snapshot.getId())
                            .eq(MetadataQualityIssue::getSeverity, "HIGH")
                            .in(MetadataQualityIssue::getStatus, "OPEN", "CONFIRMED")
            );
            if (unresolvedHigh > 0) {
                throw new BusinessException("无法审核通过：仍有 " + unresolvedHigh + " 个未解决的高风险问题");
            }
        }
    }

    private SnapshotVersionHistoryVO toHistoryVO(MetadataSnapshot snapshot) {
        SnapshotVersionHistoryVO vo = new SnapshotVersionHistoryVO();
        vo.setSnapshotId(snapshot.getId());
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
