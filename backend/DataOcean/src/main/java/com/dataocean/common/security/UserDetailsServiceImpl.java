package com.dataocean.common.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0));
        if (user == null) {
            log.warn("加载用户详情失败：用户不存在 username={}", username);
            throw new UsernameNotFoundException("用户不存在");
        }
        if (!Integer.valueOf(SysUser.STATUS_NORMAL).equals(user.getStatus())) {
            log.warn("加载用户详情失败：用户状态不可用 userId={} username={} status={}",
                    user.getId(), user.getUsername(), user.getStatus());
            throw new UsernameNotFoundException("用户不可用");
        }

        List<SysRole> roles = roleMapper.selectByUserId(user.getId());
        List<String> roleCodes = roles.stream().map(SysRole::getRoleCode).toList();
        List<String> permissions = userMapper.selectPermissionCodesByUserId(user.getId());
        if (permissions.contains("*")) {
            // 将通配权限展开为当前方法鉴权实际使用的权限码。
            permissions = List.of("*", "user:manage", "role:view", "department:manage", "datasource:manage");
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        roleCodes.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        log.debug("加载用户详情成功 userId={} username={} roleCount={} permissionCount={}",
                user.getId(), user.getUsername(), roleCodes.size(), permissions.size());

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
