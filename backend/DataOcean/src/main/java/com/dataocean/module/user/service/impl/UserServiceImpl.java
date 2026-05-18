package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.user.entity.req.UserCreateRequest;
import com.dataocean.module.user.entity.query.UserQueryRequest;
import com.dataocean.module.user.entity.req.UserUpdateRequest;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.entity.SysUserRole;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.mapper.UserRoleMapper;
import com.dataocean.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    @Transactional
    @Override
    public Long createUser(UserCreateRequest request) {
        log.info("开始创建用户 username={} departmentId={} roleIds={}",
                request.getUsername(), request.getDepartmentId(), request.getRoleIds());
        ensureUsernameAvailable(request.getUsername());
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
        log.info("用户创建成功 userId={} username={} roleCount={}", user.getId(), user.getUsername(), request.getRoleIds().size());
        return user.getId();
    }

    @Transactional
    @Override
    public void updateUser(Long id, UserUpdateRequest request) {
        log.info("开始更新用户 userId={} departmentId={} roleIds={}", id, request.getDepartmentId(), request.getRoleIds());
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
        log.info("用户更新成功 userId={} username={}", id, user.getUsername());
    }

    @Transactional
    @Override
    public void deleteUser(Long id) {
        log.info("开始删除用户 userId={}", id);
        if (Long.valueOf(1L).equals(id)) {
            throw new BusinessException("不允许删除超级管理员");
        }
        requireUser(id);
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        stringRedisTemplate.opsForValue().increment(tokenVersionKey(id));
        log.info("用户删除成功 userId={}", id);
    }

    @Override
    public UserVO getUserById(Long id) {
        return toVO(requireUser(id));
    }

    @Override
    public Page<UserVO> listUsers(UserQueryRequest request) {
        log.debug("查询用户列表 username={} realName={} departmentId={} status={} page={} pageSize={}",
                request.getUsername(), request.getRealName(), request.getDepartmentId(), request.getStatus(),
                request.resolvedPage(), request.resolvedPageSize());
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
    @Override
    public void updateStatus(Long id, Integer status) {
        SysUser user = requireUser(id);
        Integer oldStatus = user.getStatus();
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
            // 禁用或锁定账号时必须让该用户已签发的 JWT 立即失效。
            stringRedisTemplate.opsForValue().increment(tokenVersionKey(id));
        }
        log.info("用户状态更新成功 userId={} username={} oldStatus={} newStatus={}",
                id, user.getUsername(), oldStatus, status);
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            log.warn("用户查询失败：用户不存在 userId={}", id);
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    private void ensureUsernameAvailable(String username) {
        SysUser existing = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            throw new BusinessException("用户名已存在");
        }
    }

    private void validateDepartment(Long departmentId) {
        if (departmentId == null) {
            return;
        }
        // 数据库不创建外键，部门关联有效性统一在业务层校验。
        SysDepartment department = departmentMapper.selectById(departmentId);
        if (department == null || !Integer.valueOf(1).equals(department.getStatus())) {
            throw new BusinessException("部门不存在或已禁用");
        }
    }

    private void validateRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BusinessException("至少选择一个角色");
        }
        // 数据库不创建外键，角色关联有效性统一在业务层校验。
        List<SysRole> roles = roleMapper.selectByIds(roleIds);
        if (roles.size() != Set.copyOf(roleIds).size() || roles.stream().anyMatch(role -> !Integer.valueOf(1).equals(role.getStatus()))) {
            throw new BusinessException("角色不存在或已禁用");
        }
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        log.debug("绑定用户角色 userId={} roleIds={}", userId, roleIds);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        for (Long roleId : roleIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
    }

    private String tokenVersionKey(Long userId) {
        return "user:token-version:" + userId;
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
                .roleIds(roles.stream().map(SysRole::getId).toList())
                .roleNames(roles.stream().map(SysRole::getRoleName).toList())
                .roleCodes(roles.stream().map(SysRole::getRoleCode).toList())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
