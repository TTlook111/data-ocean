package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.JwtTokenProvider;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.common.security.UserDetailsServiceImpl;
import com.dataocean.module.user.entity.dto.ChangePasswordDTO;
import com.dataocean.module.user.entity.dto.LoginDTO;
import com.dataocean.module.user.entity.dto.ProfileUpdateDTO;
import com.dataocean.module.user.entity.vo.CurrentUserVO;
import com.dataocean.module.user.entity.vo.LoginVO;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.service.AuthService;
import com.dataocean.module.user.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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

/**
 * 认证服务实现。
 * <p>
 * 处理验证码校验、登录失败锁定、JWT 签发与黑名单、密码修改和个人资料更新。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final CaptchaService captchaService;
    private static final Pattern PASSWORD_COMPLEXITY = Pattern.compile("^(?=.*[a-zA-Z])(?=.*\\d).{8,32}$");
    private static final String TOKEN_VERSION_PREFIX = "user:token-version:";
    private static final String FAILED_LOGIN_PREFIX = "login:fail:";
    private static final String AUTO_LOCK_MARKER_PREFIX = "login:auto-lock:";
    private static final String AUTO_LOCK_TTL_PREFIX = "login:auto-lock:ttl:";

    @Value("${security.login.max-attempts}")
    private int maxAttempts;

    @Value("${security.login.lock-duration}")
    private long lockDurationSeconds;

    /**
     * {@inheritDoc}
     */
    @Override
    public LoginVO login(LoginDTO request) {
        // 验证码校验（在密码校验之前，避免暴力枚举）
        if (!captchaService.verify(request.getCaptchaKey(), request.getCaptchaCode())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }

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
            unlockIfAutomaticLockExpired(user);
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
        return LoginVO.builder()
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public CurrentUserVO currentUserInfo() {
        LoginUser loginUser = UserContext.currentUser();
        SysUser user = requireCurrentUserEntity(loginUser.getUserId());
        log.debug("当前登录用户信息查询完成 userId={} username={}", user.getId(), user.getUsername());
        return CurrentUserVO.builder()
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

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void changePassword(ChangePasswordDTO request) {
        LoginUser loginUser = UserContext.currentUser();
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

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void updateProfile(ProfileUpdateDTO request) {
        LoginUser loginUser = UserContext.currentUser();
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
            stringRedisTemplate.opsForValue().set(autoLockTtlKey(user.getUsername()), "1", lockDurationSeconds, TimeUnit.SECONDS);
            stringRedisTemplate.opsForValue().set(autoLockMarkerKey(user.getUsername()), String.valueOf(user.getId()));
            // 令牌版本号是让用户已签发令牌全部失效的统一开关。
            stringRedisTemplate.opsForValue().increment(tokenVersionKey(user.getId()));
            log.warn("用户连续登录失败后被锁定 userId={} username={} lockDurationSeconds={}",
                    user.getId(), user.getUsername(), lockDurationSeconds);
        }
    }

    private void clearFailedLogin(String username) {
        stringRedisTemplate.delete(failedLoginKey(username));
    }

    private void unlockIfAutomaticLockExpired(SysUser user) {
        Boolean autoLocked = stringRedisTemplate.hasKey(autoLockMarkerKey(user.getUsername()));
        if (!Boolean.TRUE.equals(autoLocked)) {
            log.warn("用户登录被拒绝：账号已锁定 userId={} username={}", user.getId(), user.getUsername());
            throw new BusinessException(403, "账号已被锁定");
        }

        Long remainingSeconds = stringRedisTemplate.getExpire(autoLockTtlKey(user.getUsername()), TimeUnit.SECONDS);
        if (remainingSeconds != null && remainingSeconds > 0) {
            log.warn("用户登录被拒绝：账号自动锁定未到期 userId={} username={} remainingSeconds={}",
                    user.getId(), user.getUsername(), remainingSeconds);
            throw new BusinessException(403, "账号已被锁定，请" + formatRemainingLockTime(remainingSeconds) + "后再试");
        }

        user.setStatus(SysUser.STATUS_NORMAL);
        userMapper.updateById(user);
        clearLoginLock(user.getUsername());
        log.info("用户自动锁定已到期，已恢复正常状态 userId={} username={}", user.getId(), user.getUsername());
    }

    private void clearLoginLock(String username) {
        stringRedisTemplate.delete(failedLoginKey(username));
        stringRedisTemplate.delete(autoLockTtlKey(username));
        stringRedisTemplate.delete(autoLockMarkerKey(username));
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
        return TOKEN_VERSION_PREFIX + userId;
    }

    private String failedLoginKey(String username) {
        return FAILED_LOGIN_PREFIX + username;
    }

    private String autoLockMarkerKey(String username) {
        return AUTO_LOCK_MARKER_PREFIX + username;
    }

    private String autoLockTtlKey(String username) {
        return AUTO_LOCK_TTL_PREFIX + username;
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

    private String formatRemainingLockTime(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return minutes + "分钟";
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }
}
