package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.dto.IamS1FieldProtectionSaveDTO;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1FieldProtectionService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.support.IamS1MetadataValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/** IAM-SIMPLE-1 字段保护写服务；保护配置不产生字段查询权。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1FieldProtectionServiceImpl implements IamS1FieldProtectionService {

    private static final Set<String> MASK_POLICIES = Set.of("PHONE", "ID_CARD", "EMAIL", "BANK_CARD", "NAME");

    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;
    private final IamS1MetadataValidationService metadataValidationService;
    private final IamS1PermissionCacheService permissionCacheService;

    @Override
    @Transactional
    public Long saveProtection(Long operatorUserId, IamS1FieldProtectionSaveDTO request) {
        try {
            validateEnvelope(request);
            ensureOperator(operatorUserId, request.getDatasourceId());
            IamS1ColumnFact column = metadataValidationService.requireColumn(request.getDatasourceId(),
                    request.getMetadataSnapshotId(), request.getTableName(), request.getColumnMetaId(), request.getColumnName());
            String level = upper(request.getProtectionLevel());
            String policy = validateProtection(level, request.getMaskPolicy());
            Long revisionNo = revisionService.record("FIELD_PROTECTION", column.getId(), "SAVE", operatorUserId,
                    safeReason(request.getReason()));
            IamS1FieldProtection protection = new IamS1FieldProtection();
            protection.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
            protection.setDatasourceId(request.getDatasourceId());
            protection.setMetadataSnapshotId(request.getMetadataSnapshotId());
            protection.setTableName(column.getTableName());
            protection.setColumnMetaId(column.getId());
            protection.setColumnName(column.getColumnName());
            protection.setProtectionLevel(level);
            protection.setMaskPolicy(policy);
            String status = request.getStatus() == null || request.getStatus().isBlank()
                    ? "ACTIVE" : upper(request.getStatus());
            if (!Set.of("ACTIVE", "REVOKED").contains(status)) {
                throw new BusinessException("字段保护状态不合法");
            }
            protection.setStatus(status);
            protection.setRevisionNo(revisionNo);
            protection.setCreatedBy(operatorUserId);
            protection.setUpdatedBy(operatorUserId);
            fieldProtectionMapper.insert(protection);
            permissionCacheService.invalidateAfterCommit(request.getDatasourceId());
            auditEventService.recordSuccess("FIELD_PROTECTION_SAVED", operatorUserId, "FIELD_PROTECTION",
                    protection.getId(), null, "columnMetaId=" + column.getId() + ";level=" + level
                            + ";revision=" + revisionNo, safeReason(request.getReason()), UUID.randomUUID().toString());
            return protection.getId();
        } catch (RuntimeException exception) {
            recordFailureSafely("FIELD_PROTECTION_SAVE_FAILED", operatorUserId,
                    request == null ? null : request.getColumnMetaId(),
                    request == null ? null : request.getReason(), exception);
            throw exception;
        }
    }

    @Override
    @Transactional
    public void revokeProtection(Long operatorUserId, Long protectionId, String reason) {
        try {
            if (protectionId == null) {
                throw new BusinessException("字段保护 ID 不能为空");
            }
            IamS1FieldProtection protection = fieldProtectionMapper.selectById(protectionId);
            if (protection == null || !IamS1Constants.PROTOCOL_VERSION.equals(protection.getProtocolVersion())) {
                throw new BusinessException("S1 字段保护不存在");
            }
            ensureOperator(operatorUserId, protection.getDatasourceId());
            Long revisionNo = revisionService.record("FIELD_PROTECTION", protectionId, "REVOKE", operatorUserId,
                    safeReason(reason));
            protection.setStatus("REVOKED");
            protection.setRevisionNo(revisionNo);
            protection.setUpdatedBy(operatorUserId);
            fieldProtectionMapper.updateById(protection);
            permissionCacheService.invalidateAfterCommit(protection.getDatasourceId());
            auditEventService.recordSuccess("FIELD_PROTECTION_REVOKED", operatorUserId, "FIELD_PROTECTION",
                    protectionId, null, "revision=" + revisionNo, safeReason(reason), UUID.randomUUID().toString());
        } catch (RuntimeException exception) {
            recordFailureSafely("FIELD_PROTECTION_REVOKE_FAILED", operatorUserId, protectionId, reason, exception);
            throw exception;
        }
    }

    private void validateEnvelope(IamS1FieldProtectionSaveDTO request) {
        if (request == null || !IamS1Constants.PROTOCOL_VERSION.equals(request.getProtocolVersion())
                || request.getDatasourceId() == null || request.getMetadataSnapshotId() == null
                || request.getColumnMetaId() == null || request.getTableName() == null
                || request.getTableName().isBlank() || request.getColumnName() == null
                || request.getColumnName().isBlank()) {
            throw new BusinessException("S1 字段保护协议、数据源、快照和字段不能为空或未知");
        }
    }

    private void ensureOperator(Long operatorUserId, Long datasourceId) {
        if (datasourceIdentityMapper.countEnabledDatasource(datasourceId) != 1) {
            throw new BusinessException("目标数据源不存在或未启用");
        }
        var decision = authorizationResolver.resolveAdminAction(operatorUserId,
                "security:mask:manage", datasourceId);
        if (!decision.isAllowed()) {
            throw new BusinessException("没有维护该数据源字段保护的权限");
        }
    }

    private String validateProtection(String level, String maskPolicy) {
        if (!Set.of(IamS1Constants.PROTECTION_NORMAL, IamS1Constants.PROTECTION_HIDDEN,
                IamS1Constants.PROTECTION_MASKED).contains(level)) {
            throw new BusinessException("字段保护级别只支持 NORMAL、HIDDEN 或 MASKED");
        }
        if (IamS1Constants.PROTECTION_MASKED.equals(level)) {
            String normalized = upper(maskPolicy);
            if (!MASK_POLICIES.contains(normalized)) {
                throw new BusinessException("MASKED 必须选择安全掩码策略");
            }
            return normalized;
        }
        if (maskPolicy != null && !maskPolicy.isBlank()) {
            throw new BusinessException("NORMAL 或 HIDDEN 不能携带掩码策略");
        }
        return null;
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String safeReason(String value) {
        return value == null ? null : value.trim();
    }

    private void recordFailureSafely(String eventType, Long operatorId, Long targetId,
                                     String reason, RuntimeException exception) {
        try {
            auditEventService.recordFailure(eventType, operatorId, "FIELD_PROTECTION", targetId,
                    safeReason(reason), "B2_FIELD_PROTECTION_WRITE_REJECTED", UUID.randomUUID().toString());
        } catch (RuntimeException auditException) {
            log.error("S1 字段保护失败审计写入失败 targetId={}", targetId, auditException);
        }
    }
}
