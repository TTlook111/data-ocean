package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1DataGrantColumn;
import com.dataocean.module.permission.s1.entity.IamS1DepartmentNode;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1RowCondition;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserIdentity;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantColumnDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantSaveDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1RowConditionDTO;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantColumnMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DepartmentMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RowConditionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataGrantService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.support.IamS1MetadataValidationService;
import com.dataocean.module.permission.s1.support.IamS1RowConditionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** IAM-SIMPLE-1 数据授权写服务。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1DataGrantServiceImpl implements IamS1DataGrantService {

    private final IamS1DataGrantMapper dataGrantMapper;
    private final IamS1DataGrantColumnMapper dataGrantColumnMapper;
    private final IamS1RowConditionMapper rowConditionMapper;
    private final IamS1UserIdentityMapper userIdentityMapper;
    private final IamS1UserRoleMapper userRoleMapper;
    private final IamS1RoleMapper roleMapper;
    private final com.dataocean.module.permission.s1.mapper.IamS1DepartmentMapper departmentMapper;
    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;
    private final IamS1MetadataValidationService metadataValidationService;
    private final IamS1PermissionCacheService permissionCacheService;

    @Override
    @Transactional
    public Long createGrant(Long operatorUserId, IamS1DataGrantSaveDTO request) {
        Long targetId = null;
        try {
            IamS1DataGrant grant = buildGrant(operatorUserId, request, null);
            targetId = grant.getId();
            grant.setRevisionNo(0L);
            try {
                dataGrantMapper.insert(grant);
            } catch (DuplicateKeyException exception) {
                throw new BusinessException("S1 数据授权写入冲突，请刷新后重试");
            }
            targetId = grant.getId();
            if (targetId == null) {
                throw new BusinessException("S1 数据授权写入失败");
            }
            Long revisionNo = revisionService.record("DATA_GRANT", targetId, "CREATE", operatorUserId,
                    safeReason(request == null ? null : request.getReason()));
            grant.setRevisionNo(revisionNo);
            dataGrantMapper.updateById(grant);
            saveChildren(grant, request);
            permissionCacheService.invalidateAfterCommit(grant.getDatasourceId());
            auditEventService.recordSuccess("DATA_GRANT_CREATED", operatorUserId, "DATA_GRANT", grant.getId(),
                    null, grantSummary(grant, revisionNo), safeReason(request.getReason()), UUID.randomUUID().toString());
            return grant.getId();
        } catch (RuntimeException exception) {
            recordFailureSafely("DATA_GRANT_CREATE_FAILED", operatorUserId, targetId,
                    request == null ? null : request.getReason(), exception);
            throw exception;
        }
    }

    @Override
    @Transactional
    public void createGrants(Long operatorUserId, Collection<IamS1DataGrantSaveDTO> requests, String reason) {
        try {
            if (requests == null || requests.isEmpty()) {
                throw new BusinessException("批量数据授权不能为空");
            }
            for (IamS1DataGrantSaveDTO request : requests) {
                if (request != null && (request.getReason() == null || request.getReason().isBlank())) {
                    request.setReason(reason);
                }
                createGrantInCurrentTransaction(operatorUserId, request);
            }
        } catch (RuntimeException exception) {
            recordFailureSafely("DATA_GRANT_BATCH_CREATE_FAILED", operatorUserId, null, reason, exception);
            throw exception;
        }
    }

    private void createGrantInCurrentTransaction(Long operatorUserId, IamS1DataGrantSaveDTO request) {
        IamS1DataGrant grant = buildGrant(operatorUserId, request, null);
        grant.setRevisionNo(0L);
        dataGrantMapper.insert(grant);
        if (grant.getId() == null) {
            throw new BusinessException("S1 数据授权写入失败");
        }
        Long revisionNo = revisionService.record("DATA_GRANT", grant.getId(), "CREATE", operatorUserId,
                safeReason(request == null ? null : request.getReason()));
        grant.setRevisionNo(revisionNo);
        dataGrantMapper.updateById(grant);
        saveChildren(grant, request);
        permissionCacheService.invalidateAfterCommit(grant.getDatasourceId());
        auditEventService.recordSuccess("DATA_GRANT_CREATED", operatorUserId, "DATA_GRANT", grant.getId(),
                null, grantSummary(grant, revisionNo), safeReason(request.getReason()), UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public void updateGrant(Long operatorUserId, Long grantId, IamS1DataGrantSaveDTO request) {
        try {
            IamS1DataGrant current = requireGrant(grantId);
            ensureOperator(operatorUserId, current.getDatasourceId());
            ensureNotSelfTarget(operatorUserId, current.getSubjectType(), current.getSubjectId(),
                    current.getDepartmentScope());
            IamS1DataGrant replacement = buildGrant(operatorUserId, request, current);
            if (!current.getDatasourceId().equals(replacement.getDatasourceId())) {
                ensureOperator(operatorUserId, replacement.getDatasourceId());
            }
            Long revisionNo = revisionService.record("DATA_GRANT", grantId, "UPDATE", operatorUserId,
                    safeReason(request.getReason()));
            replacement.setId(grantId);
            replacement.setRevisionNo(revisionNo);
            replacement.setCreatedBy(current.getCreatedBy());
            replacement.setCreatedAt(current.getCreatedAt());
            dataGrantMapper.updateById(replacement);
            dataGrantColumnMapper.deleteByGrantId(grantId);
            rowConditionMapper.deleteByGrantId(grantId);
            saveChildren(replacement, request);
            permissionCacheService.invalidateAfterCommit(current.getDatasourceId());
            if (!current.getDatasourceId().equals(replacement.getDatasourceId())) {
                permissionCacheService.invalidateAfterCommit(replacement.getDatasourceId());
            }
            auditEventService.recordSuccess("DATA_GRANT_UPDATED", operatorUserId, "DATA_GRANT", grantId,
                    null, grantSummary(replacement, revisionNo), safeReason(request.getReason()), UUID.randomUUID().toString());
        } catch (RuntimeException exception) {
            recordFailureSafely("DATA_GRANT_UPDATE_FAILED", operatorUserId, grantId,
                    request == null ? null : request.getReason(), exception);
            throw exception;
        }
    }

    @Override
    @Transactional
    public void revokeGrant(Long operatorUserId, Long grantId, String reason) {
        try {
            IamS1DataGrant grant = requireGrant(grantId);
            ensureOperator(operatorUserId, grant.getDatasourceId());
            ensureNotSelfTarget(operatorUserId, grant.getSubjectType(), grant.getSubjectId(),
                    grant.getDepartmentScope());
            Long revisionNo = revisionService.record("DATA_GRANT", grantId, "REVOKE", operatorUserId,
                    safeReason(reason));
            grant.setStatus(IamS1Constants.DATA_GRANT_STATUS_REVOKED);
            grant.setRevisionNo(revisionNo);
            grant.setUpdatedBy(operatorUserId);
            dataGrantMapper.updateById(grant);
            permissionCacheService.invalidateAfterCommit(grant.getDatasourceId());
            auditEventService.recordSuccess("DATA_GRANT_REVOKED", operatorUserId, "DATA_GRANT", grantId,
                    null, grantSummary(grant, revisionNo), safeReason(reason), UUID.randomUUID().toString());
        } catch (RuntimeException exception) {
            recordFailureSafely("DATA_GRANT_REVOKE_FAILED", operatorUserId, grantId, reason, exception);
            throw exception;
        }
    }

    private IamS1DataGrant buildGrant(Long operatorUserId, IamS1DataGrantSaveDTO request,
                                      IamS1DataGrant current) {
        validateEnvelope(request);
        String subjectType = upper(request.getSubjectType());
        String effect = upper(request.getEffect());
        String resourceScope = upper(request.getResourceScope());
        validateSubject(subjectType, request.getSubjectId());
        ensureOperator(operatorUserId, request.getDatasourceId());
        ensureNotSelfTarget(operatorUserId, subjectType, request.getSubjectId(), request.getDepartmentScope());
        validateDepartmentScope(subjectType, request.getDepartmentScope());
        validateResource(request, resourceScope, effect);

        IamS1DataGrant grant = new IamS1DataGrant();
        grant.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        grant.setSubjectType(subjectType);
        grant.setSubjectId(request.getSubjectId());
        grant.setDepartmentScope(normalizeNullable(request.getDepartmentScope()));
        grant.setDatasourceId(request.getDatasourceId());
        grant.setResourceScope(resourceScope);
        grant.setMetadataSnapshotId(request.getMetadataSnapshotId());
        grant.setTableName(normalizeNullable(request.getTableName()));
        grant.setEffect(effect);
        grant.setGrantSource(normalizeGrantSource(request.getGrantSource()));
        grant.setSourceReferenceId(request.getSourceReferenceId());
        grant.setValidFrom(request.getValidFrom() == null ? LocalDateTime.now() : request.getValidFrom());
        grant.setValidUntil(request.getValidUntil());
        String status = request.getStatus() == null || request.getStatus().isBlank()
                ? IamS1Constants.DATA_GRANT_STATUS_ACTIVE : upper(request.getStatus());
        if (!Set.of(IamS1Constants.DATA_GRANT_STATUS_ACTIVE,
                IamS1Constants.DATA_GRANT_STATUS_REVOKED).contains(status)) {
            throw new BusinessException("S1 数据授权状态不合法");
        }
        grant.setStatus(status);
        grant.setCreatedBy(operatorUserId);
        grant.setUpdatedBy(operatorUserId);
        validateValidity(grant.getValidFrom(), grant.getValidUntil());
        if (current != null) {
            grant.setCreatedBy(current.getCreatedBy());
            grant.setCreatedAt(current.getCreatedAt());
        }
        return grant;
    }

    private void saveChildren(IamS1DataGrant grant, IamS1DataGrantSaveDTO request) {
        if (request == null) {
            throw new BusinessException("授权请求不能为空");
        }
        List<IamS1DataGrantColumnDTO> columns = request.getColumns() == null ? List.of() : request.getColumns();
        List<IamS1RowConditionDTO> conditions = request.getRowConditions() == null ? List.of() : request.getRowConditions();
        if (IamS1Constants.RESOURCE_SCOPE_DATASOURCE.equals(grant.getResourceScope())) {
            if (!columns.isEmpty() || !conditions.isEmpty()) {
                throw new BusinessException("数据源级 DENY 不允许附加字段或记录条件");
            }
            return;
        }
        Map<String, IamS1ColumnFact> selected = new LinkedHashMap<>();
        if (!columns.isEmpty()) {
            Set<String> names = new LinkedHashSet<>();
            for (IamS1DataGrantColumnDTO column : columns) {
                if (column == null || column.getColumnName() == null || !names.add(column.getColumnName().trim())) {
                    throw new BusinessException("授权字段不能为空且不能重复");
                }
            }
            selected.putAll(metadataValidationService.requireColumns(grant.getDatasourceId(),
                    grant.getMetadataSnapshotId(), grant.getTableName(), names));
        }
        if (IamS1Constants.EFFECT_ALLOW.equals(grant.getEffect()) && selected.isEmpty()) {
            throw new BusinessException("ALLOW 必须保存非空明确字段列表，不能解释为全部字段");
        }
        for (IamS1DataGrantColumnDTO column : columns) {
            IamS1ColumnFact fact = selected.get(column.getColumnName().trim());
            if (fact == null || !column.getColumnMetaId().equals(fact.getId())) {
                throw new BusinessException("授权字段标识与已发布快照不匹配");
            }
            IamS1DataGrantColumn entity = new IamS1DataGrantColumn();
            entity.setGrantId(grant.getId());
            entity.setMetadataSnapshotId(grant.getMetadataSnapshotId());
            entity.setColumnMetaId(fact.getId());
            entity.setTableName(grant.getTableName());
            entity.setColumnName(fact.getColumnName());
            dataGrantColumnMapper.insert(entity);
        }
        if (IamS1Constants.EFFECT_DENY.equals(grant.getEffect()) && !conditions.isEmpty()) {
            throw new BusinessException("DENY 不支持记录级条件");
        }
        if (conditions.isEmpty()) {
            return;
        }
        String matchType = upper(request.getRowMatchType());
        if (matchType == null) {
            matchType = IamS1Constants.ROW_MATCH_ALL;
        }
        if (!Set.of(IamS1Constants.ROW_MATCH_ALL, IamS1Constants.ROW_MATCH_ANY).contains(matchType)) {
            throw new BusinessException("记录条件组合只支持 ALL 或 ANY");
        }
        for (int i = 0; i < conditions.size(); i++) {
            IamS1RowConditionDTO condition = conditions.get(i);
            if (condition == null) {
                throw new BusinessException("记录条件字段不能为空");
            }
            IamS1ColumnFact fact = metadataValidationService.requireColumn(grant.getDatasourceId(),
                    grant.getMetadataSnapshotId(), grant.getTableName(), condition.getColumnMetaId(), condition.getColumnName());
            ensureConditionFieldNotProtected(grant, fact);
            String canonicalValue = IamS1RowConditionValidator.validateAndCanonicalize(condition, fact.getDataType());
            IamS1RowCondition entity = new IamS1RowCondition();
            entity.setGrantId(grant.getId());
            entity.setMetadataSnapshotId(grant.getMetadataSnapshotId());
            entity.setTableName(grant.getTableName());
            entity.setMatchType(matchType);
            entity.setSequenceNo(i + 1);
            entity.setColumnMetaId(fact.getId());
            entity.setColumnName(fact.getColumnName());
            entity.setOperatorCode(upper(condition.getOperatorCode()));
            entity.setValueType(upper(condition.getValueType()));
            entity.setStructuredValueJson(canonicalValue);
            entity.setParameterReference(normalizeNullable(condition.getParameterReference()));
            rowConditionMapper.insert(entity);
        }
    }

    private void ensureConditionFieldNotProtected(IamS1DataGrant grant, IamS1ColumnFact fact) {
        List<IamS1FieldProtection> rules = fieldProtectionMapper.selectActiveBySnapshot(
                IamS1Constants.PROTOCOL_VERSION, grant.getDatasourceId(), grant.getMetadataSnapshotId());
        if (rules == null) {
            throw new BusinessException("字段保护事实读取失败");
        }
        for (IamS1FieldProtection rule : rules) {
            if (fact.getId().equals(rule.getColumnMetaId())
                    && (IamS1Constants.PROTECTION_HIDDEN.equals(rule.getProtectionLevel())
                    || IamS1Constants.PROTECTION_MASKED.equals(rule.getProtectionLevel()))) {
                throw new BusinessException("隐藏或脱敏字段不能作为记录条件");
            }
        }
    }

    private void validateResource(IamS1DataGrantSaveDTO request, String resourceScope, String effect) {
        List<IamS1DataGrantColumnDTO> columns = request.getColumns() == null ? List.of() : request.getColumns();
        List<IamS1RowConditionDTO> conditions = request.getRowConditions() == null ? List.of() : request.getRowConditions();
        if (IamS1Constants.RESOURCE_SCOPE_DATASOURCE.equals(resourceScope)) {
            if (!IamS1Constants.EFFECT_DENY.equals(effect) || request.getTableName() != null
                    || request.getMetadataSnapshotId() != null || !columns.isEmpty() || !conditions.isEmpty()) {
                throw new BusinessException("数据源级授权只允许无字段、无条件的 DENY");
            }
            return;
        }
        if (!IamS1Constants.RESOURCE_SCOPE_TABLE.equals(resourceScope)
                || request.getTableName() == null || request.getTableName().isBlank()
                || request.getMetadataSnapshotId() == null) {
            throw new BusinessException("表级授权必须绑定表和已发布元数据快照");
        }
        metadataValidationService.requireQueryableTable(request.getDatasourceId(), request.getMetadataSnapshotId(),
                request.getTableName());
        if (IamS1Constants.EFFECT_ALLOW.equals(effect) && columns.isEmpty()) {
            throw new BusinessException("ALLOW 必须选择至少一个明确字段");
        }
        if (IamS1Constants.EFFECT_DENY.equals(effect) && !conditions.isEmpty()) {
            throw new BusinessException("DENY 不支持记录级条件");
        }
    }

    private void validateSubject(String subjectType, Long subjectId) {
        if (subjectType == null || subjectId == null
                || !Set.of(IamS1Constants.SUBJECT_USER, IamS1Constants.SUBJECT_ROLE,
                IamS1Constants.SUBJECT_DEPARTMENT).contains(subjectType)) {
            throw new BusinessException("授权主体类型或 ID 不合法");
        }
        switch (subjectType) {
            case IamS1Constants.SUBJECT_USER -> {
                if (userIdentityMapper.countExistingUser(subjectId) != 1) {
                    throw new BusinessException("授权用户不存在");
                }
            }
            case IamS1Constants.SUBJECT_ROLE -> {
                IamS1Role role = roleMapper.selectById(subjectId);
                if (role == null) {
                    throw new BusinessException("S1 授权角色不存在");
                }
            }
            case IamS1Constants.SUBJECT_DEPARTMENT -> {
                if (departmentMapper.selectById(subjectId) == null) {
                    throw new BusinessException("授权部门不存在");
                }
            }
            default -> throw new BusinessException("授权主体类型不合法");
        }
    }

    private void ensureOperator(Long operatorUserId, Long datasourceId) {
        if (operatorUserId == null || datasourceId == null) {
            throw new BusinessException("操作者和数据源不能为空");
        }
        var decision = authorizationResolver.resolveAdminAction(operatorUserId,
                "security:permission:manage", datasourceId);
        if (!decision.isAllowed()) {
            throw new BusinessException("没有维护该数据源 S1 授权的权限");
        }
    }

    private void ensureNotSelfTarget(Long operatorUserId, String subjectType, Long subjectId,
                                     String departmentScope) {
        if (operatorUserId == null || subjectType == null || subjectId == null) {
            throw new BusinessException("不能确认授权主体");
        }
        if (IamS1Constants.SUBJECT_USER.equals(subjectType) && operatorUserId.equals(subjectId)) {
            throw new BusinessException("不能修改本人命中的 S1 数据授权");
        }
        if (IamS1Constants.SUBJECT_ROLE.equals(subjectType)
                && userRoleMapper.countActiveByUserAndRole(operatorUserId, subjectId) > 0) {
            throw new BusinessException("不能修改本人所属角色命中的 S1 数据授权");
        }
        if (IamS1Constants.SUBJECT_DEPARTMENT.equals(subjectType)) {
            IamS1UserIdentity operator = userIdentityMapper.selectIdentity(operatorUserId);
            if (operator == null || operator.getDepartmentId() == null) {
                return;
            }
            Set<Long> path = departmentPath(operator.getDepartmentId());
            boolean matches = subjectId.equals(operator.getDepartmentId())
                    || IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS.equals(departmentScope)
                    && path.contains(subjectId);
            if (matches) {
                throw new BusinessException("不能修改本人当前部门命中的 S1 数据授权");
            }
        }
    }

    private Set<Long> departmentPath(Long currentDepartmentId) {
        List<IamS1DepartmentNode> nodes = departmentMapper.selectAll();
        if (nodes == null) {
            throw new BusinessException("无法确认本人部门路径");
        }
        Map<Long, IamS1DepartmentNode> byId = nodes.stream().filter(item -> item != null && item.getId() != null)
                .collect(java.util.stream.Collectors.toMap(IamS1DepartmentNode::getId, item -> item, (left, right) -> left));
        Set<Long> path = new LinkedHashSet<>();
        Long cursor = currentDepartmentId;
        while (cursor != null && cursor != 0L) {
            if (!path.add(cursor)) {
                throw new BusinessException("无法确认本人部门路径");
            }
            IamS1DepartmentNode node = byId.get(cursor);
            if (node == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(node.getStatus())) {
                throw new BusinessException("无法确认本人部门路径");
            }
            cursor = node.getParentId();
        }
        return path;
    }

    private IamS1DataGrant requireGrant(Long grantId) {
        if (grantId == null) {
            throw new BusinessException("S1 数据授权 ID 不能为空");
        }
        IamS1DataGrant grant = dataGrantMapper.selectById(grantId);
        if (grant == null || !IamS1Constants.PROTOCOL_VERSION.equals(grant.getProtocolVersion())) {
            throw new BusinessException("S1 数据授权不存在");
        }
        return grant;
    }

    private void validateEnvelope(IamS1DataGrantSaveDTO request) {
        if (request == null || !IamS1Constants.PROTOCOL_VERSION.equals(request.getProtocolVersion())
                || request.getDatasourceId() == null || request.getSubjectId() == null) {
            throw new BusinessException("S1 数据授权协议、主体和数据源不能为空或未知");
        }
        if (request.getEffect() == null || request.getResourceScope() == null) {
            throw new BusinessException("S1 数据授权效果和资源范围不能为空");
        }
        if (!Set.of(IamS1Constants.EFFECT_ALLOW, IamS1Constants.EFFECT_DENY)
                .contains(upper(request.getEffect()))) {
            throw new BusinessException("S1 数据授权效果只支持 ALLOW 或 DENY");
        }
    }

    private void validateDepartmentScope(String subjectType, String scope) {
        if (IamS1Constants.SUBJECT_DEPARTMENT.equals(subjectType)) {
            if (!Set.of(IamS1Constants.DEPARTMENT_SCOPE_SELF,
                    IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS).contains(upper(scope))) {
                throw new BusinessException("部门授权必须选择 SELF 或 INCLUDE_DESCENDANTS");
            }
        } else if (scope != null && !scope.isBlank()) {
            throw new BusinessException("用户或角色授权不能携带部门继承范围");
        }
    }

    private void validateValidity(LocalDateTime validFrom, LocalDateTime validUntil) {
        if (validFrom == null || (validUntil != null && !validUntil.isAfter(validFrom))) {
            throw new BusinessException("授权有效期不合法");
        }
    }

    private String normalizeGrantSource(String source) {
        String normalized = source == null || source.isBlank() ? IamS1Constants.GRANT_SOURCE_MANUAL : upper(source);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{0,29}")) {
            throw new BusinessException("授权来源不合法");
        }
        return normalized;
    }

    private String grantSummary(IamS1DataGrant grant, Long revisionNo) {
        return "grantId=" + grant.getId() + ";subjectType=" + grant.getSubjectType()
                + ";resourceScope=" + grant.getResourceScope() + ";effect=" + grant.getEffect()
                + ";revision=" + revisionNo;
    }

    private String safeReason(String reason) {
        return reason == null ? null : reason.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private void recordFailureSafely(String eventType, Long operatorId, Long targetId,
                                     String reason, RuntimeException exception) {
        try {
            auditEventService.recordFailure(eventType, operatorId, "DATA_GRANT", targetId,
                    safeReason(reason), "B2_DATA_GRANT_WRITE_REJECTED", UUID.randomUUID().toString());
        } catch (RuntimeException auditException) {
            log.error("S1 数据授权失败审计写入失败 targetId={}", targetId, auditException);
        }
    }
}
