package com.dataocean.common.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.PermissionMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security 用户详情加载服务实现。
 * <p>
 * 在认证流程中被 AuthenticationManager 调用，根据用户名从数据库加载用户信息、
 * 角色列表和权限列表，组装为 {@link LoginUser} 返回给安全框架进行密码校验和授权。
 * </p>
 *
 * @author dataocean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    /** 用户 Mapper，用于查询用户基本信息和权限编码 */
    private final UserMapper userMapper;

    /** 角色 Mapper，用于查询用户关联的角色列表 */
    private final RoleMapper roleMapper;

    /** 权限 Mapper，用于查询全量权限编码（超级管理员场景） */
    private final PermissionMapper permissionMapper;

    /**
     * 根据用户名加载用户详情。
     * <p>
     * 加载流程：查询用户 → 校验状态 → 加载角色 → 加载权限 → 组装 LoginUser。
     * 若用户拥有通配权限 "*"，则替换为系统全量权限列表。
     * </p>
     *
     * @param username 登录用户名
     * @return 包含用户信息和权限的 {@link LoginUser} 对象
     * @throws UsernameNotFoundException 用户不存在或状态不可用时抛出
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 按用户名查询未删除的用户记录
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0));
        if (user == null) {
            log.warn("加载用户详情失败：用户不存在 username={}", username);
            throw new UsernameNotFoundException("用户不存在");
        }
        // 校验用户状态是否正常（非禁用/锁定）
        if (!Integer.valueOf(SysUser.STATUS_NORMAL).equals(user.getStatus())) {
            log.warn("加载用户详情失败：用户状态不可用 userId={} username={} status={}",
                    user.getId(), user.getUsername(), user.getStatus());
            throw new UsernameNotFoundException("用户不可用");
        }

        // 查询用户关联的角色列表
        List<SysRole> roles = roleMapper.selectByUserId(user.getId());
        List<String> roleCodes = roles.stream().map(SysRole::getRoleCode).toList();

        // 查询用户权限编码列表；若包含通配符 "*" 则加载全量权限
        List<String> permissions = userMapper.selectPermissionCodesByUserId(user.getId());
        if (permissions.contains("*")) {
            permissions = permissionMapper.selectAllPermissionCodes();
        }

        // 组装 Spring Security 权限对象列表（权限编码 + ROLE_ 前缀的角色编码）
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        roleCodes.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        log.debug("加载用户详情成功 userId={} username={} roleCount={} permissionCount={}",
                user.getId(), user.getUsername(), roleCodes.size(), permissions.size());

        // 构建并返回自定义的 LoginUser 对象
        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRealName(),
                roleCodes,
                permissions,
                authorities
        );
    }
}
