package com.dataocean.module.system.service;

import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRecipientResolverTest {

    @Test
    void resolvesOnlyDistinctActiveS1ProtectedAdminBindings() {
        IamS1RoleMapper mapper = mock(IamS1RoleMapper.class);
        when(mapper.selectActiveProtectedAdminUserIds()).thenReturn(List.of(1L, 1L, 2L));

        NotificationRecipientResolver resolver = new NotificationRecipientResolver(mapper);

        assertThat(resolver.adminUserIds()).containsExactly(1L, 2L);
        verify(mapper).selectActiveProtectedAdminUserIds();
    }

    @Test
    void oldAdminRoleWithoutS1BindingProducesNoRecipients() {
        IamS1RoleMapper mapper = mock(IamS1RoleMapper.class);
        when(mapper.selectActiveProtectedAdminUserIds()).thenReturn(List.of());

        assertThat(new NotificationRecipientResolver(mapper).adminUserIds()).isEmpty();
    }
}
