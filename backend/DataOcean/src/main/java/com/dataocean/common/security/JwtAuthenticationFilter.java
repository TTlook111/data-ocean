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

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsServiceImpl userDetailsService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String jti = jwtTokenProvider.getTokenId(token);
                Boolean blacklisted = stringRedisTemplate.hasKey("jwt:blacklist:" + jti);
                if (!Boolean.TRUE.equals(blacklisted) && jwtTokenProvider.validateToken(token)) {
                    Long userId = jwtTokenProvider.getUserId(token);
                    Long tokenVersion = jwtTokenProvider.getTokenVersion(token);
                    String currentVersion = stringRedisTemplate.opsForValue().get("user:token-version:" + userId);
                    if (currentVersion != null && !currentVersion.equals(String.valueOf(tokenVersion))) {
                        log.warn("JWT 被拒绝：令牌版本已失效 userId={} tokenVersion={} currentVersion={}",
                                userId, tokenVersion, currentVersion);
                        filterChain.doFilter(request, response);
                        return;
                    }
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
                log.warn("JWT 被拒绝：令牌无效，原因={}", exception.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
