package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1BootstrapState;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.entity.vo.IamS1BootstrapResult;
import com.dataocean.module.permission.s1.mapper.IamS1BootstrapStateMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamS1BootstrapServiceImplTest {

    @Test
    void bootstrapCreatesOneS1BindingAndCompletesState() {
        Fixture fixture = new Fixture();
        IamS1BootstrapState state = pendingState();
        IamS1Role systemAdmin = protectedRole();
        when(fixture.stateMapper.selectByProtocolForUpdate("IAM-SIMPLE-1")).thenReturn(state);
        when(fixture.userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(fixture.roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(systemAdmin));
        when(fixture.revisionService.record("BOOTSTRAP", 10L, "BOOTSTRAP", null,
                "IAM-SIMPLE-1 首个系统管理员初始化")).thenReturn(42L);
        doAnswer(invocation -> {
            IamS1UserRole binding = invocation.getArgument(0);
            binding.setId(99L);
            return 1;
        }).when(fixture.userRoleMapper).insert(any(IamS1UserRole.class));

        IamS1BootstrapResult result = fixture.service.bootstrap(10L, "run-1");

        assertThat(result.getState()).isEqualTo("COMPLETED");
        assertThat(result.getTargetUserId()).isEqualTo(10L);
        assertThat(result.getUserRoleId()).isEqualTo(99L);
        assertThat(result.isIdempotent()).isFalse();
        assertThat(state.getState()).isEqualTo("COMPLETED");
        assertThat(state.getTargetUserId()).isEqualTo(10L);
        verify(fixture.auditEventService).recordSuccess(
                "BOOTSTRAP_COMPLETED", null, "USER_ROLE", 99L, null,
                "targetUserId=10;roleId=1;revision=42", "IAM-SIMPLE-1 首个系统管理员初始化", "run-1");
        verify(fixture.stateMapper).updateById(state);
    }

    @Test
    void bootstrapSameTargetIsIdempotentAfterCompletion() {
        Fixture fixture = new Fixture();
        IamS1BootstrapState state = pendingState();
        state.setState("COMPLETED");
        state.setTargetUserId(10L);
        IamS1Role systemAdmin = protectedRole();
        IamS1UserRole binding = new IamS1UserRole();
        binding.setId(99L);
        binding.setStatus(1);
        when(fixture.stateMapper.selectByProtocolForUpdate("IAM-SIMPLE-1")).thenReturn(state);
        when(fixture.userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(fixture.roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(systemAdmin));
        when(fixture.userRoleMapper.selectByUserAndRole(10L, 1L)).thenReturn(binding);

        IamS1BootstrapResult result = fixture.service.bootstrap(10L, "run-2");

        assertThat(result.isIdempotent()).isTrue();
        assertThat(result.getUserRoleId()).isEqualTo(99L);
        verify(fixture.userRoleMapper, never()).insert(any(IamS1UserRole.class));
        verify(fixture.revisionService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void completedBootstrapRejectsDisabledBinding() {
        Fixture fixture = new Fixture();
        IamS1BootstrapState state = pendingState();
        state.setState("COMPLETED");
        state.setTargetUserId(10L);
        IamS1Role systemAdmin = protectedRole();
        IamS1UserRole binding = new IamS1UserRole();
        binding.setId(99L);
        binding.setStatus(0);
        when(fixture.stateMapper.selectByProtocolForUpdate("IAM-SIMPLE-1")).thenReturn(state);
        when(fixture.userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(fixture.roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(systemAdmin));
        when(fixture.userRoleMapper.selectByUserAndRole(10L, 1L)).thenReturn(binding);

        assertThatThrownBy(() -> fixture.service.bootstrap(10L, "run-invalid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态与系统管理员绑定不一致");
        verify(fixture.auditEventService).recordFailure(
                org.mockito.ArgumentMatchers.eq("BOOTSTRAP_FAILED"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("BOOTSTRAP"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void completedBootstrapRejectsDisabledProtectedRole() {
        Fixture fixture = new Fixture();
        IamS1BootstrapState state = pendingState();
        state.setState("COMPLETED");
        state.setTargetUserId(10L);
        IamS1Role disabledRole = protectedRole();
        disabledRole.setStatus(0);
        when(fixture.stateMapper.selectByProtocolForUpdate("IAM-SIMPLE-1")).thenReturn(state);
        when(fixture.userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(fixture.roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(disabledRole));

        assertThatThrownBy(() -> fixture.service.bootstrap(10L, "run-role-disabled"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态与系统管理员绑定不一致");
    }

    @Test
    void bootstrapDifferentTargetIsRejectedForever() {
        Fixture fixture = new Fixture();
        IamS1BootstrapState state = pendingState();
        state.setState("COMPLETED");
        state.setTargetUserId(10L);
        when(fixture.stateMapper.selectByProtocolForUpdate("IAM-SIMPLE-1")).thenReturn(state);

        assertThatThrownBy(() -> fixture.service.bootstrap(11L, "run-3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能更换目标账号");
        verify(fixture.userRoleMapper, never()).insert(any(IamS1UserRole.class));
    }

    @Test
    void bootstrapRejectsDisabledTargetBeforeWritingAnyFact() {
        Fixture fixture = new Fixture();
        when(fixture.stateMapper.selectByProtocolForUpdate("IAM-SIMPLE-1")).thenReturn(pendingState());
        when(fixture.userIdentityMapper.countEnabledUser(10L)).thenReturn(0L);

        assertThatThrownBy(() -> fixture.service.bootstrap(10L, "run-4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在、已删除或未启用");
        verify(fixture.userRoleMapper, never()).insert(any(IamS1UserRole.class));
        verify(fixture.stateMapper, never()).updateById(any(IamS1BootstrapState.class));
        verify(fixture.auditEventService).recordFailure(
                org.mockito.ArgumentMatchers.eq("BOOTSTRAP_FAILED"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("BOOTSTRAP"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private IamS1BootstrapState pendingState() {
        IamS1BootstrapState state = new IamS1BootstrapState();
        state.setId(1L);
        state.setProtocolVersion("IAM-SIMPLE-1");
        state.setState("PENDING");
        return state;
    }

    private IamS1Role protectedRole() {
        IamS1Role role = new IamS1Role();
        role.setId(1L);
        role.setRoleCode("IAM_S1_SYSTEM_ADMIN");
        role.setProtectedRole(1);
        role.setBuiltIn(1);
        role.setStatus(1);
        return role;
    }

    private static class Fixture {
        private final IamS1BootstrapStateMapper stateMapper = mock(IamS1BootstrapStateMapper.class);
        private final IamS1UserIdentityMapper userIdentityMapper = mock(IamS1UserIdentityMapper.class);
        private final IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        private final IamS1UserRoleMapper userRoleMapper = mock(IamS1UserRoleMapper.class);
        private final IamS1PermissionRevisionService revisionService = mock(IamS1PermissionRevisionService.class);
        private final IamS1AuditEventService auditEventService = mock(IamS1AuditEventService.class);
        private final IamS1BootstrapServiceImpl service = new IamS1BootstrapServiceImpl(
                stateMapper, userIdentityMapper, roleMapper, userRoleMapper, revisionService, auditEventService);
    }
}
