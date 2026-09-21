package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1Function;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.dto.IamS1RoleSaveDTO;
import com.dataocean.module.permission.s1.mapper.IamS1RoleFunctionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1FunctionService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamS1RoleServiceImplTest {

    @Test
    void ordinaryRoleRequestCannotCreateProtectedSystemAdmin() {
        IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        IamS1AuthorizationResolver resolver = mock(IamS1AuthorizationResolver.class);
        when(resolver.hasGlobalFunction(10L, "organization:role:manage")).thenReturn(true);
        Fixture fixture = new Fixture(roleMapper, resolver);

        IamS1RoleSaveDTO request = request("IAM_S1_SYSTEM_ADMIN", "伪装名称");

        assertThatThrownBy(() -> fixture.service.createRole(10L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能由 IAM-SIMPLE-1");
        verify(roleMapper, never()).insert(any(IamS1Role.class));
    }

    @Test
    void roleOwnerCannotModifyOwnRole() {
        IamS1Role role = new IamS1Role();
        role.setId(20L);
        role.setRoleCode("DATA_REVIEWER");
        role.setProtectedRole(0);
        role.setBuiltIn(0);
        role.setStatus(1);
        IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        when(roleMapper.selectById(20L)).thenReturn(role);
        IamS1AuthorizationResolver resolver = mock(IamS1AuthorizationResolver.class);
        when(resolver.hasGlobalFunction(10L, "organization:role:manage")).thenReturn(true);
        IamS1UserRoleMapper userRoleMapper = mock(IamS1UserRoleMapper.class);
        when(userRoleMapper.countActiveByUserAndRole(10L, 20L)).thenReturn(1L);
        Fixture fixture = new Fixture(roleMapper, resolver, userRoleMapper);

        assertThatThrownBy(() -> fixture.service.updateRole(10L, 20L, request("DATA_REVIEWER", "复核员")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本人持有");
        verify(roleMapper, never()).updateById(any(IamS1Role.class));
    }

    @Test
    void ordinaryRoleManagerCannotConfigureSensitiveFunctions() {
        IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        IamS1AuthorizationResolver resolver = mock(IamS1AuthorizationResolver.class);
        IamS1FunctionService functionService = mock(IamS1FunctionService.class);
        IamS1AuditEventService auditEventService = mock(IamS1AuditEventService.class);
        when(resolver.hasGlobalFunction(10L, "organization:role:manage")).thenReturn(true);
        when(resolver.isSystemAdmin(10L)).thenReturn(false);
        IamS1Function sensitiveFunction = new IamS1Function();
        sensitiveFunction.setId(32L);
        sensitiveFunction.setFunctionCode("security:permission:manage");
        when(functionService.resolveExpanded(List.of("security:permission:manage")))
                .thenReturn(List.of(sensitiveFunction));
        IamS1RoleServiceImpl service = new IamS1RoleServiceImpl(
                roleMapper,
                mock(IamS1RoleFunctionMapper.class),
                mock(IamS1UserRoleMapper.class),
                functionService,
                resolver,
                mock(IamS1PermissionRevisionService.class),
                auditEventService);

        IamS1RoleSaveDTO request = request("SECURITY_OPERATOR", "安全操作员");
        request.setFunctionCodes(List.of("security:permission:manage"));

        assertThatThrownBy(() -> service.createRole(10L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能由 S1 系统管理员");
        verify(roleMapper, never()).insert(any(IamS1Role.class));
        verify(auditEventService).recordFailure(
                org.mockito.ArgumentMatchers.eq("ROLE_CREATE_FAILED"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("ROLE"),
                org.mockito.ArgumentMatchers.<Long>isNull(),
                org.mockito.ArgumentMatchers.<String>isNull(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private IamS1RoleSaveDTO request(String code, String name) {
        IamS1RoleSaveDTO request = new IamS1RoleSaveDTO();
        request.setRoleCode(code);
        request.setRoleName(name);
        return request;
    }

    private static class Fixture {
        private final IamS1RoleServiceImpl service;

        private Fixture(IamS1RoleMapper roleMapper, IamS1AuthorizationResolver resolver) {
            this(roleMapper, resolver, mock(IamS1UserRoleMapper.class));
        }

        private Fixture(IamS1RoleMapper roleMapper, IamS1AuthorizationResolver resolver,
                        IamS1UserRoleMapper userRoleMapper) {
            service = new IamS1RoleServiceImpl(
                    roleMapper,
                    mock(IamS1RoleFunctionMapper.class),
                    userRoleMapper,
                    mock(IamS1FunctionService.class),
                    resolver,
                    mock(IamS1PermissionRevisionService.class),
                    mock(IamS1AuditEventService.class));
        }
    }
}
