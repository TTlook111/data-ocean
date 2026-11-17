package com.dataocean.module.iam.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.iam.entity.dto.InitialPasswordDTO;
import com.dataocean.module.iam.entity.dto.LoginDTO;
import com.dataocean.module.iam.entity.vo.CsrfVO;
import com.dataocean.module.iam.entity.vo.CurrentSessionVO;
import com.dataocean.module.iam.entity.vo.LoginVO;
import com.dataocean.module.iam.entity.vo.PasswordChangedVO;
import com.dataocean.module.iam.security.CurrentIdentityFilter;
import com.dataocean.module.iam.service.AuthService;

import java.time.Clock;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 管理浏览器登录会话、CSRF 轮换及必要改密入口。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * 认证事实与凭据变更服务。
     */
    private final AuthService auth;

    /**
     * 由安全链复用的上下文存储。
     */
    private final HttpSessionSecurityContextRepository contexts;

    /**
     * 登录和退出时清除旧 CSRF。
     */
    private final HttpSessionCsrfTokenRepository csrf;

    /**
     * 统一 UTC 认证时刻。
     */
    private final Clock clock;

    /**
     * 触发服务端令牌生成，仅返回 CSRF 信息。
     */
    @GetMapping("/csrf")
    public Result<CsrfVO> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Result.success(new CsrfVO(token.getHeaderName(), token.getToken()));
    }

    /**
     * 认证成功后轮换会话编号与 CSRF，显式保存安全上下文。
     */
    @PostMapping("/login")
    public Result<LoginVO> login(
            @Valid @RequestBody LoginDTO data, HttpServletRequest request, HttpServletResponse response) {
        var principal = auth.authenticate(data, request.getRemoteAddr());
        var session = request.getSession(true);
        request.changeSessionId();
        long now = clock.instant().getEpochSecond();
        session.setAttribute(CurrentIdentityFilter.AUTH_AT, now);
        session.setAttribute(CurrentIdentityFilter.ACTIVITY_AT, now);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        csrf.saveToken(null, request, response);
        var current = auth.current(principal, now);
        return Result.success(new LoginVO(
                current.getUserId(),
                current.getAuthenticationState(),
                current.isMustChangePassword(),
                current.getAbsoluteExpiresAt()));
    }

    /**
     * 返回受限或完整身份，被动读取不延长用户活动时限。
     */
    @GetMapping("/me")
    public Result<CurrentSessionVO> me(HttpServletRequest request) {
        return Result.success(auth.current(
                UserContext.current(), (Long) request.getSession(false).getAttribute(CurrentIdentityFilter.AUTH_AT)));
    }

    /**
     * 注销当前会话，已退出的调用保持幂等。
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        endSession(request, response);
        return Result.success(null);
    }

    /**
     * 完成受限身份改密后清理当前 Cookie 对应的会话。
     */
    @PostMapping("/password/initial")
    public Result<PasswordChangedVO> initial(
            @Valid @RequestBody InitialPasswordDTO data, HttpServletRequest request, HttpServletResponse response) {
        auth.initialPassword(data);
        endSession(request, response);
        return Result.success(new PasswordChangedVO(true));
    }

    /**
     * 清除会话、线程上下文及旧 CSRF，不影响独立后台任务。
     */
    private void endSession(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) {
            // 同一会话可能已由改密后的失效消费者清除。
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrf.saveToken(null, request, response);
    }
}
