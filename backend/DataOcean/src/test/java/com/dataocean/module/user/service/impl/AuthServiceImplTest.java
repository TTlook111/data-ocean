package com.dataocean.module.user.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.JwtTokenProvider;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserDetailsServiceImpl;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.entity.dto.LoginDTO;
import com.dataocean.module.user.entity.vo.LoginVO;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.service.CaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserDetailsServiceImpl userDetailsService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                userMapper,
                userDetailsService,
                passwordEncoder,
                jwtTokenProvider,
                stringRedisTemplate,
                captchaService
        );
        ReflectionTestUtils.setField(authService, "maxAttempts", 3);
        ReflectionTestUtils.setField(authService, "lockDurationSeconds", 1800L);
    }

    @Test
    void failedPasswordLocksAccountForConfiguredDuration() {
        SysUser user = user(SysUser.STATUS_NORMAL);
        when(captchaService.verify("captcha-key", "ABCD")).thenReturn(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("bad-password", "encoded-password")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("login:fail:admin")).thenReturn(3L);

        assertThatThrownBy(() -> authService.login(loginRequest("bad-password")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getCode()).isEqualTo(401);
                    assertThat(businessException.getMessage()).isEqualTo("用户名或密码错误");
                });

        assertThat(user.getStatus()).isEqualTo(SysUser.STATUS_LOCKED);
        verify(stringRedisTemplate).expire("login:fail:admin", Duration.ofSeconds(1800));
        verify(valueOperations).set("login:auto-lock:ttl:admin", "1", 1800L, TimeUnit.SECONDS);
        verify(valueOperations).set("login:auto-lock:admin", "7");
        verify(valueOperations).increment("user:token-version:7");
        verify(userMapper).updateById(user);
    }

    @Test
    void automaticLockRejectsLoginUntilDurationExpires() {
        SysUser user = user(SysUser.STATUS_LOCKED);
        when(captchaService.verify("captcha-key", "ABCD")).thenReturn(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(stringRedisTemplate.hasKey("login:auto-lock:admin")).thenReturn(true);
        when(stringRedisTemplate.getExpire("login:auto-lock:ttl:admin", TimeUnit.SECONDS)).thenReturn(61L);

        assertThatThrownBy(() -> authService.login(loginRequest("correct-password")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getCode()).isEqualTo(403);
                    assertThat(businessException.getMessage()).isEqualTo("账号已被锁定，请2分钟后再试");
                });

        verify(passwordEncoder, never()).matches(any(), any());
        verify(userMapper, never()).updateById(user);
    }

    @Test
    void expiredAutomaticLockIsClearedAndLoginContinues() {
        SysUser user = user(SysUser.STATUS_LOCKED);
        LoginUser loginUser = loginUser();
        when(captchaService.verify("captcha-key", "ABCD")).thenReturn(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(stringRedisTemplate.hasKey("login:auto-lock:admin")).thenReturn(true);
        when(stringRedisTemplate.getExpire("login:auto-lock:ttl:admin", TimeUnit.SECONDS)).thenReturn(-2L);
        when(passwordEncoder.matches("correct-password", "encoded-password")).thenReturn(true);
        when(userDetailsService.loadUserByUsername("admin")).thenReturn(loginUser);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:token-version:7")).thenReturn("4");
        when(jwtTokenProvider.generateToken(loginUser, 4L)).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(86400L);

        LoginVO result = authService.login(loginRequest("correct-password"));

        assertThat(result.getToken()).isEqualTo("jwt-token");
        assertThat(result.getUsername()).isEqualTo("admin");
        assertThat(user.getStatus()).isEqualTo(SysUser.STATUS_NORMAL);
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(stringRedisTemplate, atLeastOnce()).delete("login:fail:admin");
        verify(stringRedisTemplate).delete("login:auto-lock:ttl:admin");
        verify(stringRedisTemplate).delete("login:auto-lock:admin");
        verify(userMapper, times(2)).updateById(user);
    }

    @Test
    void manuallyLockedAccountDoesNotUseAutomaticUnlockMarker() {
        SysUser user = user(SysUser.STATUS_LOCKED);
        when(captchaService.verify("captcha-key", "ABCD")).thenReturn(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(stringRedisTemplate.hasKey("login:auto-lock:admin")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("correct-password")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getCode()).isEqualTo(403);
                    assertThat(businessException.getMessage()).isEqualTo("账号已被锁定");
                });

        verify(passwordEncoder, never()).matches(any(), any());
        verify(userMapper, never()).updateById(user);
    }

    private LoginDTO loginRequest(String password) {
        LoginDTO request = new LoginDTO();
        request.setUsername("admin");
        request.setPassword(password);
        request.setCaptchaKey("captcha-key");
        request.setCaptchaCode("ABCD");
        return request;
    }

    private SysUser user(int status) {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("admin");
        user.setPasswordHash("encoded-password");
        user.setRealName("管理员");
        user.setStatus(status);
        user.setPasswordChanged(1);
        user.setDeleted(0);
        return user;
    }

    private LoginUser loginUser() {
        return new LoginUser(
                7L,
                "admin",
                "encoded-password",
                "管理员",
                List.of("ADMIN"),
                List.of("user:manage"),
                List.of(
                        new SimpleGrantedAuthority("user:manage"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                )
        );
    }
}
