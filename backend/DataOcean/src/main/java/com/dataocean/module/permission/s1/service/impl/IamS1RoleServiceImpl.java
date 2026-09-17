package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.entity.IamS1Function;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1RoleFunction;
import com.dataocean.module.permission.s1.entity.dto.IamS1RoleSaveDTO;
import com.dataocean.module.permission.s1.mapper.IamS1RoleFunctionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1FunctionService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.service.IamS1RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** IAM-SIMPLE-1 普通角色服务实现。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1RoleServiceImpl implements IamS1RoleService {

    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("[A-Za-z0-9:_-]{1,100}");

    private final IamS1RoleMapper roleMapper;
    private final IamS1RoleFunctionMapper roleFunctionMapper;
    private final IamS1UserRoleMapper userRoleMapper;
    private final IamS1FunctionService functionService;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;

    @Override
    @Transactional
    public Long createRole(Long operatorUserId, IamS1RoleSaveDTO request) {
        try {
            return doCreateRole(operatorUserId, request);
        } catch (RuntimeException exception) {
            recordFailureSafely("ROLE_CREATE_FAILED", operatorUserId, null,
                    request == null ? null : request.getReason(), exception);
            throw exception;
        }
    }

    private Long doCreateRole(Long operatorUserId, IamS1RoleSaveDTO request) {
        ensureCanManageRoles(operatorUserId);
        validateRequest(request);
        if (IamS1Constants.SYSTEM_ADMIN_ROLE_CODE.equals(request.getRoleCode())) {
            throw new BusinessException("系统管理员角色只能由 IAM-SIMPLE-1 固定规则初始化");
        }
        List<IamS1Function> functions = functionService.resolveExpanded(request.getFunctionCodes());
        ensureCanConfigureFunctions(operatorUserId, functions);
        IamS1Role existing = roleMapper.selectOne(new LambdaQueryWrapper<IamS1Role>()
                .eq(IamS1Role::getRoleCode, request.getRoleCode()));
        if (existing != null) {
            throw new BusinessException("S1 角色编码已存在");
        }

        IamS1Role role = new IamS1Role();
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName().trim());
        role.setDescription(request.getDescription());
        role.setStatus(normalizeStatus(request.getStatus()));
        role.setProtectedRole(IamS1Constants.DISABLED);
        role.setBuiltIn(IamS1Constants.DISABLED);
        role.setCreatedBy(operatorUserId);
        role.setUpdatedBy(operatorUserId);
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("S1 角色编码已存在，请刷新后重试");
        }
        replaceFunctions(role.getId(), operatorUserId, functions);
        Long revisionNo = revisionService.record("ROLE", role.getId(), "CREATE", operatorUserId,
                request.getReason());
        auditEventService.recordSuccess("ROLE_CREATED", operatorUserId, "ROLE", role.getId(), null,
                "roleCode=" + role.getRoleCode() + ";revision=" + revisionNo, request.getReason(),
                UUID.randomUUID().toString());
        return role.getId();
    }

    @Override
    @Transactional
    public void updateRole(Long operatorUserId, Long roleId, IamS1RoleSaveDTO request) {
        try {
            doUpdateRole(operatorUserId, roleId, request);
        } catch (RuntimeException exception) {
            recordFailureSafely("ROLE_UPDATE_FAILED", operatorUserId, roleId,
                    request == null ? null : request.getReason(), exception);
            throw exception;
        }
    }

    private void doUpdateRole(Long operatorUserId, Long roleId, IamS1RoleSaveDTO request) {
        ensureCanManageRoles(operatorUserId);
        validateRequest(request);
        IamS1Role role = requireRole(roleId);
        ensureOrdinaryRole(role);
        ensureNotHoldingRole(operatorUserId, roleId);
        List<IamS1Function> functions = functionService.resolveExpanded(request.getFunctionCodes());
        ensureCanConfigureFunctions(operatorUserId, functions);
        if (IamS1Constants.SYSTEM_ADMIN_ROLE_CODE.equals(request.getRoleCode())) {
            throw new BusinessException("普通角色不能改为系统管理员");
        }
        IamS1Role sameCode = roleMapper.selectOne(new LambdaQueryWrapper<IamS1Role>()
                .eq(IamS1Role::getRoleCode, request.getRoleCode())
                .ne(IamS1Role::getId, roleId));
        if (sameCode != null) {
            throw new BusinessException("S1 角色编码已存在");
        }
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName().trim());
        role.setDescription(request.getDescription());
        role.setStatus(normalizeStatus(request.getStatus()));
        role.setUpdatedBy(operatorUserId);
        roleMapper.updateById(role);
        replaceFunctions(roleId, operatorUserId, functions);
        Long revisionNo = revisionService.record("ROLE", roleId, "UPDATE", operatorUserId, request.getReason());
        auditEventService.recordSuccess("ROLE_UPDATED", operatorUserId, "ROLE", roleId, null,
                "roleCode=" + role.getRoleCode() + ";revision=" + revisionNo, request.getReason(),
                UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public void deleteRole(Long operatorUserId, Long roleId, String reason) {
        try {
            doDeleteRole(operatorUserId, roleId, reason);
        } catch (RuntimeException exception) {
            recordFailureSafely("ROLE_DELETE_FAILED", operatorUserId, roleId, reason, exception);
            throw exception;
        }
    }

    private void doDeleteRole(Long operatorUserId, Long roleId, String reason) {
        ensureCanManageRoles(operatorUserId);
        IamS1Role role = requireRole(roleId);
        ensureOrdinaryRole(role);
        ensureNotHoldingRole(operatorUserId, roleId);
        Long memberCount = userRoleMapper.selectCount(new LambdaQueryWrapper<com.dataocean.module.permission.s1.entity.IamS1UserRole>()
                .eq(com.dataocean.module.permission.s1.entity.IamS1UserRole::getRoleId, roleId));
        if (memberCount != null && memberCount > 0) {
            throw new BusinessException("S1 角色仍有成员，无法删除");
        }
        roleFunctionMapper.delete(new LambdaQueryWrapper<IamS1RoleFunction>()
                .eq(IamS1RoleFunction::getRoleId, roleId));
        roleMapper.deleteById(roleId);
        Long revisionNo = revisionService.record("ROLE", roleId, "DELETE", operatorUserId, reason);
        auditEventService.recordSuccess("ROLE_DELETED", operatorUserId, "ROLE", roleId, null,
                "revision=" + revisionNo, reason, UUID.randomUUID().toString());
    }

    private void replaceFunctions(Long roleId, Long operatorUserId, List<IamS1Function> functions) {
        roleFunctionMapper.delete(new LambdaQueryWrapper<IamS1RoleFunction>()
                .eq(IamS1RoleFunction::getRoleId, roleId));
        for (IamS1Function function : functions) {
            IamS1RoleFunction relation = new IamS1RoleFunction();
            relation.setRoleId(roleId);
            relation.setFunctionId(function.getId());
            relation.setCreatedBy(operatorUserId);
            roleFunctionMapper.insert(relation);
        }
    }

    private void ensureCanConfigureFunctions(Long operatorUserId, List<IamS1Function> functions) {
        if (authorizationResolver.isSystemAdmin(operatorUserId)) {
            return;
        }
        boolean sensitive = functions.stream()
                .map(IamS1Function::getFunctionCode)
                .anyMatch(IamS1FunctionCatalog::requiresSystemAdminRoleConfiguration);
        if (sensitive) {
            throw new BusinessException("授权管理、组织管理、审批、字段保护和平台配置功能只能由 S1 系统管理员配置");
        }
    }

    private void ensureCanManageRoles(Long operatorUserId) {
        if (operatorUserId == null
                || !authorizationResolver.hasGlobalFunction(operatorUserId, "organization:role:manage")) {
            throw new BusinessException("没有维护 S1 角色的权限");
        }
    }

    private void ensureNotHoldingRole(Long operatorUserId, Long roleId) {
        if (operatorUserId != null && userRoleMapper.countActiveByUserAndRole(operatorUserId, roleId) > 0) {
            throw new BusinessException("不能修改、禁用或删除本人持有的 S1 角色");
        }
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

    private void ensureOrdinaryRole(IamS1Role role) {
        if (Integer.valueOf(IamS1Constants.ENABLED).equals(role.getProtectedRole())
                || Integer.valueOf(IamS1Constants.ENABLED).equals(role.getBuiltIn())
                || IamS1Constants.SYSTEM_ADMIN_ROLE_CODE.equals(role.getRoleCode())) {
            throw new BusinessException("系统管理员角色只能由服务端固定规则维护");
        }
    }

    private void validateRequest(IamS1RoleSaveDTO request) {
        if (request == null || request.getRoleCode() == null || request.getRoleName() == null
                || request.getRoleCode().isBlank() || request.getRoleName().isBlank()
                || !ROLE_CODE_PATTERN.matcher(request.getRoleCode()).matches()) {
            throw new BusinessException("S1 角色编码和名称不合法");
        }
    }

    private int normalizeStatus(Integer status) {
        if (status == null) {
            return IamS1Constants.ENABLED;
        }
        if (status != IamS1Constants.ENABLED && status != IamS1Constants.DISABLED) {
            throw new BusinessException("S1 角色状态不合法");
        }
        return status;
    }

    private void recordFailureSafely(String eventType, Long operatorUserId, Long targetId,
                                     String reason, RuntimeException exception) {
        try {
            auditEventService.recordFailure(eventType, operatorUserId, "ROLE", targetId, reason,
                    exception.getMessage(), UUID.randomUUID().toString());
        } catch (RuntimeException auditException) {
            log.error("S1 角色失败审计写入失败 targetId={}", targetId, auditException);
        }
    }
}
