package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.vo.IssueBatchHandleResultVO;
import com.dataocean.module.governance.entity.vo.QualityIssueVO;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.service.QualityIssueService;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.entity.SysUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元数据质量问题服务实现。
 * <p>
 * 提供质量问题筛选查询、状态流转、批量处理和负责人分派能力。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityIssueServiceImpl implements QualityIssueService {

    private final MetadataQualityIssueMapper issueMapper;
    private final UserMapper userMapper;
    private final DatasourceMapper datasourceMapper;
    // Phase 1 #6: 治理-置信度联动
    private final ConfidenceCalculator confidenceCalculator;

    // 合法的状态流转（安全优先：REOPENED 必须经过 CONFIRMED 才能 RESOLVED）
    private static final Set<String> VALID_FROM_OPEN = Set.of(
            MetadataQualityIssue.STATUS_CONFIRMED, MetadataQualityIssue.STATUS_REJECTED);
    private static final Set<String> VALID_FROM_CONFIRMED = Set.of(
            MetadataQualityIssue.STATUS_RESOLVED, MetadataQualityIssue.STATUS_REJECTED);
    private static final Set<String> VALID_FROM_RESOLVED = Set.of(
            MetadataQualityIssue.STATUS_REOPENED);
    private static final Set<String> VALID_FROM_REJECTED = Set.of(
            MetadataQualityIssue.STATUS_REOPENED);
    private static final Set<String> VALID_FROM_REOPENED = Set.of(
            MetadataQualityIssue.STATUS_CONFIRMED, MetadataQualityIssue.STATUS_REJECTED);

    /**
     * {@inheritDoc}
     */
    @Transactional(readOnly = true)
    @Override
    public Page<QualityIssueVO> listIssues(Long snapshotId, String dimension, String severity,
                                           String status, String tableName, Long assigneeId,
                                           int page, int size) {
        LambdaQueryWrapper<MetadataQualityIssue> qw = new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(snapshotId != null, MetadataQualityIssue::getSnapshotId, snapshotId)
                .eq(StringUtils.hasText(dimension), MetadataQualityIssue::getDimension, dimension)
                .eq(StringUtils.hasText(severity), MetadataQualityIssue::getSeverity, severity)
                .eq(StringUtils.hasText(status), MetadataQualityIssue::getStatus, status)
                .eq(StringUtils.hasText(tableName), MetadataQualityIssue::getTableName, tableName)
                .eq(assigneeId != null, MetadataQualityIssue::getAssigneeId, assigneeId)
                .orderByDesc(MetadataQualityIssue::getCreatedAt);

        Page<MetadataQualityIssue> issuePage = issueMapper.selectPage(new Page<>(page, size), qw);

        // 批量查询 assignee 姓名，避免 N+1
        Set<Long> assigneeIds = issuePage.getRecords().stream()
                .map(MetadataQualityIssue::getAssigneeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> nameMap = new HashMap<>();
        if (!assigneeIds.isEmpty()) {
            List<SysUser> users = userMapper.selectByIds(assigneeIds);
            for (SysUser u : users) {
                nameMap.put(u.getId(), u.getRealName());
            }
        }

        Set<Long> datasourceIds = issuePage.getRecords().stream()
                .map(MetadataQualityIssue::getDatasourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> datasourceNameMap = new HashMap<>();
        if (!datasourceIds.isEmpty()) {
            List<Datasource> datasources = datasourceMapper.selectByIds(datasourceIds);
            for (Datasource datasource : datasources) {
                datasourceNameMap.put(datasource.getId(), datasource.getName());
            }
        }

        Page<QualityIssueVO> voPage = new Page<>(issuePage.getCurrent(), issuePage.getSize(), issuePage.getTotal());
        voPage.setRecords(issuePage.getRecords().stream().map(issue -> toVO(issue, nameMap, datasourceNameMap)).toList());
        return voPage;
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void handleIssue(Long issueId, String targetStatus, String resolutionNote, Long operatorId) {
        MetadataQualityIssue issue = issueMapper.selectById(issueId);
        if (issue == null) {
            throw new BusinessException(404, "问题不存在");
        }

        validateTransition(issue.getStatus(), targetStatus);

        String oldStatus = issue.getStatus();
        issue.setStatus(targetStatus);
        if (MetadataQualityIssue.STATUS_RESOLVED.equals(targetStatus)
                || MetadataQualityIssue.STATUS_REJECTED.equals(targetStatus)) {
            issue.setResolvedBy(operatorId);
            issue.setResolvedAt(LocalDateTime.now());
            issue.setResolutionNote(resolutionNote);
        }
        issueMapper.updateById(issue);
        log.info("问题状态变更 issueId={} {} → {}", issueId, oldStatus, targetStatus);

        // Phase 1 #6: 治理 Issue 状态变更时联动字段置信度
        adjustConfidenceForStatusChange(issue, targetStatus, operatorId);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public IssueBatchHandleResultVO batchHandle(List<Long> issueIds, String targetStatus, Long operatorId) {
        int updated = 0;
        List<IssueBatchHandleResultVO.SkippedIssue> skippedIssues = new ArrayList<>();
        for (Long issueId : issueIds) {
            try {
                handleIssue(issueId, targetStatus, null, operatorId);
                updated++;
            } catch (BusinessException e) {
                // 状态不允许流转是正常结果而非异常：记录下来返回给调用方，
                // 由它如实呈现「N 条成功、M 条被跳过及原因」。
                log.warn("批量处理跳过 issueId={}: {}", issueId, e.getMessage());
                skippedIssues.add(new IssueBatchHandleResultVO.SkippedIssue(issueId, e.getMessage()));
            }
        }
        return IssueBatchHandleResultVO.builder()
                .updated(updated)
                .skipped(skippedIssues.size())
                .skippedIssues(skippedIssues)
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void assignIssue(Long issueId, Long assigneeId) {
        MetadataQualityIssue issue = issueMapper.selectById(issueId);
        if (issue == null) {
            throw new BusinessException(404, "问题不存在");
        }
        issue.setAssigneeId(assigneeId);
        issueMapper.updateById(issue);
        log.info("问题分派 issueId={} assigneeId={}", issueId, assigneeId);
    }

    /**
     * Phase 1 #6: 治理 Issue 状态变更 → 字段置信度联动。
     * <p>
     * columnMetaId 在 issue 创建时（质量检查批处理）预存，此处 O(1) 直接读取。
     * CONFIRMED → -5 分扣减，RESOLVED → +3 分恢复。
     * </p>
     */
    private void adjustConfidenceForStatusChange(MetadataQualityIssue issue,
                                                  String targetStatus, Long operatorId) {
        Long columnMetaId = issue.getColumnMetaId();
        if (columnMetaId == null) {
            return; // 无关联列的 issue（如数据库级 issue）跳过
        }
        String eventType = null;
        if (MetadataQualityIssue.STATUS_CONFIRMED.equals(targetStatus)) {
            eventType = FieldConfidenceEvent.TYPE_GOVERNANCE_ISSUE_CONFIRMED;
        } else if (MetadataQualityIssue.STATUS_RESOLVED.equals(targetStatus)) {
            eventType = FieldConfidenceEvent.TYPE_GOVERNANCE_ISSUE_RESOLVED;
        }
        if (eventType != null) {
            try {
                confidenceCalculator.adjustScore(columnMetaId, eventType, operatorId, null);
                log.info("置信度联动 issueId={} columnMetaId={} eventType={}", issue.getId(), columnMetaId, eventType);
            } catch (Exception e) {
                log.warn("置信度联动失败 issueId={} columnMetaId={} eventType={}", issue.getId(), columnMetaId, eventType, e);
            }
        }
    }

    /**
     * 校验状态流转合法性
     * <p>
     * 合法状态机：
     * OPEN → CONFIRMED / REJECTED
     * CONFIRMED → RESOLVED / REJECTED
     * RESOLVED → REOPENED
     * REJECTED → REOPENED
     * REOPENED → CONFIRMED / REJECTED（必须经过 CONFIRMED 才能再次 RESOLVED）
     * </p>
     */
    private void validateTransition(String currentStatus, String targetStatus) {
        boolean valid = switch (currentStatus) {
            case MetadataQualityIssue.STATUS_OPEN -> VALID_FROM_OPEN.contains(targetStatus);
            case MetadataQualityIssue.STATUS_CONFIRMED -> VALID_FROM_CONFIRMED.contains(targetStatus);
            case MetadataQualityIssue.STATUS_RESOLVED -> VALID_FROM_RESOLVED.contains(targetStatus);
            case MetadataQualityIssue.STATUS_REJECTED -> VALID_FROM_REJECTED.contains(targetStatus);
            case MetadataQualityIssue.STATUS_REOPENED -> VALID_FROM_REOPENED.contains(targetStatus);
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(400,
                    String.format("不允许从 %s 转换到 %s", currentStatus, targetStatus));
        }
    }

    private QualityIssueVO toVO(MetadataQualityIssue issue, Map<Long, String> nameMap, Map<Long, String> datasourceNameMap) {
        QualityIssueVO vo = new QualityIssueVO();
        vo.setId(issue.getId());
        vo.setSnapshotId(issue.getSnapshotId());
        vo.setDatasourceId(issue.getDatasourceId());
        if (issue.getDatasourceId() != null) {
            vo.setDatasourceName(datasourceNameMap.getOrDefault(issue.getDatasourceId(), null));
        }
        vo.setDimension(issue.getDimension());
        vo.setSeverity(issue.getSeverity());
        vo.setTableName(issue.getTableName());
        vo.setColumnName(issue.getColumnName());
        vo.setIssueDescription(issue.getIssueDescription());
        vo.setSuggestion(issue.getSuggestion());
        vo.setStatus(issue.getStatus());
        vo.setAssigneeId(issue.getAssigneeId());
        vo.setCreatedAt(issue.getCreatedAt());
        vo.setResolvedAt(issue.getResolvedAt());
        if (issue.getAssigneeId() != null) {
            vo.setAssigneeName(nameMap.getOrDefault(issue.getAssigneeId(), null));
        }
        return vo;
    }
}
