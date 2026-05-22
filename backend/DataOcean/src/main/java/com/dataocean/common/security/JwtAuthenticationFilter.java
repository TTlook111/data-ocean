package com.dataocean.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 * <p>
 * 在每次请求中从 Authorization 头提取 JWT 令牌，验证其有效性后
 * 将用户认证信息设置到 Spring Security 上下文中。
 * 支持令牌黑名单（退出登录）和令牌版本号（强制下线）机制。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsServiceImpl userDetailsService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 执行 JWT 认证过滤逻辑
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain 过滤器链
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // 从请求头中解析 JWT 令牌
        String token = resolveToken(request);

        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                // 检查令牌是否在黑名单中（已退出登录）
                String jti = jwtTokenProvider.getTokenId(token);
                Boolean blacklisted = stringRedisTemplate.hasKey("jwt:blacklist:" + jti);

                if (!Boolean.TRUE.equals(blacklisted) && jwtTokenProvider.validateToken(token)) {
                    // 校验令牌版本号是否与 Redis 中的当前版本一致
                    Long userId = jwtTokenProvider.getUserId(token);
                    Long tokenVersion = jwtTokenProvider.getTokenVersion(token);
                    String currentVersion = stringRedisTemplate.opsForValue().get("user:token-version:" + userId);

                    if (currentVersion != null && !currentVersion.equals(String.valueOf(tokenVersion))) {
                        // 令牌版本不匹配，说明用户已被强制下线
                        log.warn("JWT 被拒绝：令牌版本已失效 userId={} tokenVersion={} currentVersion={}",
                                userId, tokenVersion, currentVersion);
                        filterChain.doFilter(request, response);
                        return;
                    }

                    // 加载用户详情并设置认证信息到安全上下文
                    String username = jwtTokenProvider.getUsernameFromToken(token);
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("JWT 认证成功 userId={} username={}", userId, username);
                } else if (Boolean.TRUE.equals(blacklisted)) {
                    log.warn("JWT 被拒绝：令牌已在黑名单 jti={}", jti);
                }
            } catch (JwtException | IllegalArgumentException exception) {
                // 令牌解析失败，清除安全上下文
                log.warn("JWT 被拒绝：令牌无效，原因={}", exception.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        // 继续执行过滤器链
        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头中解析 Bearer Token
     *
     * @param request HTTP 请求
     * @return JWT 字符串，不存在则返回 null
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
