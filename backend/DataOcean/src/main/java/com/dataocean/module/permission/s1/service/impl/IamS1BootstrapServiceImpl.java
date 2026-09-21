package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.entity.IamS1BootstrapState;
import com.dataocean.module.permission.s1.entity.vo.IamS1BootstrapResult;
import com.dataocean.module.permission.s1.mapper.IamS1BootstrapStateMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1BootstrapService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** IAM-SIMPLE-1 启动式首个系统管理员初始化实现。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1BootstrapServiceImpl implements IamS1BootstrapService {

    private static final String STATE_PENDING = "PENDING";
    private static final String STATE_COMPLETED = "COMPLETED";
    private static final String IMPLEMENTATION_VERSION = "B1";

    private final IamS1BootstrapStateMapper bootstrapStateMapper;
    private final IamS1UserIdentityMapper userIdentityMapper;
    private final IamS1RoleMapper roleMapper;
    private final IamS1UserRoleMapper userRoleMapper;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;

    @Override
    @Transactional
    public IamS1BootstrapResult bootstrap(Long targetUserId, String executionId) {
        try {
            return doBootstrap(targetUserId, executionId);
        } catch (RuntimeException exception) {
            recordFailureSafely(targetUserId, exception);
            throw exception;
        }
    }

    private IamS1BootstrapResult doBootstrap(Long targetUserId, String executionId) {
        if (targetUserId == null || targetUserId <= 0) {
            throw new BusinessException("bootstrap 目标账号 ID 不合法");
        }
        IamS1BootstrapState state = bootstrapStateMapper.selectByProtocolForUpdate(IamS1Constants.PROTOCOL_VERSION);
        if (state == null) {
            throw new BusinessException("IAM-SIMPLE-1 bootstrap 状态不存在，不能初始化");
        }
        if (STATE_COMPLETED.equals(state.getState())) {
            if (!targetUserId.equals(state.getTargetUserId())) {
                throw new BusinessException("IAM-SIMPLE-1 bootstrap 已完成，不能更换目标账号");
            }
            if (userIdentityMapper.countEnabledUser(targetUserId) != 1) {
                throw new BusinessException("bootstrap 已完成，但目标账号已不存在、删除或禁用");
            }
            Long userRoleId = findProtectedBindingId(targetUserId);
            if (userRoleId == null) {
                throw new BusinessException("bootstrap 状态与系统管理员绑定不一致");
            }
            return new IamS1BootstrapResult(STATE_COMPLETED, targetUserId, userRoleId, true);
        }
        if (!STATE_PENDING.equals(state.getState())) {
            throw new BusinessException("IAM-SIMPLE-1 bootstrap 状态不允许初始化");
        }
        if (userIdentityMapper.countEnabledUser(targetUserId) != 1) {
            throw new BusinessException("bootstrap 目标账号不存在、已删除或未启用");
        }

        List<IamS1Role> protectedRoles = roleMapper.selectList(new LambdaQueryWrapper<IamS1Role>()
                .eq(IamS1Role::getProtectedRole, IamS1Constants.ENABLED)
                .eq(IamS1Role::getBuiltIn, IamS1Constants.ENABLED)
                .eq(IamS1Role::getStatus, IamS1Constants.ENABLED));
        if (protectedRoles.size() != 1) {
            throw new BusinessException("IAM-SIMPLE-1 受保护系统管理员角色不唯一");
        }
        IamS1Role protectedRole = protectedRoles.get(0);
        String safeExecutionId = executionId == null || executionId.isBlank()
                ? UUID.randomUUID().toString() : executionId;
        Long revisionNo = revisionService.record("BOOTSTRAP", targetUserId, "BOOTSTRAP", null,
                "IAM-SIMPLE-1 首个系统管理员初始化");
        IamS1UserRole binding = new IamS1UserRole();
        binding.setUserId(targetUserId);
        binding.setRoleId(protectedRole.getId());
        binding.setStatus(IamS1Constants.ENABLED);
        binding.setRevisionNo(revisionNo);
        userRoleMapper.insert(binding);

        auditEventService.recordSuccess("BOOTSTRAP_COMPLETED", null, "USER_ROLE", binding.getId(), null,
                "targetUserId=" + targetUserId + ";roleId=" + protectedRole.getId() + ";revision=" + revisionNo,
                "IAM-SIMPLE-1 首个系统管理员初始化", safeExecutionId);

        state.setState(STATE_COMPLETED);
        state.setTargetUserId(targetUserId);
        state.setCompletedAt(LocalDateTime.now());
        state.setImplementationVersion(IMPLEMENTATION_VERSION);
        state.setExecutionId(safeExecutionId);
        bootstrapStateMapper.updateById(state);
        return new IamS1BootstrapResult(STATE_COMPLETED, targetUserId, binding.getId(), false);
    }

    private Long findProtectedBindingId(Long targetUserId) {
        List<IamS1Role> protectedRoles = roleMapper.selectList(new LambdaQueryWrapper<IamS1Role>()
                .eq(IamS1Role::getProtectedRole, IamS1Constants.ENABLED)
                .eq(IamS1Role::getBuiltIn, IamS1Constants.ENABLED)
                .eq(IamS1Role::getStatus, IamS1Constants.ENABLED));
        if (protectedRoles.size() != 1
                || !Integer.valueOf(IamS1Constants.ENABLED).equals(protectedRoles.get(0).getStatus())) {
            return null;
        }
        if (userIdentityMapper.countEnabledUser(targetUserId) != 1) {
            return null;
        }
        IamS1UserRole binding = userRoleMapper.selectByUserAndRole(targetUserId, protectedRoles.get(0).getId());
        return binding == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(binding.getStatus())
                ? null : binding.getId();
    }

    private void recordFailureSafely(Long targetUserId, RuntimeException exception) {
        try {
            auditEventService.recordFailure("BOOTSTRAP_FAILED", null, "BOOTSTRAP", targetUserId,
                    "IAM-SIMPLE-1 首个系统管理员初始化", exception.getMessage(), UUID.randomUUID().toString());
        } catch (RuntimeException auditException) {
            log.error("S1 bootstrap 失败审计写入失败 targetUserId={}", targetUserId, auditException);
        }
    }
}
