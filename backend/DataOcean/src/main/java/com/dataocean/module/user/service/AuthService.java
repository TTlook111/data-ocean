package com.dataocean.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.JwtTokenProvider;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserDetailsServiceImpl;
import com.dataocean.module.user.dto.LoginRequest;
import com.dataocean.module.user.dto.LoginResponse;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${security.login.max-attempts}")
    private int maxAttempts;

    @Value("${security.login.lock-duration}")
    private long lockDurationSeconds;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())
                .eq(SysUser::getDeleted, 0));
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (Integer.valueOf(SysUser.STATUS_DISABLED).equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用");
        }
        if (Integer.valueOf(SysUser.STATUS_LOCKED).equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被锁定");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordFailedLogin(user);
            throw new BusinessException(401, "用户名或密码错误");
        }

        clearFailedLogin(user.getUsername());
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        LoginUser loginUser = (LoginUser) userDetailsService.loadUserByUsername(user.getUsername());
        long tokenVersion = ensureTokenVersion(user.getId());
        String token = jwtTokenProvider.generateToken(loginUser, tokenVersion);
        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getExpirationSeconds())
                .userId(loginUser.getUserId())
                .username(loginUser.getUsername())
                .realName(loginUser.getRealName())
                .roles(loginUser.getRoles())
                .permissions(loginUser.getPermissions())
                .build();
    }

    public void logout(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token == null) {
            return;
        }
        String jti = jwtTokenProvider.getTokenId(token);
        Date expiresAt = jwtTokenProvider.getExpiration(token);
        long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(), LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneId.systemDefault()));
        if (ttl > 0) {
            stringRedisTemplate.opsForValue().set("jwt:blacklist:" + jti, "1", ttl, TimeUnit.SECONDS);
        }
    }

    public LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BusinessException(401, "未登录");
        }
        return loginUser;
    }

    private void recordFailedLogin(SysUser user) {
        String key = failedLoginKey(user.getUsername());
        Long attempts = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, Duration.ofSeconds(lockDurationSeconds));
        if (attempts != null && attempts >= maxAttempts) {
            user.setStatus(SysUser.STATUS_LOCKED);
            userMapper.updateById(user);
        }
    }

    private void clearFailedLogin(String username) {
        stringRedisTemplate.delete(failedLoginKey(username));
    }

    private long ensureTokenVersion(Long userId) {
        String key = "user:token-version:" + userId;
        String version = stringRedisTemplate.opsForValue().get(key);
        if (version != null) {
            return Long.parseLong(version);
        }
        stringRedisTemplate.opsForValue().set(key, "0");
        return 0L;
    }

    private String failedLoginKey(String username) {
        return "login:fail:" + username;
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }
}
