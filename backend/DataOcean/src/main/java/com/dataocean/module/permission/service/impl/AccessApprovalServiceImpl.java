package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.permission.entity.AccessApprovalRequest;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.entity.PermissionChangeLog;
import com.dataocean.module.permission.event.PermissionChangedEvent;
import com.dataocean.module.permission.mapper.AccessApprovalRequestMapper;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.permission.mapper.PermissionChangeLogMapper;
import com.dataocean.module.permission.service.AccessApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据访问审批服务实现
 * <p>
 * 流程：用户申请 → 管理员审批 → 通过生成临时 ALLOW 策略（有有效期）→ 过期自动清理。
 * 安全约束：不能绕过系统级 DENY/BLOCKED/DEPRECATED。
 * </p>
 *
 * @author dataocean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessApprovalServiceImpl extends ServiceImpl<AccessApprovalRequestMapper, AccessApprovalRequest>
        implements AccessApprovalService {

    private final DatasourceAccessPolicyMapper policyMapper;
    private final PermissionChangeLogMapper changeLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final SchemaSnapshotService schemaSnapshotService;

    @Transactional
    @Override
    public Long submitRequest(AccessApprovalRequest request) {
        // 校验必填字段
        if (request.getRequesterId() == null || request.getDatasourceId() == null) {
            throw new BusinessException("申请人和数据源不能为空");
        }
        if (request.getTableName() == null || request.getTableName().isBlank()) {
            throw new BusinessException("表名不能为空");
        }
        if (request.getRequestReason() == null || request.getRequestReason().isBlank()) {
            throw new BusinessException("申请理由不能为空");
        }

        // 安全检查：不允许对 BLOCKED/DEPRECATED 的表/列申请访问
        checkGovernanceSafety(request.getDatasourceId(), request.getTableName(), request.getColumnName());

        // 设置默认值
        if (request.getStatus() == null) {
            request.setStatus(AccessApprovalRequest.STATUS_PENDING);
        }
        if (request.getRequestedDuration() == null || request.getRequestedDuration() <= 0) {
            request.setRequestedDuration(24); // 默认 24 小时
        }

        baseMapper.insert(request);
        log.info("数据访问审批请求已提交 requestId={} datasourceId={} table={} column={}",
                request.getId(), request.getDatasourceId(), request.getTableName(), request.getColumnName());
        return request.getId();
    }

    @Transactional
    @Override
    public void reviewRequest(Long requestId, Long approverId, boolean approved, String reason) {
        AccessApprovalRequest request = baseMapper.selectById(requestId);
        if (request == null) {
            throw new BusinessException("审批请求不存在");
        }
        if (!AccessApprovalRequest.STATUS_PENDING.equals(request.getStatus())) {
            throw new BusinessException("只有 PENDING 状态的请求才能审批");
        }

        request.setApproverId(approverId);
        request.setApprovedAt(LocalDateTime.now());

        if (approved) {
            // 再次安全检查：审批时重新校验治理状态（防止审批期间状态变更）
            checkGovernanceSafety(request.getDatasourceId(), request.getTableName(), request.getColumnName());

            // 计算过期时间
            LocalDateTime expiresAt = LocalDateTime.now().plusHours(request.getRequestedDuration());
            request.setExpiresAt(expiresAt);
            request.setStatus(AccessApprovalRequest.STATUS_APPROVED);

            // 生成临时 ALLOW 策略
            DatasourceAccessPolicy tempPolicy = new DatasourceAccessPolicy();
            tempPolicy.setDatasourceId(request.getDatasourceId());
            tempPolicy.setSubjectType("USER");
            tempPolicy.setSubjectId(request.getRequesterId());
            tempPolicy.setTableName(request.getTableName());
            tempPolicy.setColumnName(request.getColumnName());
            tempPolicy.setAccessType("ALLOW");
            tempPolicy.setPriority(150); // 临时策略优先级低于系统级（0-99），高于默认（200+）
            tempPolicy.setValidUntil(expiresAt);
            tempPolicy.setCreatedBy(approverId);
            policyMapper.insert(tempPolicy);

            // 记录审计日志
            PermissionChangeLog auditLog = new PermissionChangeLog();
            auditLog.setChangeType("CREATE");
            auditLog.setTargetType("POLICY");
            auditLog.setTargetId(tempPolicy.getId());
            auditLog.setSubjectType("USER");
            auditLog.setSubjectId(request.getRequesterId());
            auditLog.setDatasourceId(request.getDatasourceId());
            auditLog.setNewValue("{\"source\":\"APPROVAL\",\"requestId\":" + requestId
                    + ",\"expiresAt\":\"" + expiresAt + "\"}");
            auditLog.setOperatorId(approverId);
            auditLog.setReason("数据访问审批通过 requestId=" + requestId);
            changeLogMapper.insert(auditLog);

            // 触发权限缓存失效
            eventPublisher.publishEvent(new PermissionChangedEvent(this, request.getRequesterId(), request.getDatasourceId()));

            log.info("数据访问审批通过 requestId={} userId={} expiresAt={}", requestId, request.getRequesterId(), expiresAt);
        } else {
            request.setStatus(AccessApprovalRequest.STATUS_REJECTED);
            request.setRejectReason(reason);
            log.info("数据访问审批拒绝 requestId={} reason={}", requestId, reason);
        }

        baseMapper.updateById(request);
    }

    @Override
    public Page<AccessApprovalRequest> listRequests(Long datasourceId, String status, int page, int size) {
        LambdaQueryWrapper<AccessApprovalRequest> qw = new LambdaQueryWrapper<AccessApprovalRequest>()
                .eq(datasourceId != null, AccessApprovalRequest::getDatasourceId, datasourceId)
                .eq(status != null, AccessApprovalRequest::getStatus, status)
                .orderByDesc(AccessApprovalRequest::getCreatedAt);
        return baseMapper.selectPage(new Page<>(page, size), qw);
    }

    @Transactional
    @Override
    public int expireOverdueRequests() {
        LocalDateTime now = LocalDateTime.now();

        // 查找所有已过期但仍标记为 APPROVED 的请求
        List<AccessApprovalRequest> overdue = baseMapper.selectList(
                new LambdaQueryWrapper<AccessApprovalRequest>()
                        .eq(AccessApprovalRequest::getStatus, AccessApprovalRequest.STATUS_APPROVED)
                        .lt(AccessApprovalRequest::getExpiresAt, now));

        int count = 0;
        for (AccessApprovalRequest request : overdue) {
            request.setStatus(AccessApprovalRequest.STATUS_EXPIRED);
            baseMapper.updateById(request);

            // 删除对应的临时 ALLOW 策略
            List<DatasourceAccessPolicy> tempPolicies = policyMapper.selectList(
                    new LambdaQueryWrapper<DatasourceAccessPolicy>()
                            .eq(DatasourceAccessPolicy::getDatasourceId, request.getDatasourceId())
                            .eq(DatasourceAccessPolicy::getSubjectType, "USER")
                            .eq(DatasourceAccessPolicy::getSubjectId, request.getRequesterId())
                            .eq(DatasourceAccessPolicy::getTableName, request.getTableName())
                            .eq(DatasourceAccessPolicy::getAccessType, "ALLOW")
                            .le(DatasourceAccessPolicy::getValidUntil, now));

            for (DatasourceAccessPolicy policy : tempPolicies) {
                policyMapper.deleteById(policy.getId());
            }

            // 触发权限缓存失效
            eventPublisher.publishEvent(new PermissionChangedEvent(this, request.getRequesterId(), request.getDatasourceId()));

            count++;
            log.info("临时策略已过期 requestId={} userId={}", request.getId(), request.getRequesterId());
        }

        return count;
    }

    /**
     * 安全检查：不允许对 BLOCKED/DEPRECATED 的表/列申请访问
     * <p>
     * 系统级 DENY/BLOCKED/DEPRECATED 不能被审批绕过。
     * </p>
     */
    private void checkGovernanceSafety(Long datasourceId, String tableName, String columnName) {
        var publishedSnapshot = schemaSnapshotService.getPublishedSnapshot(datasourceId);
        if (publishedSnapshot == null) return;

        // 检查表级 BLOCKED/DEPRECATED
        if (!"*".equals(tableName)) {
            Long tableBlocked = tableMetaMapper.selectCount(
                    new LambdaQueryWrapper<DbTableMeta>()
                            .eq(DbTableMeta::getDatasourceId, datasourceId)
                            .eq(DbTableMeta::getSnapshotId, publishedSnapshot.getId())
                            .eq(DbTableMeta::getTableName, tableName)
                            .in(DbTableMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED")));
            if (tableBlocked > 0) {
                throw new BusinessException("该表处于 BLOCKED/DEPRECATED 状态，不允许申请访问");
            }
        }

        // 检查列级 BLOCKED/DEPRECATED
        if (columnName != null && !columnName.isBlank()) {
            Long columnBlocked = columnMetaMapper.selectCount(
                    new LambdaQueryWrapper<DbColumnMeta>()
                            .eq(DbColumnMeta::getDatasourceId, datasourceId)
                            .eq(DbColumnMeta::getSnapshotId, publishedSnapshot.getId())
                            .eq(DbColumnMeta::getTableName, tableName)
                            .eq(DbColumnMeta::getColumnName, columnName)
                            .in(DbColumnMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED")));
            if (columnBlocked > 0) {
                throw new BusinessException("该列处于 BLOCKED/DEPRECATED 状态，不允许申请访问");
            }
        }
    }
}
