package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.fieldtag.entity.po.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.entity.vo.IssueBatchHandleResultVO;
import com.dataocean.module.governance.entity.vo.QualityIssueVO;
import com.dataocean.module.governance.entity.vo.QualityIssueSummaryVO;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.governance.service.QualityIssueService;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.entity.po.SysUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    /** 批量处理逐项校验用的功能码，与单条处理保持同一个码。 */
    private static final String BATCH_MANAGE_FUNCTION = "governance:issue:manage";

    private final MetadataQualityIssueMapper issueMapper;
    private final UserMapper userMapper;
    private final DatasourceMapper datasourceMapper;
    /** 校验“指定快照”的真实归属：快照归属不能由调用方自己声称。 */
    private final MetadataSnapshotMapper snapshotMapper;
    // Phase 1 #6: 治理-置信度联动
    private final ConfidenceCalculator confidenceCalculator;
    private final com.dataocean.module.permission.s1.support.IamS1AdminGuard adminGuard;
    private final com.dataocean.module.permission.s1.resource.impl.GovernanceIssueResourceResolver issueResourceResolver;

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
        return queryIssues(null, snapshotId, dimension, severity, status, tableName, assigneeId, page, size);
    }

    @Override
    public Page<QualityIssueVO> listIssuesInDatasources(java.util.Collection<Long> datasourceIds, Long snapshotId,
                                                        String dimension, String severity, String status,
                                                        String tableName, Long assigneeId, int page, int size) {
        if (snapshotId != null) {
            // 显式指定快照时必须**直接判定归属**。只把 snapshotId 塞进 WHERE 是不行的：
            // 无权快照会返回一个空页，把“无权”伪装成“该快照没有问题”，既误导用户，
            // 也把无权资源变成了可探测的目标。先解析快照的真实归属，再决定放行还是拒绝。
            MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
            if (snapshot == null) {
                throw new BusinessException(404, "快照不存在");
            }
            if (snapshot.getDatasourceId() == null) {
                throw new BusinessException(409, "快照缺少数据源归属，无法判定负责范围");
            }
            if (datasourceIds == null || !datasourceIds.contains(snapshot.getDatasourceId())) {
                throw new BusinessException(403, "没有负责该快照所属的数据源，无法查看其质量问题");
            }
        }
        // 未指定快照时，空负责源直接返回空页：不能退化成“返回全部数据源”。
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return new Page<>(page, size, 0);
        }
        return queryIssues(datasourceIds, snapshotId, dimension, severity, status, tableName,
                assigneeId, page, size);
    }

    @Transactional(readOnly = true)
    @Override
    public QualityIssueSummaryVO snapshotIssueSummary(java.util.Collection<Long> datasourceIds, Long snapshotId,
                                                       int recentLimit) {
        // 先复用既有快照负责源校验；不能把无权快照伪装成零问题。
        listIssuesInDatasources(datasourceIds, snapshotId, null, null, null, null, null, 1, 1);
        long open = countSnapshotStatus(snapshotId, MetadataQualityIssue.STATUS_OPEN);
        long confirmed = countSnapshotStatus(snapshotId, MetadataQualityIssue.STATUS_CONFIRMED);
        long reopened = countSnapshotStatus(snapshotId, MetadataQualityIssue.STATUS_REOPENED);
        Page<QualityIssueVO> recent = queryIssues(datasourceIds, snapshotId,
                null, null, null, null, null, 1, Math.max(1, Math.min(recentLimit, 20)),
                java.util.Set.of(MetadataQualityIssue.STATUS_OPEN, MetadataQualityIssue.STATUS_CONFIRMED,
                        MetadataQualityIssue.STATUS_REOPENED));
        QualityIssueSummaryVO result = new QualityIssueSummaryVO();
        result.setStatusCounts(Map.of(
                MetadataQualityIssue.STATUS_OPEN, open,
                MetadataQualityIssue.STATUS_CONFIRMED, confirmed,
                MetadataQualityIssue.STATUS_REOPENED, reopened));
        result.setTotalUnresolved(open + confirmed + reopened);
        result.setRecentIssues(recent.getRecords());
        return result;
    }

    private long countSnapshotStatus(Long snapshotId, String status) {
        return issueMapper.selectCount(new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getSnapshotId, snapshotId)
                .eq(MetadataQualityIssue::getStatus, status));
    }

    /** `scopeDatasourceIds == null` 表示不按数据源限制（仅供既有内部调用），空集合由调用方先行处理。 */
    private Page<QualityIssueVO> queryIssues(java.util.Collection<Long> scopeDatasourceIds, Long snapshotId,
                                             String dimension, String severity, String status,
                                             String tableName, Long assigneeId, int page, int size) {
        return queryIssues(scopeDatasourceIds, snapshotId, dimension, severity, status,
                tableName, assigneeId, page, size, null);
    }

    private Page<QualityIssueVO> queryIssues(java.util.Collection<Long> scopeDatasourceIds, Long snapshotId,
                                             String dimension, String severity, String status,
                                             String tableName, Long assigneeId, int page, int size,
                                             java.util.Collection<String> statuses) {
        LambdaQueryWrapper<MetadataQualityIssue> qw = new LambdaQueryWrapper<MetadataQualityIssue>()
                .in(scopeDatasourceIds != null, MetadataQualityIssue::getDatasourceId, scopeDatasourceIds)
                .eq(snapshotId != null, MetadataQualityIssue::getSnapshotId, snapshotId)
                .eq(StringUtils.hasText(dimension), MetadataQualityIssue::getDimension, dimension)
                .eq(StringUtils.hasText(severity), MetadataQualityIssue::getSeverity, severity)
                .eq(StringUtils.hasText(status), MetadataQualityIssue::getStatus, status)
                .in(statuses != null && !statuses.isEmpty(), MetadataQualityIssue::getStatus, statuses)
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
        LocalDateTime handledAt = LocalDateTime.now();
        LambdaUpdateWrapper<MetadataQualityIssue> update = new LambdaUpdateWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getId, issueId)
                // 预期状态作为 SQL 条件，防止并发流转覆盖已提交状态。
                .eq(MetadataQualityIssue::getStatus, oldStatus)
                .set(MetadataQualityIssue::getStatus, targetStatus)
                .set(MetadataQualityIssue::getUpdatedAt, handledAt);
        if (MetadataQualityIssue.STATUS_RESOLVED.equals(targetStatus)
                || MetadataQualityIssue.STATUS_REJECTED.equals(targetStatus)) {
            update.set(MetadataQualityIssue::getResolvedBy, operatorId)
                    .set(MetadataQualityIssue::getResolvedAt, handledAt)
                    .set(MetadataQualityIssue::getResolutionNote, resolutionNote);
        } else {
            // These columns describe the current resolution/rejection only; do not carry them
            // through REOPENED or CONFIRMED and mistake them for an audit history.
            update.set(MetadataQualityIssue::getResolvedBy, null)
                    .set(MetadataQualityIssue::getResolvedAt, null)
                    .set(MetadataQualityIssue::getResolutionNote, null);
        }
        if (issueMapper.update(null, update) != 1) {
            throw new BusinessException(409, "问题状态已被其他操作修改，请刷新后重试");
        }
        log.info("问题状态变更 issueId={} {} → {}", issueId, oldStatus, targetStatus);

        // 问题状态是主业务结果；辅助置信度只在主事务提交后联动。
        adjustConfidenceForStatusChange(issue, targetStatus, operatorId);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public IssueBatchHandleResultVO batchHandle(List<Long> issueIds, String targetStatus, Long operatorId) {
        // 原子性的准确边界：**权限与归属预校验整批原子**——任一问题无权、不存在或归属断链就整批拒绝，
        // 不能“先改几条再发现越权”。所以校验统一前置，全部通过后才进入修改。
        // 但**状态流转本身不是全有或全无**：单条状态机不允许的流转会被跳过并在响应里如实返回，
        // 这是既有的产品语义（见 IssueBatchHandleResultVO），不是“整批业务原子”。
        List<Long> distinctIds = issueIds == null ? List.of()
                : issueIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException(400, "批量处理的问题列表不能为空");
        }
        List<MetadataQualityIssue> issues = issueMapper.selectBatchIds(distinctIds);
        if (issues == null || issues.size() != distinctIds.size()) {
            throw new BusinessException(404, "批量处理包含不存在的问题，已整批拒绝");
        }
        for (MetadataQualityIssue issue : issues) {
            IamS1ResolvedResource resolved = issueResourceResolver.resolve(issue.getId());
            adminGuard.requireDatasourceFunction(operatorId, BATCH_MANAGE_FUNCTION, resolved.datasourceId());
        }

        int updated = 0;
        List<IssueBatchHandleResultVO.SkippedIssue> skippedIssues = new ArrayList<>();
        for (Long issueId : distinctIds) {
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
        if (assigneeId == null) {
            throw new BusinessException(400, "必须指定责任人");
        }
        // 责任人必须是**存在且启用**的账号：此前直接写入 assigneeId，
        // 不存在或已禁用的用户也能被设为负责人，列表随即显示出无人可处理的“幽灵责任人”。
        SysUser assignee = userMapper.selectById(assigneeId);
        if (assignee == null) {
            throw new BusinessException(404, "责任人不存在");
        }
        if (assignee.getStatus() == null || assignee.getStatus() != SysUser.STATUS_NORMAL) {
            throw new BusinessException(400, "责任人账号未启用，不能作为负责人");
        }
        // 分派只写责任人与更新时间，避免把读取后的旧状态写回覆盖并发处理结果。
        // 被分派人不会因此获得该数据源上的任何查询、治理或授权能力。
        int updated = issueMapper.update(null, new LambdaUpdateWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getId, issueId)
                .set(MetadataQualityIssue::getAssigneeId, assigneeId)
                .set(MetadataQualityIssue::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new BusinessException(409, "问题已被其他操作修改，请刷新后重试");
        }
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
            if (!TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.isActualTransactionActive()) {
                log.warn("问题状态变更未处于事务中，跳过提交后置信度联动 issueId={} columnMetaId={} eventType={}",
                        issue.getId(), columnMetaId, eventType);
                return;
            }
            Long issueId = issue.getId();
            String scheduledEventType = eventType;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // 调用独立 Spring Bean 的 REQUIRES_NEW 方法。主问题已提交，置信度异常不会标记主事务回滚。
                        confidenceCalculator.adjustScoreAfterCommit(columnMetaId, scheduledEventType, operatorId, null);
                        log.info("置信度联动 issueId={} columnMetaId={} eventType={}", issueId, columnMetaId, scheduledEventType);
                    } catch (Exception e) {
                        log.warn("置信度联动失败 issueId={} columnMetaId={} eventType={}", issueId, columnMetaId, scheduledEventType, e);
                    }
                }
            });
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
