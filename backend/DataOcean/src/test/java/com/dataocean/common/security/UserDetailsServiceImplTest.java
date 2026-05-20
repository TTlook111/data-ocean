package com.dataocean.common.security;

import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.PermissionMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDetailsServiceImplTest {

    @Test
    void wildcardPermissionExpandsFromPermissionTable() {
        UserMapper userMapper = mock(UserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        PermissionMapper permissionMapper = mock(PermissionMapper.class);

        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("encoded");
        user.setRealName("超级管理员");
        user.setStatus(SysUser.STATUS_NORMAL);

        SysRole role = new SysRole();
        role.setRoleCode("ADMIN");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(roleMapper.selectByUserId(1L)).thenReturn(List.of(role));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(List.of("*"));
        when(permissionMapper.selectAllPermissionCodes()).thenReturn(List.of("*", "metadata:manage", "datasource:manage"));

        UserDetailsServiceImpl service = new UserDetailsServiceImpl(userMapper, roleMapper, permissionMapper);

        UserDetails details = service.loadUserByUsername("admin");

        assertThat(details).isInstanceOf(LoginUser.class);
        LoginUser loginUser = (LoginUser) details;
        assertThat(loginUser.getPermissions()).containsExactly("*", "metadata:manage", "datasource:manage");
        assertThat(loginUser.getAuthorities())
                .extracting(Object::toString)
                .contains("metadata:manage", "datasource:manage", "ROLE_ADMIN");
    }
}
