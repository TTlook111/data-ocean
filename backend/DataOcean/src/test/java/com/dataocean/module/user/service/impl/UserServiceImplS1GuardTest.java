package com.dataocean.module.user.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新用户接口不得写入旧角色事实；删除必须停用 S1 绑定并记录 revision。
 * <p>
 * B6 批次 3 说明：原先还有 4 处 {@code verify(userRoleMapper, never())} 断言
 * （证明服务不去碰旧 {@code sys_user_role} 表）。随旧 {@code UserRoleMapper} /
 * {@code SysUserRole} 删除，这些断言已无法编写——该约束现在由**编译期**保证，
 * 比运行期断言更强，故不是覆盖缺失。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplS1GuardTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private IamS1UserRoleMapper iamS1UserRoleMapper;
    @Mock
    private IamS1DataGrantMapper iamS1DataGrantMapper;
    @Mock
    private IamS1PermissionRevisionService revisionService;
    @Mock
    private IamS1AuditEventService auditEventService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @InjectMocks
    private UserServiceImpl service;

    @Test
    void createUserRejectsNonEmptyLegacyRoleIdsAndInsertsNothing() {
        UserCreateDTO request = new UserCreateDTO();
        request.setUsername("alice");
        request.setPassword("Password1");
        request.setRealName("Alice");
        request.setRoleIds(List.of(3L));

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("roleIds");

        verify(userMapper, never()).insert(any(SysUser.class));
        verify(revisionService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void updateUserRejectsNonEmptyLegacyRoleIdsAndWritesNoRoleFacts() {
        UserUpdateDTO request = new UserUpdateDTO();
        request.setRealName("Alice");
        request.setRoleIds(List.of(3L));

        assertThatThrownBy(() -> service.updateUser(9L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("roleIds");

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void getUserByIdDoesNotExposeLegacyRolesAsS1Roles() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("alice");
        user.setStatus(SysUser.STATUS_NORMAL);
        when(userMapper.selectById(9L)).thenReturn(user);

        var vo = service.getUserById(9L);

        assertThat(vo.getRoleIds()).isEmpty();
        assertThat(vo.getRoleNames()).isEmpty();
        assertThat(vo.getRoleCodes()).isEmpty();
    }

    @Test
    void deleteUserRejectsLastProtectedAdminAndDeletesNothing() {
        SysUser user = enabledUser(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        IamS1UserRole binding = enabledBinding(9L);
        when(iamS1UserRoleMapper.selectByUserIdForUpdate(9L)).thenReturn(List.of(binding));
        when(iamS1UserRoleMapper.selectActiveProtectedBindingsForUpdate()).thenReturn(List.of(binding));
        when(userMapper.selectByIds(java.util.Set.of(9L))).thenReturn(List.of(user));

        assertThatThrownBy(() -> service.deleteUser(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个有效 S1 系统管理员");

        verify(userMapper, never()).deleteById(9L);
        verify(iamS1UserRoleMapper, never()).updateById(any(IamS1UserRole.class));
        verify(revisionService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deleteUserDisablesActiveS1BindingsRevokesUserGrantsAndRecordsRevision() {
        SysUser user = enabledUser(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        IamS1UserRole binding = enabledBinding(9L);
        binding.setId(4L);
        when(iamS1UserRoleMapper.selectByUserIdForUpdate(9L)).thenReturn(List.of(binding));
        when(iamS1UserRoleMapper.selectActiveProtectedBindingsForUpdate()).thenReturn(List.of());
        IamS1DataGrant grant = new IamS1DataGrant();
        grant.setId(8L);
        grant.setStatus(IamS1Constants.DATA_GRANT_STATUS_ACTIVE);
        when(iamS1DataGrantMapper.selectActiveBySubjectForUpdate(
                IamS1Constants.PROTOCOL_VERSION, IamS1Constants.SUBJECT_USER, 9L))
                .thenReturn(List.of(grant));
        when(revisionService.record(eq("USER"), eq(9L), eq("DELETE"), any(), any())).thenReturn(77L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service.deleteUser(9L);

        assertThat(binding.getStatus()).isEqualTo(IamS1Constants.DISABLED);
        assertThat(binding.getRevisionNo()).isEqualTo(77L);
        assertThat(grant.getStatus()).isEqualTo(IamS1Constants.DATA_GRANT_STATUS_REVOKED);
        assertThat(grant.getRevisionNo()).isEqualTo(77L);
        verify(iamS1UserRoleMapper).updateById(binding);
        verify(iamS1DataGrantMapper).updateById(grant);
        verify(userMapper).deleteById(9L);
        verify(revisionService).record("USER", 9L, "DELETE", null, "删除用户并停用其 S1 绑定");
        verify(auditEventService).recordSuccess(eq("USER_DELETE"), any(), eq("USER"), eq(9L),
                any(), any(), any(), any());
        verify(valueOperations).increment("user:token-version:9");
    }

    @Test
    void updateUserRecordsRevisionWhenPrimaryDepartmentChanges() {
        SysUser user = enabledUser(9L);
        user.setDepartmentId(1L);
        when(userMapper.selectById(9L)).thenReturn(user);
        SysDepartment department = new SysDepartment();
        department.setId(2L);
        department.setStatus(1);
        when(departmentMapper.selectById(2L)).thenReturn(department);
        when(revisionService.record(eq("USER"), eq(9L), eq("DEPARTMENT_CHANGE"), any(), any()))
                .thenReturn(12L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setDepartmentId(2L);
        service.updateUser(9L, request);

        verify(revisionService).record("USER", 9L, "DEPARTMENT_CHANGE", null, "调整用户主部门");
        verify(auditEventService).recordSuccess(eq("USER_DEPARTMENT_CHANGE"), any(), eq("USER"), eq(9L),
                any(), any(), any(), any());
    }

    @Test
    void updateStatusRecordsRevisionWhenDisablingUser() {
        SysUser user = enabledUser(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(iamS1UserRoleMapper.selectActiveProtectedBindingsForUpdate()).thenReturn(List.of());
        when(revisionService.record(eq("USER"), eq(9L), eq("DISABLE"), any(), any())).thenReturn(3L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service.updateStatus(9L, SysUser.STATUS_DISABLED);

        verify(revisionService).record("USER", 9L, "DISABLE", null, "变更用户状态");
        verify(valueOperations).increment("user:token-version:9");
    }

    @Test
    void updateStatusRejectsDisablingLastProtectedAdmin() {
        SysUser user = enabledUser(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        IamS1UserRole binding = enabledBinding(9L);
        when(iamS1UserRoleMapper.selectActiveProtectedBindingsForUpdate()).thenReturn(List.of(binding));
        when(userMapper.selectByIds(java.util.Set.of(9L))).thenReturn(List.of(user));

        assertThatThrownBy(() -> service.updateStatus(9L, SysUser.STATUS_DISABLED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个有效 S1 系统管理员");

        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(revisionService, never()).record(any(), any(), any(), any(), any());
    }

    private static SysUser enabledUser(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("alice");
        user.setStatus(SysUser.STATUS_NORMAL);
        return user;
    }

    private static IamS1UserRole enabledBinding(Long userId) {
        IamS1UserRole binding = new IamS1UserRole();
        binding.setUserId(userId);
        binding.setRoleId(1L);
        binding.setStatus(IamS1Constants.ENABLED);
        return binding;
    }
}
