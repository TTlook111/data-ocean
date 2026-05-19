package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.JwtTokenProvider;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserDetailsServiceImpl;
import com.dataocean.module.user.entity.req.ChangePasswordRequest;
import com.dataocean.module.user.entity.req.LoginRequest;
import com.dataocean.module.user.entity.req.ProfileUpdateRequest;
import com.dataocean.module.user.entity.vo.CurrentUserResponse;
import com.dataocean.module.user.entity.vo.LoginResponse;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private static final Pattern PASSWORD_COMPLEXITY = Pattern.compile("^(?=.*[a-zA-Z])(?=.*\\d).{8,32}$");

    @Value("${security.login.max-attempts}")
    private int maxAttempts;

    @Value("${security.login.lock-duration}")
    private long lockDurationSeconds;

    @Override
    public LoginResponse login(LoginRequest request) {
        log.info("用户登录尝试 username={}", request.getUsername());
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())
                .eq(SysUser::getDeleted, 0));
        if (user == null) {
            log.warn("用户登录失败：账号不存在 username={}", request.getUsername());
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (Integer.valueOf(SysUser.STATUS_DISABLED).equals(user.getStatus())) {
            log.warn("用户登录被拒绝：账号已禁用 userId={} username={}", user.getId(), user.getUsername());
            throw new BusinessException(403, "账号已被禁用");
        }
        if (Integer.valueOf(SysUser.STATUS_LOCKED).equals(user.getStatus())) {
            log.warn("用户登录被拒绝：账号已锁定 userId={} username={}", user.getId(), user.getUsername());
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
        log.info("用户登录成功 userId={} username={} roles={}", user.getId(), user.getUsername(), loginUser.getRoles());
        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getExpirationSeconds())
                .userId(loginUser.getUserId())
                .username(loginUser.getUsername())
                .realName(loginUser.getRealName())
                .passwordChanged(isPasswordChanged(user))
                .roles(loginUser.getRoles())
                .permissions(loginUser.getPermissions())
                .build();
    }

    @Override
    public void logout(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token == null) {
            log.debug("忽略登出请求：Authorization 请求头为空或不是 Bearer 格式");
            return;
        }
        String jti = jwtTokenProvider.getTokenId(token);
        Date expiresAt = jwtTokenProvider.getExpiration(token);
        long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(), LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneId.systemDefault()));
        if (ttl > 0) {
            stringRedisTemplate.opsForValue().set("jwt:blacklist:" + jti, "1", ttl, TimeUnit.SECONDS);
            log.info("用户登出成功，JWT 已加入黑名单 jti={} ttlSeconds={}", jti, ttl);
        } else {
            log.debug("登出时 JWT 已过期 jti={}", jti);
        }
    }

    @Override
    public LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BusinessException(401, "未登录");
        }
        return loginUser;
    }

    @Override
    public CurrentUserResponse currentUserInfo() {
        LoginUser loginUser = currentUser();
        SysUser user = requireCurrentUserEntity(loginUser.getUserId());
        log.debug("当前登录用户信息查询完成 userId={} username={}", user.getId(), user.getUsername());
        return CurrentUserResponse.builder()
                .id(loginUser.getUserId())
                .username(loginUser.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .passwordChanged(isPasswordChanged(user))
                .roles(loginUser.getRoles())
                .permissions(loginUser.getPermissions())
                .build();
    }

    @Transactional
    @Override
    public void changePassword(ChangePasswordRequest request) {
        LoginUser loginUser = currentUser();
        SysUser user = requireCurrentUserEntity(loginUser.getUserId());
        log.info("用户发起修改密码 userId={} username={}", user.getId(), user.getUsername());
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            log.warn("用户修改密码失败：旧密码不正确 userId={} username={}", user.getId(), user.getUsername());
            throw new BusinessException("旧密码不正确");
        }
        validatePasswordComplexity(request.getNewPassword());
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("新密码不能与旧密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChanged(1);
        userMapper.updateById(user);
        stringRedisTemplate.opsForValue().increment(tokenVersionKey(user.getId()));
        log.info("用户修改密码成功，已刷新令牌版本 userId={} username={}", user.getId(), user.getUsername());
    }

    @Transactional
    @Override
    public void updateProfile(ProfileUpdateRequest request) {
        LoginUser loginUser = currentUser();
        SysUser user = requireCurrentUserEntity(loginUser.getUserId());
        log.info("用户发起个人资料修改 userId={} username={}", user.getId(), user.getUsername());
        if (request.getRealName() != null) {
            user.setRealName(request.getRealName());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        userMapper.updateById(user);
        log.info("用户个人资料修改成功 userId={} username={}", user.getId(), user.getUsername());
    }

    private void recordFailedLogin(SysUser user) {
        String key = failedLoginKey(user.getUsername());
        Long attempts = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, Duration.ofSeconds(lockDurationSeconds));
        log.warn("用户登录密码错误 userId={} username={} attempts={} maxAttempts={}",
                user.getId(), user.getUsername(), attempts, maxAttempts);
        if (attempts != null && attempts >= maxAttempts) {
            user.setStatus(SysUser.STATUS_LOCKED);
            userMapper.updateById(user);
            // 令牌版本号是让用户已签发令牌全部失效的统一开关。
            stringRedisTemplate.opsForValue().increment(tokenVersionKey(user.getId()));
            log.warn("用户连续登录失败后被锁定 userId={} username={} lockDurationSeconds={}",
                    user.getId(), user.getUsername(), lockDurationSeconds);
        }
    }

    private void clearFailedLogin(String username) {
        stringRedisTemplate.delete(failedLoginKey(username));
    }

    private long ensureTokenVersion(Long userId) {
        String key = tokenVersionKey(userId);
        String version = stringRedisTemplate.opsForValue().get(key);
        if (version != null) {
            return Long.parseLong(version);
        }
        stringRedisTemplate.opsForValue().set(key, "0");
        return 0L;
    }

    private String tokenVersionKey(Long userId) {
        return "user:token-version:" + userId;
    }

    private String failedLoginKey(String username) {
        return "login:fail:" + username;
    }

    private SysUser requireCurrentUserEntity(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("当前登录用户不存在 userId={}", userId);
            throw new BusinessException(401, "当前登录用户不存在");
        }
        return user;
    }

    private void validatePasswordComplexity(String password) {
        if (!PASSWORD_COMPLEXITY.matcher(password).matches()) {
            throw new BusinessException("新密码需为8-32位且至少包含字母和数字");
        }
    }

    private boolean isPasswordChanged(SysUser user) {
        return Integer.valueOf(1).equals(user.getPasswordChanged());
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }
}
