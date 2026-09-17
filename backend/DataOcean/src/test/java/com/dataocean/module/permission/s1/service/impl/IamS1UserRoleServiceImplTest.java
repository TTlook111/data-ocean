package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.mapper.IamS1RoleDatasourceMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamS1UserRoleServiceImplTest {

    @Test
    void userCannotAssignOrChangeOwnS1Role() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.assignRole(10L, 10L, 20L, "本人测试"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本人");
        verify(fixture.userRoleMapper, never()).insert(any(IamS1UserRole.class));
    }

    @Test
    void ordinaryRoleManagerCannotAssignProtectedSystemAdmin() {
        IamS1Role protectedRole = role(1L, 1, 1, 1);
        Fixture fixture = new Fixture();
        when(fixture.roleMapper.selectById(1L)).thenReturn(protectedRole);
        when(fixture.authorizationResolver.isSystemAdmin(10L)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.assignRole(10L, 11L, 1L, "保护规则测试"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有 S1 系统管理员");
        verify(fixture.userRoleMapper, never()).insert(any(IamS1UserRole.class));
    }

    @Test
    void ordinaryUserManagerCannotAssignBackendRole() {
        Fixture fixture = new Fixture();
        IamS1Role backendRole = role(2L, 1, 0, 0);
        when(fixture.roleMapper.selectById(2L)).thenReturn(backendRole);
        when(fixture.authorizationResolver.isSystemAdmin(10L)).thenReturn(false);
        when(fixture.authorizationResolver.hasGlobalFunction(10L, "organization:user:manage"))
                .thenReturn(true);
        when(fixture.roleMapper.selectFunctionCodesByRoleId(2L)).thenReturn(List.of("datasource:manage"));

        assertThatThrownBy(() -> fixture.service.assignRole(10L, 11L, 2L, "后台角色转授测试"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("后台角色只能");
        verify(fixture.userRoleMapper, never()).insert(any(IamS1UserRole.class));
    }

    @Test
    void lastProtectedSystemAdminCannotBeRemoved() {
        IamS1Role protectedRole = role(1L, 1, 1, 1);
        IamS1UserRole binding = new IamS1UserRole();
        binding.setId(30L);
        binding.setUserId(11L);
        binding.setRoleId(1L);
        binding.setStatus(1);
        Fixture fixture = new Fixture();
        when(fixture.roleMapper.selectById(1L)).thenReturn(protectedRole);
        when(fixture.authorizationResolver.isSystemAdmin(10L)).thenReturn(true);
        when(fixture.userRoleMapper.selectByUserAndRole(11L, 1L)).thenReturn(binding);
        when(fixture.roleMapper.countActiveProtectedRoles()).thenReturn(1L);
        when(fixture.roleMapper.selectActiveProtectedRoleForUpdate()).thenReturn(protectedRole);
        when(fixture.userRoleMapper.selectActiveProtectedBindingsForUpdate()).thenReturn(List.of(binding));

        assertThatThrownBy(() -> fixture.service.removeRole(10L, 11L, 1L, "最后管理员测试"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个");
        verify(fixture.userRoleMapper, never()).deleteById(30L);
        verify(fixture.roleDatasourceMapper, never()).deleteByUserRoleId(30L);
    }

    @Test
    void administratorBindingRevisionUsesStableTargetUserId() {
        IamS1Role role = role(2L, 1, 0, 0);
        Fixture fixture = new Fixture();
        when(fixture.roleMapper.selectById(2L)).thenReturn(role);
        when(fixture.authorizationResolver.isSystemAdmin(10L)).thenReturn(true);
        when(fixture.userIdentityMapper.countEnabledUser(11L)).thenReturn(1L);
        when(fixture.userRoleMapper.selectByUserAndRole(11L, 2L)).thenReturn(null);
        when(fixture.revisionService.record("USER_ROLE", 11L, "ASSIGN", 10L, "稳定目标测试"))
                .thenReturn(7L);
        org.mockito.Mockito.doAnswer(invocation -> {
            IamS1UserRole binding = invocation.getArgument(0);
            binding.setId(30L);
            return 1;
        }).when(fixture.userRoleMapper).insert(any(IamS1UserRole.class));

        fixture.service.assignRole(10L, 11L, 2L, "稳定目标测试");

        verify(fixture.revisionService).record("USER_ROLE", 11L, "ASSIGN", 10L, "稳定目标测试");
    }

    private IamS1Role role(Long id, int status, int protectedRole, int builtIn) {
        IamS1Role role = new IamS1Role();
        role.setId(id);
        role.setStatus(status);
        role.setProtectedRole(protectedRole);
        role.setBuiltIn(builtIn);
        return role;
    }

    private static class Fixture {
        private final IamS1UserIdentityMapper userIdentityMapper = mock(IamS1UserIdentityMapper.class);
        private final IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        private final IamS1UserRoleMapper userRoleMapper = mock(IamS1UserRoleMapper.class);
        private final IamS1RoleDatasourceMapper roleDatasourceMapper = mock(IamS1RoleDatasourceMapper.class);
        private final IamS1AuthorizationResolver authorizationResolver = mock(IamS1AuthorizationResolver.class);
        private final IamS1PermissionRevisionService revisionService = mock(IamS1PermissionRevisionService.class);
        private final IamS1AuditEventService auditEventService = mock(IamS1AuditEventService.class);
        private final IamS1UserRoleServiceImpl service = new IamS1UserRoleServiceImpl(
                userIdentityMapper, roleMapper, userRoleMapper, roleDatasourceMapper,
                authorizationResolver, revisionService, auditEventService);
    }
}
