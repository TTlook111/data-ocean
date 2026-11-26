package com.dataocean.module.iam.config;

import com.dataocean.infrastructure.security.ApiErrorWriter;
import com.dataocean.module.audit.MutationFailureFilter;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.security.CurrentIdentityFilter;

import java.time.Clock;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 装配 Cookie 会话、CSRF 与当前身份核验，保持公开入口显式可查。
 */
@Configuration
@EnableRedisIndexedHttpSession(
        redisNamespace = "${spring.session.redis.namespace:dataocean:session}",
        maxInactiveIntervalInSeconds = 28800)
public class IamSecurityConfiguration {

    /**
     * 使用现有 Redis 配置，不由应用启动擅自修改共享服务设置。
     */
    @Bean
    ConfigureRedisAction configureRedisAction() {
        return ConfigureRedisAction.NO_OP;
    }

    /**
     * 配置带算法标识的成熟密码编码器，保留完整合法密码参与散列。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(
                "pbkdf2@SpringSecurity_v5_8",
                Map.of("pbkdf2@SpringSecurity_v5_8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    /**
     * 配置每次请求恢复与显式保存可信身份的会话上下文仓库。
     */
    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 配置浏览器会话中的 CSRF 存储，与安全过滤链共用。
     */
    @Bean
    HttpSessionCsrfTokenRepository csrfRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /**
     * 统一设置登录 Cookie 的名称、作用路径和浏览器保护属性。
     */
    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("DATAOCEAN_SESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        return cookie;
    }

    /**
     * 统一装配会话、CSRF、认证失败和拒绝访问的安全过滤链。
     *
     * @throws Exception 安全框架构建失败时由启动边界中止，不能忽略失败后开放入口
     */
    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            UserMapper users,
            ApiErrorWriter errors,
            AuditService audits,
            Clock clock,
            HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrf,
            @Value("${dataocean.session-idle-seconds}") long idle,
            @Value("${dataocean.session-absolute-seconds}") long absolute)
            throws Exception {
        var handler = new CsrfTokenRequestAttributeHandler();
        return http.securityContext(
                        context -> context.securityContextRepository(contexts).requireExplicitSave(true))
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
                .authorizeHttpRequests(
                        auth -> auth.requestMatchers(CurrentIdentityFilter.PUBLIC_PATHS.toArray(String[]::new))
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .exceptionHandling(config -> config.authenticationEntryPoint(
                                (req, res, error) -> errors.write(res, 401, "AUTH_REQUIRED", "请先登录"))
                        .accessDeniedHandler((req, res, error) -> errors.write(
                                res,
                                403,
                                error instanceof CsrfException ? "CSRF_INVALID" : "PERMISSION_DENIED",
                                error instanceof CsrfException ? "请求校验已失效，请刷新后重试" : "当前无权执行此操作")))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .addFilterAfter(new MutationFailureFilter(audits), SecurityContextHolderFilter.class)
                .addFilterAfter(
                        new CurrentIdentityFilter(users, errors, clock, idle, absolute), MutationFailureFilter.class)
                .build();
    }
}
