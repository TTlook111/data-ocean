package com.dataocean.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.user.dto.UserCreateRequest;
import com.dataocean.module.user.dto.UserQueryRequest;
import com.dataocean.module.user.dto.UserUpdateRequest;
import com.dataocean.module.user.dto.UserVO;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.entity.SysUserRole;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    @Transactional
    public Long createUser(UserCreateRequest request) {
        ensureUsernameAvailable(request.getUsername(), null);
        validateDepartment(request.getDepartmentId());
        validateRoles(request.getRoleIds());

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDepartmentId(request.getDepartmentId());
        user.setStatus(SysUser.STATUS_NORMAL);
        user.setDeleted(0);
        userMapper.insert(user);
        bindRoles(user.getId(), request.getRoleIds());
        return user.getId();
    }

    @Transactional
    public void updateUser(Long id, UserUpdateRequest request) {
        SysUser user = requireUser(id);
        validateDepartment(request.getDepartmentId());
        if (request.getRoleIds() != null) {
            validateRoles(request.getRoleIds());
        }
        if (StringUtils.hasText(request.getRealName())) {
            user.setRealName(request.getRealName());
        }
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDepartmentId(request.getDepartmentId());
        userMapper.updateById(user);
        if (request.getRoleIds() != null) {
            bindRoles(id, request.getRoleIds());
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        if (Long.valueOf(1L).equals(id)) {
            throw new BusinessException("不允许删除超级管理员");
        }
        requireUser(id);
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
    }

    public UserVO getUserById(Long id) {
        return toVO(requireUser(id));
    }

    public Page<UserVO> listUsers(UserQueryRequest request) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(request.getUsername()), SysUser::getUsername, request.getUsername())
                .like(StringUtils.hasText(request.getRealName()), SysUser::getRealName, request.getRealName())
                .eq(request.getDepartmentId() != null, SysUser::getDepartmentId, request.getDepartmentId())
                .eq(request.getStatus() != null, SysUser::getStatus, request.getStatus())
                .orderByDesc(SysUser::getCreatedAt);
        Page<SysUser> userPage = userMapper.selectPage(new Page<>(request.resolvedPage(), request.resolvedPageSize()), wrapper);
        Page<UserVO> result = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        result.setRecords(userPage.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    @Transactional
    public void disableUser(Long id) {
        updateStatus(id, SysUser.STATUS_DISABLED);
    }

    @Transactional
    public void enableUser(Long id) {
        updateStatus(id, SysUser.STATUS_NORMAL);
    }

    @Transactional
    public void unlockUser(Long id) {
        updateStatus(id, SysUser.STATUS_NORMAL);
        SysUser user = requireUser(id);
        stringRedisTemplate.delete("login:fail:" + user.getUsername());
    }

    @Transactional
    public void updateStatus(Long id, Integer status) {
        SysUser user = requireUser(id);
        if (Long.valueOf(1L).equals(id) && !Integer.valueOf(SysUser.STATUS_NORMAL).equals(status)) {
            throw new BusinessException("不允许禁用或锁定超级管理员");
        }
        if (!List.of(SysUser.STATUS_NORMAL, SysUser.STATUS_DISABLED, SysUser.STATUS_LOCKED).contains(status)) {
            throw new BusinessException("用户状态不合法");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        if (Integer.valueOf(SysUser.STATUS_DISABLED).equals(status) || Integer.valueOf(SysUser.STATUS_LOCKED).equals(status)) {
            stringRedisTemplate.delete("login:fail:" + user.getUsername());
            stringRedisTemplate.opsForValue().increment("user:token-version:" + id);
        }
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    private void ensureUsernameAvailable(String username, Long currentId) {
        SysUser existing = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null && !existing.getId().equals(currentId)) {
            throw new BusinessException("用户名已存在");
        }
    }

    private void validateDepartment(Long departmentId) {
        if (departmentId == null) {
            return;
        }
        SysDepartment department = departmentMapper.selectById(departmentId);
        if (department == null || !Integer.valueOf(1).equals(department.getStatus())) {
            throw new BusinessException("部门不存在或已禁用");
        }
    }

    private void validateRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BusinessException("至少选择一个角色");
        }
        List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
        if (roles.size() != Set.copyOf(roleIds).size() || roles.stream().anyMatch(role -> !Integer.valueOf(1).equals(role.getStatus()))) {
            throw new BusinessException("角色不存在或已禁用");
        }
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        for (Long roleId : roleIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
    }

    private UserVO toVO(SysUser user) {
        SysDepartment department = user.getDepartmentId() == null ? null : departmentMapper.selectById(user.getDepartmentId());
        List<SysRole> roles = roleMapper.selectByUserId(user.getId());
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .departmentName(department == null ? null : department.getDeptName())
                .roleNames(roles.stream().map(SysRole::getRoleName).toList())
                .roleCodes(roles.stream().map(SysRole::getRoleCode).toList())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
