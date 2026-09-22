package com.dataocean.common.security;

import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDetailsServiceImplTest {

    @Test
    void loadsOnlyIdentityAndUsesNonBusinessAuthenticationAuthority() {
        UserMapper userMapper = mock(UserMapper.class);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("encoded");
        user.setRealName("超级管理员");
        user.setStatus(SysUser.STATUS_NORMAL);
        user.setDeleted(0);

        when(userMapper.selectOne(any())).thenReturn(user);

        UserDetailsServiceImpl service = new UserDetailsServiceImpl(userMapper);
        UserDetails details = service.loadUserByUsername("admin");

        assertThat(details).isInstanceOf(LoginUser.class);
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("AUTHENTICATED_USER");
        verify(userMapper).selectOne(any());
    }
}
