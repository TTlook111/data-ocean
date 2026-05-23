package com.dataocean.common.config;

import com.dataocean.common.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dataocean.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 安全配置类。
 * <p>
 * 配置无状态（JWT）认证体系，包括：
 * <ul>
 *   <li>禁用 CSRF（前后端分离 + JWT 无需 CSRF 防护）</li>
 *   <li>无状态会话管理（不创建 HttpSession）</li>
 *   <li>URL 级别的访问控制规则</li>
 *   <li>自定义 401/403 异常响应</li>
 *   <li>JWT 过滤器注册（在 UsernamePasswordAuthenticationFilter 之前执行）</li>
 * </ul>
 * </p>
 *
 * @author dataocean
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    /** JWT 认证过滤器，负责从请求头解析和验证 Token */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** JSON 序列化工具，用于异常响应体的序列化 */
    private final ObjectMapper objectMapper;

    /**
     * 配置安全过滤器链。
     * <p>
     * 定义请求的认证和授权规则，注册 JWT 过滤器，
     * 并自定义未认证（401）和无权限（403）的响应格式。
     * </p>
     *
     * @param http Spring Security 的 HttpSecurity 构建器
     * @return 构建完成的 SecurityFilterChain
     * @throws Exception 配置过程中的异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("配置无状态安全过滤器链");
        http.csrf(AbstractHttpConfigurer::disable)
                // 设置无状态会话策略，不使用 Session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置 URL 访问规则
                .authorizeHttpRequests(auth -> auth
                        // 登录和验证码接口允许匿名访问
                        .requestMatchers("/api/auth/login", "/api/auth/captcha").permitAll()
                        // 认证相关接口需要已登录
                        .requestMatchers("/api/auth/logout", "/api/auth/me", "/api/auth/password", "/api/auth/profile").authenticated()
                        // 数据源和管理端接口需要已登录
                        .requestMatchers("/api/datasources/**").authenticated()
                        .requestMatchers("/api/admin/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        // 静态资源和错误页面放行
                        .requestMatchers("/error", "/favicon.ico").permitAll()
                        // 其他请求默认需要认证（防止 Actuator 等端点意外暴露）
                        .anyRequest().authenticated()
                )
                // 自定义异常处理：未认证和无权限时返回 JSON 格式错误信息
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(Result.error(401, "未登录或登录已过期")));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(Result.error(403, "无访问权限")));
                        })
                )
                // 在 UsernamePasswordAuthenticationFilter 之前插入 JWT 过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 暴露 AuthenticationManager Bean，供登录接口手动触发认证使用。
     *
     * @param configuration Spring Security 认证配置
     * @return AuthenticationManager 实例
     * @throws Exception 获取过程中的异常
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 配置密码编码器。
     * <p>
     * 使用 BCrypt 算法，强度因子为 10（默认值），
     * 兼顾安全性和性能。
     * </p>
     *
     * @return BCryptPasswordEncoder 实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        log.info("配置 BCrypt 密码编码器 strength=10");
        return new BCryptPasswordEncoder(10);
    }
}
