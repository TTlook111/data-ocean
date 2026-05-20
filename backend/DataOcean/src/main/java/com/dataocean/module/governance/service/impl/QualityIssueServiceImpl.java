package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityIssueServiceImpl implements QualityIssueService {

    private final MetadataQualityIssueMapper issueMapper;
    private final UserMapper userMapper;

    // 合法的状态流转
    private static final Set<String> VALID_FROM_OPEN = Set.of(
            MetadataQualityIssue.STATUS_CONFIRMED, MetadataQualityIssue.STATUS_REJECTED);
    private static final Set<String> VALID_FROM_CONFIRMED = Set.of(
            MetadataQualityIssue.STATUS_RESOLVED, MetadataQualityIssue.STATUS_REJECTED);

    @Transactional(readOnly = true)
    @Override
    public Page<QualityIssueVO> listIssues(Long snapshotId, String dimension, String severity,
                                           String status, String tableName, int page, int size) {
        LambdaQueryWrapper<MetadataQualityIssue> qw = new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getSnapshotId, snapshotId)
                .eq(StringUtils.hasText(dimension), MetadataQualityIssue::getDimension, dimension)
                .eq(StringUtils.hasText(severity), MetadataQualityIssue::getSeverity, severity)
                .eq(StringUtils.hasText(status), MetadataQualityIssue::getStatus, status)
                .eq(StringUtils.hasText(tableName), MetadataQualityIssue::getTableName, tableName)
                .orderByDesc(MetadataQualityIssue::getCreatedAt);

        Page<MetadataQualityIssue> issuePage = issueMapper.selectPage(new Page<>(page, size), qw);

        // 批量查询 assignee 姓名，避免 N+1
        Set<Long> assigneeIds = issuePage.getRecords().stream()
                .map(MetadataQualityIssue::getAssigneeId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, String> nameMap = new HashMap<>();
        if (!assigneeIds.isEmpty()) {
            List<SysUser> users = userMapper.selectBatchIds(assigneeIds);
            for (SysUser u : users) {
                nameMap.put(u.getId(), u.getRealName());
            }
        }

        Page<QualityIssueVO> voPage = new Page<>(issuePage.getCurrent(), issuePage.getSize(), issuePage.getTotal());
        voPage.setRecords(issuePage.getRecords().stream().map(i -> toVO(i, nameMap)).toList());
        return voPage;
    }

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
    }

    @Transactional
    @Override
    public int batchHandle(List<Long> issueIds, String targetStatus, Long operatorId) {
        int updated = 0;
        for (Long issueId : issueIds) {
            try {
                handleIssue(issueId, targetStatus, null, operatorId);
                updated++;
            } catch (BusinessException e) {
                log.warn("批量处理跳过 issueId={}: {}", issueId, e.getMessage());
            }
        }
        return updated;
    }

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

    private void validateTransition(String currentStatus, String targetStatus) {
        boolean valid = switch (currentStatus) {
            case MetadataQualityIssue.STATUS_OPEN -> VALID_FROM_OPEN.contains(targetStatus);
            case MetadataQualityIssue.STATUS_CONFIRMED -> VALID_FROM_CONFIRMED.contains(targetStatus);
            default -> false;
        };
        if (!valid) {
            throw new BusinessException(400,
                    String.format("不允许从 %s 转换到 %s", currentStatus, targetStatus));
        }
    }

    private QualityIssueVO toVO(MetadataQualityIssue issue, Map<Long, String> nameMap) {
        QualityIssueVO vo = new QualityIssueVO();
        vo.setId(issue.getId());
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
