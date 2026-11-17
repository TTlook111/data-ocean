package com.dataocean.module.iam.security;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.security.ApiErrorWriter;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.iam.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class CurrentIdentityFilter extends OncePerRequestFilter {
    public static final String AUTH_VERSION="dataocean.authenticationVersion";
    public static final String AUTH_AT="dataocean.authenticatedAt";
    public static final String ACTIVITY_AT="dataocean.lastUserActivityAt";
    private final UserMapper users;
    private final ApiErrorWriter errors;
    private final Clock clock;
    private final long idleSeconds;
    private final long absoluteSeconds;
    public CurrentIdentityFilter(UserMapper users,ApiErrorWriter errors,Clock clock,long idleSeconds,long absoluteSeconds) {
        this.users=users;this.errors=errors;this.clock=clock;this.idleSeconds=idleSeconds;this.absoluteSeconds=absoluteSeconds;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Trace-Id",UUID.randomUUID().toString());
        response.setHeader("Cache-Control","no-store");
        String path=request.getRequestURI();
        boolean publicPath=Set.of("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/health","/error").contains(path);
        try {
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if (authentication!=null && authentication.getPrincipal() instanceof IamPrincipal principal) {
                var session=request.getSession(false);
                var user=users.find(principal.userId());
                long now=clock.instant().getEpochSecond();
                Long authenticated=session==null?null:(Long)session.getAttribute(AUTH_AT);
                Long activity=session==null?null:(Long)session.getAttribute(ACTIVITY_AT);
                if (user==null || !user.getLifecycle().equals("ENABLED") || user.getAuthenticationVersion()!=principal.authenticationVersion()
                    || authenticated==null || activity==null || now-authenticated>=absoluteSeconds || now-activity>=idleSeconds) {
                    if (session!=null) session.invalidate();
                    SecurityContextHolder.clearContext();
                    if (!publicPath) { errors.write(response,401,"AUTH_EXPIRED","登录已失效，请重新登录");return; }
                } else {
                    // 只刷新请求内身份资料，不缓存权限，也不建立第二套 ThreadLocal。
                    var fresh=new IamPrincipal(user.getId(),user.getAuthenticationVersion(),user.getUsername(),user.getDisplayName());
                    var context=SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(fresh,null,List.of()));
                    SecurityContextHolder.setContext(context);
                    boolean passive=path.equals("/api/v1/auth/me") || path.startsWith("/api/v1/commands/") || path.endsWith("/events") || path.endsWith("/snapshot");
                    if (!passive) session.setAttribute(ACTIVITY_AT,now);
                    if (user.isMustChangePassword() && !publicPath && !Set.of("/api/v1/auth/me","/api/v1/auth/password/initial","/api/v1/auth/logout").contains(path)) {
                        errors.write(response,403,"PASSWORD_CHANGE_REQUIRED","请先修改初始密码并重新登录");return;
                    }
                }
            }
            chain.doFilter(request,response);
        } catch (DataAccessException error) { errors.write(response,503,"DEPENDENCY_UNAVAILABLE","当前无法核验登录与权限，请稍后重试"); }
        catch (BizIllegalException error) { errors.write(response,error.getStatus(),error.getCode(),error.getMessage()); }
    }
}
