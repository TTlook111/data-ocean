package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.mapper.IamS1RoleDatasourceMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.service.IamS1UserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;

/** IAM-SIMPLE-1 用户角色绑定服务实现。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1UserRoleServiceImpl implements IamS1UserRoleService {

    private final IamS1UserIdentityMapper userIdentityMapper;
    private final IamS1RoleMapper roleMapper;
    private final IamS1UserRoleMapper userRoleMapper;
    private final IamS1RoleDatasourceMapper roleDatasourceMapper;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;

    @Override
    @Transactional
    public Long assignRole(Long operatorUserId, Long targetUserId, Long roleId, String reason) {
        try {
            return doAssignRole(operatorUserId, targetUserId, roleId, reason);
        } catch (RuntimeException exception) {
            recordFailureSafely("USER_ROLE_ASSIGN_FAILED", operatorUserId, targetUserId, reason, exception);
            throw exception;
        }
    }

    private Long doAssignRole(Long operatorUserId, Long targetUserId, Long roleId, String reason) {
        ensureDifferentUser(operatorUserId, targetUserId);
        ensureCanManageBindings(operatorUserId, roleId, true);
        if (targetUserId == null || userIdentityMapper.countEnabledUser(targetUserId) != 1) {
            throw new BusinessException("目标账号不存在或未启用");
        }
        IamS1Role role = requireActiveRole(roleId);
        IamS1UserRole existing = userRoleMapper.selectByUserAndRole(targetUserId, roleId);
        if (existing != null) {
            throw new BusinessException("S1 用户角色绑定已存在");
        }
        Long revisionNo = revisionService.record("USER_ROLE", targetUserId, "ASSIGN", operatorUserId, reason);
        IamS1UserRole binding = new IamS1UserRole();
        binding.setUserId(targetUserId);
        binding.setRoleId(role.getId());
        binding.setStatus(IamS1Constants.ENABLED);
        binding.setRevisionNo(revisionNo);
        binding.setCreatedBy(operatorUserId);
        binding.setUpdatedBy(operatorUserId);
        try {
            userRoleMapper.insert(binding);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("S1 用户角色绑定已存在，请刷新后重试");
        }
        auditEventService.recordSuccess("USER_ROLE_ASSIGNED", operatorUserId, "USER_ROLE", binding.getId(), null,
                "userId=" + targetUserId + ";roleId=" + roleId + ";revision=" + revisionNo,
                reason, UUID.randomUUID().toString());
        return binding.getId();
    }

    @Override
    @Transactional
    public void removeRole(Long operatorUserId, Long targetUserId, Long roleId, String reason) {
        try {
            doRemoveRole(operatorUserId, targetUserId, roleId, reason);
        } catch (RuntimeException exception) {
            recordFailureSafely("USER_ROLE_REMOVE_FAILED", operatorUserId, targetUserId, reason, exception);
            throw exception;
        }
    }

    private void doRemoveRole(Long operatorUserId, Long targetUserId, Long roleId, String reason) {
        ensureDifferentUser(operatorUserId, targetUserId);
        ensureCanManageBindings(operatorUserId, roleId, false);
        IamS1Role role = requireRole(roleId);
        IamS1UserRole binding = requireBinding(targetUserId, roleId);
        ensureCanRemoveProtectedRole(role);
        roleDatasourceMapper.deleteByUserRoleId(binding.getId());
        userRoleMapper.deleteById(binding.getId());
        Long revisionNo = revisionService.record("USER_ROLE", targetUserId, "REMOVE", operatorUserId, reason);
        auditEventService.recordSuccess("USER_ROLE_REMOVED", operatorUserId, "USER_ROLE", binding.getId(), null,
                "userId=" + targetUserId + ";roleId=" + roleId + ";revision=" + revisionNo,
                reason, UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public void disableRoleBinding(Long operatorUserId, Long targetUserId, Long roleId, String reason) {
        try {
            doDisableRoleBinding(operatorUserId, targetUserId, roleId, reason);
        } catch (RuntimeException exception) {
            recordFailureSafely("USER_ROLE_DISABLE_FAILED", operatorUserId, targetUserId, reason, exception);
            throw exception;
        }
    }

    private void doDisableRoleBinding(Long operatorUserId, Long targetUserId, Long roleId, String reason) {
        ensureDifferentUser(operatorUserId, targetUserId);
        ensureCanManageBindings(operatorUserId, roleId, false);
        IamS1Role role = requireRole(roleId);
        IamS1UserRole binding = requireBinding(targetUserId, roleId);
        ensureCanRemoveProtectedRole(role);
        binding.setStatus(IamS1Constants.DISABLED);
        binding.setUpdatedBy(operatorUserId);
        userRoleMapper.updateById(binding);
        Long revisionNo = revisionService.record("USER_ROLE", targetUserId, "DISABLE", operatorUserId, reason);
        auditEventService.recordSuccess("USER_ROLE_DISABLED", operatorUserId, "USER_ROLE", binding.getId(), null,
                "userId=" + targetUserId + ";roleId=" + roleId + ";revision=" + revisionNo,
                reason, UUID.randomUUID().toString());
    }

    private void ensureDifferentUser(Long operatorUserId, Long targetUserId) {
        if (operatorUserId == null || targetUserId == null) {
            throw new BusinessException("操作者和目标账号不能为空");
        }
        if (operatorUserId.equals(targetUserId)) {
            throw new BusinessException("不能修改本人的 S1 角色");
        }
    }

    private void ensureCanManageBindings(Long operatorUserId, Long roleId, boolean assigning) {
        IamS1Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("S1 角色不存在");
        }
        boolean systemAdmin = authorizationResolver.isSystemAdmin(operatorUserId);
        if (isProtected(role) && !systemAdmin) {
            throw new BusinessException("只有 S1 系统管理员可以分配系统管理员角色");
        }
        if (!systemAdmin && !authorizationResolver.hasGlobalFunction(operatorUserId, "organization:user:manage")) {
            throw new BusinessException("没有维护 S1 用户角色绑定的权限");
        }
        if (!systemAdmin && roleMapper.selectFunctionCodesByRoleId(roleId).stream()
                .anyMatch(IamS1FunctionCatalog::requiresSystemAdminRoleBinding)) {
            throw new BusinessException("后台角色只能由 S1 系统管理员分配");
        }
        if (assigning && !isProtected(role) && !roleStatusEnabled(role)) {
            throw new BusinessException("不能分配已禁用的 S1 角色");
        }
    }

    private IamS1Role requireActiveRole(Long roleId) {
        IamS1Role role = requireRole(roleId);
        if (!roleStatusEnabled(role)) {
            throw new BusinessException("S1 角色已禁用");
        }
        return role;
    }

    private IamS1Role requireRole(Long roleId) {
        if (roleId == null) {
            throw new BusinessException("S1 角色 ID 不能为空");
        }
        IamS1Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("S1 角色不存在");
        }
        return role;
    }

    private IamS1UserRole requireBinding(Long targetUserId, Long roleId) {
        IamS1UserRole binding = userRoleMapper.selectByUserAndRole(targetUserId, roleId);
        if (binding == null) {
            throw new BusinessException("S1 用户角色绑定不存在");
        }
        return binding;
    }

    private void ensureCanRemoveProtectedRole(IamS1Role role) {
        if (!isProtected(role)) {
            return;
        }
        if (roleMapper.countActiveProtectedRoles() != 1) {
            throw new BusinessException("S1 受保护系统管理员角色状态异常，拒绝变更");
        }
        IamS1Role lockedRole = roleMapper.selectActiveProtectedRoleForUpdate();
        if (lockedRole == null || !role.getId().equals(lockedRole.getId())) {
            throw new BusinessException("S1 受保护系统管理员角色状态异常，拒绝变更");
        }
        List<IamS1UserRole> bindings = userRoleMapper.selectActiveProtectedBindingsForUpdate();
        if (bindings.size() <= 1) {
            throw new BusinessException("不能移除最后一个有效 S1 系统管理员");
        }
    }

    private boolean isProtected(IamS1Role role) {
        return Integer.valueOf(IamS1Constants.ENABLED).equals(role.getProtectedRole())
                && Integer.valueOf(IamS1Constants.ENABLED).equals(role.getBuiltIn());
    }

    private boolean roleStatusEnabled(IamS1Role role) {
        return Integer.valueOf(IamS1Constants.ENABLED).equals(role.getStatus());
    }

    private void recordFailureSafely(String eventType, Long operatorUserId, Long targetUserId,
                                     String reason, RuntimeException exception) {
        try {
            auditEventService.recordFailure(eventType, operatorUserId, "USER_ROLE", targetUserId, reason,
                    exception.getMessage(), UUID.randomUUID().toString());
        } catch (RuntimeException auditException) {
            log.error("S1 用户角色失败审计写入失败 targetUserId={}", targetUserId, auditException);
        }
    }
}
