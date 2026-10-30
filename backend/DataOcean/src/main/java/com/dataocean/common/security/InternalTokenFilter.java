package com.dataocean.common.security;

import com.dataocean.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 内部服务间调用认证过滤器
 * <p>
 * 统一保护全部 {@code /internal/**}，取代此前分散在三个 Controller 里、
 * 且守卫口径互不一致的方法内校验。
 * </p>
 * <p>
 * 与 {@code SecurityConfig} 的配合是刻意的双层设计：本过滤器通过校验后会写入
 * {@link #AUTHORITY}，而授权规则要求该权限（而非 {@code permitAll}）。
 * 这样即使本过滤器因配置原因未能执行，授权层也会拒绝请求（fail-closed），
 * 不会退化成"跳过过滤器即放行"。路径匹配由 {@link #internalPathMatcher()} 单点定义，
 * 两侧不会发散。
 * </p>
 *
 * @author dataocean
 */
@Slf4j
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 内部调用认证请求头 */
    public static final String HEADER_NAME = "X-Internal-Token";

    /** 通过校验后写入的授权标识；{@code SecurityConfig} 要求同一个常量 */
    public static final String AUTHORITY = "ROLE_INTERNAL";

    private static final String INTERNAL_PATH_PATTERN = "/internal/**";

    /** 内部服务在安全上下文中的主体名 */
    private static final String PRINCIPAL_NAME = "internal-service";

    private final InternalTokenValidator validator;
    private final ObjectMapper objectMapper;
    private final RequestMatcher internalPaths = new AntPathRequestMatcher(INTERNAL_PATH_PATTERN);

    public InternalTokenFilter(InternalTokenValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /**
     * 暴露内部路径匹配器，供 {@code SecurityConfig} 的授权规则复用。
     * <p>
     * 路径模式与匹配语义只在此处定义一次，避免过滤器与授权规则各写一份而发散。
     * </p>
     *
     * @return 匹配 {@code /internal/**} 的匹配器
     */
    public RequestMatcher internalPathMatcher() {
        return internalPaths;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !internalPaths.matches(request);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (validator.matches(request.getHeader(HEADER_NAME))) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    PRINCIPAL_NAME, null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }

        // 不记录收到的令牌或其片段
        log.warn("内部接口非法访问 path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(HttpServletResponse.SC_FORBIDDEN, "内部接口禁止外部访问")));
    }
}
