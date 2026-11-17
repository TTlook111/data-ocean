package com.dataocean.module.iam.security;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.security.ApiErrorWriter;
import com.dataocean.module.iam.mapper.UserMapper;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.RequiredArgsConstructor;

/**
 * 每个请求从已恢复的会话重核可信人员，身份清理由外层安全链负责。
 */
@RequiredArgsConstructor
public class CurrentIdentityFilter extends OncePerRequestFilter {

    /**
     * 浏览器认证的明确公开路径，与安全配置共用同一集合。
     */
    public static final Set<String> PUBLIC_PATHS =
            Set.of("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/health", "/error");

    /**
     * 会话关联的权威认证修订属性名。
     */
    public static final String AUTH_VERSION = "dataocean.authenticationVersion";

    /**
     * 会话内首次认证时刻的属性名，用于最长登录时限。
     */
    public static final String AUTH_AT = "dataocean.authenticatedAt";

    /**
     * 会话内最后一次有效用户活动的属性名，被动轮询不更新。
     */
    public static final String ACTIVITY_AT = "dataocean.lastUserActivityAt";

    /**
     * 人员资料、生命周期与受控凭据的访问依赖。
     */
    private final UserMapper users;

    /**
     * 向浏览器写出统一安全错误结构的技术适配。
     */
    private final ApiErrorWriter errors;

    /**
     * 统一 UTC 时钟，用于认证时限和核对凭据到期。
     */
    private final Clock clock;

    /**
     * 允许没有有效用户活动的最长秒数。
     */
    private final long idleSeconds;

    /**
     * 一次登录允许持续的最长秒数。
     */
    private final long absoluteSeconds;

    /**
     * 认证恢复发生依赖故障时拒绝放行，业务调用的异常不在此伪装为登录故障。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Trace-Id", UUID.randomUUID().toString());
        response.setHeader("Cache-Control", "no-store");

        try {
            // 当前层只处理恢复身份所需的存储异常，失败不使用旧允许结果。
            if (!restoreIdentity(request, response)) {
                return;
            }
        } catch (DataAccessException error) {
            errors.write(response, 503, "DEPENDENCY_UNAVAILABLE", "当前无法核验登录与权限，请稍后重试");
            return;
        }

        // 业务链自行进入 MVC 或相应安全处理器，外层仍在 finally 清理身份。
        chain.doFilter(request, response);
    }

    /**
     * 重核权威认证版本、空闲和绝对时限，返回本次请求能否继续进入安全链。
     */
    private boolean restoreIdentity(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof IamPrincipal principal)) {
            // 匿名请求留给后续精确路径规则，绝不建立默认管理身份。
            return true;
        }

        String path = request.getRequestURI();

        boolean publicPath = PUBLIC_PATHS.contains(path);

        var session = request.getSession(false);

        var user = users.find(principal.userId());

        long now = clock.instant().getEpochSecond();

        Long authenticated = session == null ? null : (Long) session.getAttribute(AUTH_AT);

        Long activity = session == null ? null : (Long) session.getAttribute(ACTIVITY_AT);
        if (user == null
                || !user.getLifecycle().equals("ENABLED")
                || user.getAuthenticationVersion() != principal.authenticationVersion()
                || authenticated == null
                || activity == null
                || now - authenticated >= absoluteSeconds
                || now - activity >= idleSeconds) {
            // 先结束旧资格；公开登录和 CSRF 入口可以继续建立新的匿名会话。
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            if (publicPath) {
                return true;
            }
            errors.write(response, 401, "AUTH_EXPIRED", "登录已失效，请重新登录");
            return false;
        }

        var fresh = new IamPrincipal(
                user.getId(), user.getAuthenticationVersion(), user.getUsername(), user.getDisplayName());

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(fresh, null, List.of()));
        SecurityContextHolder.setContext(context);

        if (user.isMustChangePassword()
                && !publicPath
                && !Set.of("/api/v1/auth/me", "/api/v1/auth/password/initial").contains(path)) {
            // 待改密会话不能借直接 API 或订阅请求绕过必要改密。
            errors.write(response, 403, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码并重新登录");
            return false;
        }

        boolean passive = publicPath
                || path.equals("/api/v1/auth/me")
                || path.startsWith("/api/v1/commands/")
                || path.endsWith("/events")
                || path.endsWith("/snapshot");
        if (!passive) {
            // 先判断时限再更新活动，心跳、状态轮询和令牌获取不会续期。
            session.setAttribute(ACTIVITY_AT, now);
        }
        return true;
    }
}
