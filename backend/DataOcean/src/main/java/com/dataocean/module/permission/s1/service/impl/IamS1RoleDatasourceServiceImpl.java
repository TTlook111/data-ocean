package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1RoleDatasource;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleDatasourceMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.service.IamS1RoleDatasourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** IAM-SIMPLE-1 用户角色负责源服务实现。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1RoleDatasourceServiceImpl implements IamS1RoleDatasourceService {

    private final IamS1UserRoleMapper userRoleMapper;
    private final IamS1RoleMapper roleMapper;
    private final IamS1RoleDatasourceMapper roleDatasourceMapper;
    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;

    @Override
    @Transactional
    public void bindDatasources(Long operatorUserId, Long userRoleId, Collection<Long> datasourceIds, String reason) {
        try {
            doBindDatasources(operatorUserId, userRoleId, datasourceIds, reason);
        } catch (RuntimeException exception) {
            recordFailureSafely("ROLE_DATASOURCE_BIND_FAILED", operatorUserId, userRoleId, reason, exception);
            throw exception;
        }
    }

    private void doBindDatasources(Long operatorUserId, Long userRoleId, Collection<Long> datasourceIds, String reason) {
        IamS1UserRole userRole = requireBinding(userRoleId);
        ensureOperator(operatorUserId, userRole);
        IamS1Role role = requireActiveRole(userRole.getRoleId());
        ensureOrdinaryRole(role);
        Set<Long> distinctDatasourceIds = normalizeDatasourceIds(datasourceIds);
        for (Long datasourceId : distinctDatasourceIds) {
            if (datasourceIdentityMapper.countEnabledDatasource(datasourceId) != 1) {
                throw new BusinessException("目标数据源不存在或未启用: " + datasourceId);
            }
        }

        Long revisionNo = revisionService.record("ROLE_DATASOURCE", userRoleId, "BIND", operatorUserId, reason);
        for (Long datasourceId : distinctDatasourceIds) {
            IamS1RoleDatasource relation = roleDatasourceMapper.selectOne(new LambdaQueryWrapper<IamS1RoleDatasource>()
                    .eq(IamS1RoleDatasource::getUserRoleId, userRoleId)
                    .eq(IamS1RoleDatasource::getDatasourceId, datasourceId));
            if (relation == null) {
                relation = new IamS1RoleDatasource();
                relation.setUserRoleId(userRoleId);
                relation.setDatasourceId(datasourceId);
                relation.setCreatedBy(operatorUserId);
                relation.setRevisionNo(revisionNo);
                relation.setStatus(IamS1Constants.ENABLED);
                roleDatasourceMapper.insert(relation);
            } else {
                relation.setStatus(IamS1Constants.ENABLED);
                relation.setRevisionNo(revisionNo);
                relation.setUpdatedBy(operatorUserId);
                roleDatasourceMapper.updateById(relation);
            }
        }
        auditEventService.recordSuccess("ROLE_DATASOURCE_BOUND", operatorUserId, "ROLE_DATASOURCE", userRoleId,
                null, "datasourceCount=" + distinctDatasourceIds.size() + ";revision=" + revisionNo,
                reason, UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public void removeDatasource(Long operatorUserId, Long userRoleId, Long datasourceId, String reason) {
        try {
            doRemoveDatasource(operatorUserId, userRoleId, datasourceId, reason);
        } catch (RuntimeException exception) {
            recordFailureSafely("ROLE_DATASOURCE_REMOVE_FAILED", operatorUserId, userRoleId, reason, exception);
            throw exception;
        }
    }

    private void doRemoveDatasource(Long operatorUserId, Long userRoleId, Long datasourceId, String reason) {
        IamS1UserRole userRole = requireBinding(userRoleId);
        ensureOperator(operatorUserId, userRole);
        IamS1Role role = requireActiveRole(userRole.getRoleId());
        ensureOrdinaryRole(role);
        if (datasourceId == null) {
            throw new BusinessException("负责数据源不能为空");
        }
        IamS1RoleDatasource relation = roleDatasourceMapper.selectOne(new LambdaQueryWrapper<IamS1RoleDatasource>()
                .eq(IamS1RoleDatasource::getUserRoleId, userRoleId)
                .eq(IamS1RoleDatasource::getDatasourceId, datasourceId)
                .eq(IamS1RoleDatasource::getStatus, IamS1Constants.ENABLED));
        if (relation == null) {
            throw new BusinessException("S1 负责源绑定不存在");
        }
        Long revisionNo = revisionService.record("ROLE_DATASOURCE", userRoleId, "REMOVE", operatorUserId, reason);
        relation.setStatus(IamS1Constants.DISABLED);
        relation.setRevisionNo(revisionNo);
        relation.setUpdatedBy(operatorUserId);
        roleDatasourceMapper.updateById(relation);
        auditEventService.recordSuccess("ROLE_DATASOURCE_REMOVED", operatorUserId, "ROLE_DATASOURCE", relation.getId(),
                null, "userRoleId=" + userRoleId + ";datasourceId=" + datasourceId + ";revision=" + revisionNo,
                reason, UUID.randomUUID().toString());
    }

    private IamS1UserRole requireBinding(Long userRoleId) {
        if (userRoleId == null) {
            throw new BusinessException("S1 用户角色绑定 ID 不能为空");
        }
        IamS1UserRole userRole = userRoleMapper.selectById(userRoleId);
        if (userRole == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(userRole.getStatus())) {
            throw new BusinessException("S1 用户角色绑定不存在或已禁用");
        }
        return userRole;
    }

    private void ensureOperator(Long operatorUserId, IamS1UserRole userRole) {
        if (operatorUserId == null || operatorUserId.equals(userRole.getUserId())) {
            throw new BusinessException("不能修改本人的后台负责源");
        }
        if (!authorizationResolver.isSystemAdmin(operatorUserId)) {
            throw new BusinessException("只有 S1 系统管理员可以维护后台负责源");
        }
    }

    private IamS1Role requireActiveRole(Long roleId) {
        IamS1Role role = roleMapper.selectById(roleId);
        if (role == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(role.getStatus())) {
            throw new BusinessException("S1 角色不存在或已禁用");
        }
        return role;
    }

    private void ensureOrdinaryRole(IamS1Role role) {
        if (Integer.valueOf(IamS1Constants.ENABLED).equals(role.getProtectedRole())
                || Integer.valueOf(IamS1Constants.ENABLED).equals(role.getBuiltIn())) {
            throw new BusinessException("系统管理员由受保护规则拥有全部后台负责源范围");
        }
    }

    private Set<Long> normalizeDatasourceIds(Collection<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            throw new BusinessException("至少需要一个后台负责数据源");
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long datasourceId : datasourceIds) {
            if (datasourceId == null) {
                throw new BusinessException("后台负责数据源 ID 不能为空");
            }
            distinctIds.add(datasourceId);
        }
        return distinctIds;
    }

    private void recordFailureSafely(String eventType, Long operatorUserId, Long userRoleId,
                                     String reason, RuntimeException exception) {
        try {
            auditEventService.recordFailure(eventType, operatorUserId, "ROLE_DATASOURCE", userRoleId, reason,
                    exception.getMessage(), UUID.randomUUID().toString());
        } catch (RuntimeException auditException) {
            log.error("S1 负责源失败审计写入失败 userRoleId={}", userRoleId, auditException);
        }
    }
}
