package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamS1RoleDatasourceServiceImplTest {

    @Test
    void userCannotChangeOwnResponsibleDatasource() {
        IamS1UserRole binding = binding(30L, 10L, 20L);
        Fixture fixture = new Fixture();
        when(fixture.userRoleMapper.selectById(30L)).thenReturn(binding);

        assertThatThrownBy(() -> fixture.service.bindDatasources(10L, 30L, java.util.List.of(40L), "本人负责源测试"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本人");
        verify(fixture.revisionService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void protectedSystemAdminDoesNotNeedAndCannotReceiveDatasourceRows() {
        IamS1UserRole binding = binding(30L, 11L, 1L);
        IamS1Role role = role(1L, 1, 1, 1);
        Fixture fixture = new Fixture();
        when(fixture.userRoleMapper.selectById(30L)).thenReturn(binding);
        when(fixture.authorizationResolver.isSystemAdmin(10L)).thenReturn(true);
        when(fixture.roleMapper.selectById(1L)).thenReturn(role);

        assertThatThrownBy(() -> fixture.service.bindDatasources(10L, 30L, java.util.List.of(40L), "保护范围测试"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("受保护规则");
        verify(fixture.roleDatasourceMapper, never()).insert(any(IamS1RoleDatasource.class));
    }

    private IamS1UserRole binding(Long id, Long userId, Long roleId) {
        IamS1UserRole binding = new IamS1UserRole();
        binding.setId(id);
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        binding.setStatus(1);
        return binding;
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
        private final IamS1UserRoleMapper userRoleMapper = mock(IamS1UserRoleMapper.class);
        private final IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        private final IamS1RoleDatasourceMapper roleDatasourceMapper = mock(IamS1RoleDatasourceMapper.class);
        private final IamS1DatasourceIdentityMapper datasourceIdentityMapper = mock(IamS1DatasourceIdentityMapper.class);
        private final IamS1AuthorizationResolver authorizationResolver = mock(IamS1AuthorizationResolver.class);
        private final IamS1PermissionRevisionService revisionService = mock(IamS1PermissionRevisionService.class);
        private final IamS1AuditEventService auditEventService = mock(IamS1AuditEventService.class);
        private final IamS1RoleDatasourceServiceImpl service = new IamS1RoleDatasourceServiceImpl(
                userRoleMapper, roleMapper, roleDatasourceMapper, datasourceIdentityMapper,
                authorizationResolver, revisionService, auditEventService);
    }
}
