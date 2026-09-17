package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.module.permission.s1.entity.IamS1Function;
import com.dataocean.module.permission.s1.entity.vo.IamS1AuthorizationDecision;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FunctionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamS1AuthorizationResolverImplTest {

    @Mock
    private IamS1UserIdentityMapper userIdentityMapper;
    @Mock
    private IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    @Mock
    private IamS1FunctionMapper functionMapper;
    @Mock
    private IamS1RoleMapper roleMapper;

    @Test
    void unknownFunctionIsDeniedBeforeAnyRoleCalculation() {
        when(userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(functionMapper.selectActiveByCode("unknown:function")).thenReturn(null);
        IamS1AuthorizationDecision decision = resolver().resolveAdminAction(10L, "unknown:function", 20L);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("UNKNOWN_FUNCTION");
        verifyNoInteractions(datasourceIdentityMapper, roleMapper);
    }

    @Test
    void disabledUserIsDenied() {
        when(userIdentityMapper.countEnabledUser(10L)).thenReturn(0L);

        IamS1AuthorizationDecision decision = resolver().resolveAdminAction(10L, "datasource:view", 20L);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("USER_NOT_ENABLED");
        verifyNoInteractions(functionMapper, datasourceIdentityMapper, roleMapper);
    }

    @Test
    void oldPermissionFactsWithoutS1BindingRemainDenied() {
        when(userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(functionMapper.selectActiveByCode("datasource:view")).thenReturn(function("datasource:view"));
        when(datasourceIdentityMapper.countEnabledDatasource(20L)).thenReturn(1L);
        when(roleMapper.countActiveProtectedRoles()).thenReturn(1L);
        when(roleMapper.countActiveProtectedBindings(10L)).thenReturn(0L);
        when(roleMapper.countActiveUserRoleBindings(10L)).thenReturn(0L);

        IamS1AuthorizationDecision decision = resolver().resolveAdminAction(10L, "datasource:view", 20L);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("NO_S1_BINDING");
    }

    @Test
    void sameBindingWithFunctionAndDatasourceIsAllowed() {
        when(userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(functionMapper.selectActiveByCode("datasource:manage")).thenReturn(function("datasource:manage"));
        when(datasourceIdentityMapper.countEnabledDatasource(20L)).thenReturn(1L);
        when(roleMapper.countActiveProtectedRoles()).thenReturn(1L);
        when(roleMapper.countActiveProtectedBindings(10L)).thenReturn(0L);
        when(roleMapper.countActiveUserRoleBindings(10L)).thenReturn(1L);
        when(roleMapper.countActiveUserFunction(10L, "datasource:manage")).thenReturn(1L);
        when(roleMapper.countActiveUserDatasource(10L, 20L)).thenReturn(1L);
        when(roleMapper.countActiveUserFunctionDatasource(10L, "datasource:manage", 20L)).thenReturn(1L);

        assertThat(resolver().canManageDatasource(10L, "datasource:manage", 20L)).isTrue();
    }

    @Test
    void functionAndDatasourceFromDifferentBindingsAreDenied() {
        when(userIdentityMapper.countEnabledUser(10L)).thenReturn(1L);
        when(functionMapper.selectActiveByCode("metadata:view")).thenReturn(function("metadata:view"));
        when(datasourceIdentityMapper.countEnabledDatasource(20L)).thenReturn(1L);
        when(roleMapper.countActiveProtectedRoles()).thenReturn(1L);
        when(roleMapper.countActiveProtectedBindings(10L)).thenReturn(0L);
        when(roleMapper.countActiveUserRoleBindings(10L)).thenReturn(2L);
        when(roleMapper.countActiveUserFunction(10L, "metadata:view")).thenReturn(1L);
        when(roleMapper.countActiveUserDatasource(10L, 20L)).thenReturn(1L);
        when(roleMapper.countActiveUserFunctionDatasource(10L, "metadata:view", 20L)).thenReturn(0L);

        IamS1AuthorizationDecision decision = resolver().resolveAdminAction(10L, "metadata:view", 20L);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReasonCode()).isEqualTo("SAME_BINDING_REQUIRED");
    }

    @Test
    void protectedS1SystemAdminGetsBackendRangeButOnlyFromS1Binding() {
        when(userIdentityMapper.countEnabledUser(1L)).thenReturn(1L);
        when(functionMapper.selectActiveByCode("system:runtime:manage")).thenReturn(function("system:runtime:manage"));
        when(datasourceIdentityMapper.countEnabledDatasource(20L)).thenReturn(1L);
        when(roleMapper.countActiveProtectedRoles()).thenReturn(1L);
        when(roleMapper.countActiveProtectedBindings(1L)).thenReturn(1L);

        assertThat(resolver().canManageDatasource(1L, "system:runtime:manage", 20L)).isTrue();
        verify(roleMapper).countActiveProtectedBindings(1L);
    }

    private IamS1AuthorizationResolverImpl resolver() {
        return new IamS1AuthorizationResolverImpl(
                userIdentityMapper, datasourceIdentityMapper, functionMapper, roleMapper);
    }

    private IamS1Function function(String code) {
        IamS1Function function = new IamS1Function();
        function.setFunctionCode(code);
        function.setStatus("ACTIVE");
        return function;
    }
}
