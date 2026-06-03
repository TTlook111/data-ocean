package com.dataocean.module.user.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.entity.SysUserRole;
import com.dataocean.module.user.mapper.PermissionMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.RolePermissionMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServiceImplTest {

    @Test
    void listUsersByRoleReturnsMembersWithRoleMetadata() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleServiceImpl service = service(roleMapper, userMapper, userRoleMapper);

        SysRole role = role(5L, "ADMIN", "超级管理员");
        SysUser admin = user(1L, "admin", "超级管理员", SysUser.STATUS_NORMAL);
        when(roleMapper.selectById(5L)).thenReturn(role);
        when(roleMapper.selectUsersByRoleId(5L)).thenReturn(List.of(admin));

        var members = service.listUsersByRole(5L);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).getUsername()).isEqualTo("admin");
        assertThat(members.get(0).getRoleIds()).containsExactly(5L);
        assertThat(members.get(0).getRoleCodes()).containsExactly("ADMIN");
    }

    @Test
    void assignRoleToUserCreatesMissingRelation() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleServiceImpl service = service(roleMapper, userMapper, userRoleMapper);

        when(roleMapper.selectById(2L)).thenReturn(role(2L, "DATA_ANALYST", "数据分析师"));
        when(userMapper.selectById(9L)).thenReturn(user(9L, "analyst", "分析师", SysUser.STATUS_NORMAL));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);

        service.assignRoleToUser(2L, 9L);

        verify(userRoleMapper).insert(any(SysUserRole.class));
    }

    @Test
    void assignRoleToUserSkipsExistingRelation() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleServiceImpl service = service(roleMapper, userMapper, userRoleMapper);

        when(roleMapper.selectById(2L)).thenReturn(role(2L, "DATA_ANALYST", "数据分析师"));
        when(userMapper.selectById(9L)).thenReturn(user(9L, "analyst", "分析师", SysUser.STATUS_NORMAL));
        when(userRoleMapper.selectCount(any())).thenReturn(1L);

        service.assignRoleToUser(2L, 9L);

        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void assignRoleToUserRejectsDisabledUser() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleServiceImpl service = service(roleMapper, userMapper, userRoleMapper);

        when(roleMapper.selectById(2L)).thenReturn(role(2L, "DATA_ANALYST", "数据分析师"));
        when(userMapper.selectById(9L)).thenReturn(user(9L, "analyst", "分析师", SysUser.STATUS_DISABLED));

        assertThatThrownBy(() -> service.assignRoleToUser(2L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能分配角色");
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void removeRoleFromUserProtectsLastActiveAdmin() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleServiceImpl service = service(roleMapper, userMapper, userRoleMapper);

        when(roleMapper.selectById(5L)).thenReturn(role(5L, "ADMIN", "超级管理员"));
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin", "超级管理员", SysUser.STATUS_NORMAL));
        when(roleMapper.countActiveUsersByRoleId(5L)).thenReturn(1L);

        assertThatThrownBy(() -> service.removeRoleFromUser(5L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超级管理员");
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void removeRoleFromUserAllowsDisabledAdminCleanup() {
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        RoleServiceImpl service = service(roleMapper, userMapper, userRoleMapper);

        when(roleMapper.selectById(5L)).thenReturn(role(5L, "ADMIN", "超级管理员"));
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin", "超级管理员", SysUser.STATUS_DISABLED));

        service.removeRoleFromUser(5L, 1L);

        verify(userRoleMapper).delete(any());
    }

    private static SysRole role(Long id, String code, String name) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setStatus(1);
        return role;
    }

    private static SysUser user(Long id, String username, String realName, int status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRealName(realName);
        user.setStatus(status);
        return user;
    }

    private static RoleServiceImpl service(RoleMapper roleMapper, UserMapper userMapper, UserRoleMapper userRoleMapper) {
        return new RoleServiceImpl(
                roleMapper,
                mock(PermissionMapper.class),
                mock(RolePermissionMapper.class),
                userMapper,
                userRoleMapper
        );
    }
}
