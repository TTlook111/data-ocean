package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.user.entity.SysPermission;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysRolePermission;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.entity.SysUserRole;
import com.dataocean.module.user.entity.dto.RoleSaveDTO;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.mapper.PermissionMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.RolePermissionMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.mapper.UserRoleMapper;
import com.dataocean.module.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色管理业务实现类。
 * <p>
 * 提供角色列表查询、角色成员查看/分配/移除等功能。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;

    /**
     * 查询所有启用状态的角色列表，按 ID 升序排列
     */
    @Override
    public List<SysRole> listEnabledRoles() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getId));
        log.debug("查询启用角色列表完成 count={}", roles.size());
        return roles;
    }

    @Override
    public List<SysRole> listAllRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
    }

    @Transactional
    @Override
    public Long createRole(RoleSaveDTO request) {
        ensureRoleCodeAvailable(request.getRoleCode(), null);
        SysRole role = new SysRole();
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName());
        role.setDescription(request.getDescription());
        role.setStatus(normalizeStatus(request.getStatus()));
        roleMapper.insert(role);
        updateRolePermissions(role.getId(), request.getPermissionIds());
        return role.getId();
    }

    @Transactional
    @Override
    public void updateRole(Long roleId, RoleSaveDTO request) {
        SysRole role = requireRole(roleId);
        if ("ADMIN".equals(role.getRoleCode()) && request.getStatus() != null && request.getStatus() != 1) {
            throw new BusinessException("超级管理员角色不能禁用");
        }
        ensureRoleCodeAvailable(request.getRoleCode(), roleId);
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName());
        role.setDescription(request.getDescription());
        role.setStatus(normalizeStatus(request.getStatus()));
        roleMapper.updateById(role);
        updateRolePermissions(roleId, request.getPermissionIds());
    }

    @Transactional
    @Override
    public void deleteRole(Long roleId) {
        SysRole role = requireRole(roleId);
        if ("ADMIN".equals(role.getRoleCode())) {
            throw new BusinessException("超级管理员角色不能删除");
        }
        Long memberCount = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId));
        if (memberCount != null && memberCount > 0) {
            throw new BusinessException("角色下仍有成员，无法删除");
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        roleMapper.deleteById(roleId);
    }

    @Override
    public List<Long> listRolePermissionIds(Long roleId) {
        requireRole(roleId);
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .toList();
    }

    @Transactional
    @Override
    public void updateRolePermissions(Long roleId, List<Long> permissionIds) {
        requireRole(roleId);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = permissionIds.stream().distinct().toList();
        List<SysPermission> permissions = permissionMapper.selectByIds(distinctIds);
        if (permissions.size() != distinctIds.size()) {
            throw new BusinessException("权限不存在，请刷新后重试");
        }
        for (Long permissionId : distinctIds) {
            SysRolePermission relation = new SysRolePermission();
            relation.setRoleId(roleId);
            relation.setPermissionId(permissionId);
            rolePermissionMapper.insert(relation);
        }
    }

    /**
     * 查询指定角色下的用户成员列表
     */
    @Override
    public List<UserVO> listUsersByRole(Long roleId) {
        SysRole role = requireEnabledRole(roleId);
        return roleMapper.selectUsersByRoleId(roleId).stream()
                .map(user -> toRoleMemberVO(user, role))
                .toList();
    }

    /**
     * 将用户分配到指定角色，已存在则幂等跳过
     */
    @Transactional
    @Override
    public void assignRoleToUser(Long roleId, Long userId) {
        requireEnabledRole(roleId);
        SysUser user = requireUser(userId);
        if (!Integer.valueOf(SysUser.STATUS_NORMAL).equals(user.getStatus())) {
            throw new BusinessException("用户已禁用或锁定，不能分配角色");
        }

        // 检查是否已存在关联关系，幂等处理
        Long existing = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getUserId, userId));
        if (existing != null && existing > 0) {
            return;
        }

        SysUserRole userRole = new SysUserRole();
        userRole.setRoleId(roleId);
        userRole.setUserId(userId);
        userRoleMapper.insert(userRole);
        log.info("角色成员分配成功 roleId={} userId={}", roleId, userId);
    }

    /**
     * 从指定角色移除用户，ADMIN 角色至少保留一个正常状态成员
     */
    @Transactional
    @Override
    public void removeRoleFromUser(Long roleId, Long userId) {
        SysRole role = requireEnabledRole(roleId);
        SysUser user = requireUser(userId);
        // 保护：ADMIN 角色至少保留一个正常状态的成员
        boolean removingActiveAdmin = "ADMIN".equals(role.getRoleCode())
                && Integer.valueOf(SysUser.STATUS_NORMAL).equals(user.getStatus());
        if (removingActiveAdmin && roleMapper.countActiveUsersByRoleId(roleId) <= 1) {
            throw new BusinessException("至少保留一个正常状态的超级管理员");
        }

        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getUserId, userId));
        log.info("角色成员移除成功 roleId={} userId={}", roleId, userId);
    }

    /** 校验角色存在且启用 */
    private SysRole requireEnabledRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null || !Integer.valueOf(1).equals(role.getStatus())) {
            throw new BusinessException("角色不存在或已禁用");
        }
        return role;
    }

    private SysRole requireRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        return role;
    }

    private void ensureRoleCodeAvailable(String roleCode, Long currentRoleId) {
        SysRole existing = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
        if (existing != null && !existing.getId().equals(currentRoleId)) {
            throw new BusinessException("角色编码已存在");
        }
    }

    private int normalizeStatus(Integer status) {
        if (status == null) {
            return 1;
        }
        if (status != 1 && status != 2) {
            throw new BusinessException("角色状态不合法");
        }
        return status;
    }

    /** 校验用户存在 */
    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    /** 将用户实体转为角色成员视图对象，查询该用户的全部角色信息 */
    private UserVO toRoleMemberVO(SysUser user, SysRole currentRole) {
        // 查询该用户关联的所有启用角色
        List<SysRole> allRoles = roleMapper.selectByUserId(user.getId());
        if (allRoles == null || allRoles.isEmpty()) {
            allRoles = List.of(currentRole);
        }
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .roleIds(allRoles.stream().map(SysRole::getId).toList())
                .roleNames(allRoles.stream().map(SysRole::getRoleName).toList())
                .roleCodes(allRoles.stream().map(SysRole::getRoleCode).toList())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
