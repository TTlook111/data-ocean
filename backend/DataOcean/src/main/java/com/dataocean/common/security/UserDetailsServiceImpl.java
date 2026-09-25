package com.dataocean.common.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring Security 用户详情加载服务实现。
 * <p>
 * 认证只加载账号身份和状态。业务授权不放入 Spring authorities，
 * 由 IAM-SIMPLE-1 Resolver/Guard/Aspect 按 userId 动态判定。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

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

        // 该 authority 只表达“已完成身份认证”，不表达任何业务权限。
        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("AUTHENTICATED_USER"));
        log.debug("加载用户详情成功 userId={} username={}", user.getId(), user.getUsername());

        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRealName(),
                authorities
        );
    }
}
