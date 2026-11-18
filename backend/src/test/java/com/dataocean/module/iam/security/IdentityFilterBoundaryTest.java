package com.dataocean.module.iam.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.security.ApiErrorWriter;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.mapper.UserMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * 使用真实安全过滤器与同一工作线程验证正常、异常和依赖失败后的身份隔离。
 */
class IdentityFilterBoundaryTest {

    /**
     * 时限判定保持可重复，不以等待真实半小时替代边界验证。
     */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);

    /**
     * 仅权威人员读取可控，安全上下文恢复与清理仍使用真实框架实现。
     */
    private final UserMapper users = mock(UserMapper.class);

    /**
     * 最小 Servlet 读取当前可信主体，异常路径用于检查外层 finally 清理。
     */
    private HttpServlet endpoint(boolean fail, AtomicBoolean entered) {
        return new HttpServlet() {

            /**
             * 只在实际进入业务链后读取当前身份。
             */
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
                    throws ServletException, IOException {
                entered.set(true);
                if (fail) {
                    throw new ServletException("synthetic downstream failure");
                }
                response.getWriter().write(Long.toString(UserContext.getUser()));
            }
        };
    }

    /**
     * 为每次请求构造独立会话，安全主体名沿用稳定人员 ID。
     */
    private MockHttpSession session(long id) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IamPrincipal(id, 1, "test" + id, "测试人员"), null, List.of()));

        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(CurrentIdentityFilter.AUTH_AT, clock.instant().getEpochSecond());
        session.setAttribute(CurrentIdentityFilter.ACTIVITY_AT, clock.instant().getEpochSecond() - 10);
        return session;
    }

    /**
     * 权威账号只提供认证所需事实，不在会话保存完整人员 PO。
     */
    private UserPO user(long id) {
        var user = new UserPO();
        user.setId(id);
        user.setUsername("test" + id);
        user.setDisplayName("测试人员");
        user.setLifecycle("ENABLED");
        user.setAuthenticationVersion(1);
        return user;
    }

    /**
     * 实际上下文过滤器在自定义身份过滤器外侧，负责所有结束路径的清理。
     */
    private MockFilterChain chain(boolean fail, AtomicBoolean entered) {
        var identity = new CurrentIdentityFilter(
                users, new ApiErrorWriter(new JsonCodec(JsonMapper.builder().build())), clock, 1800, 28800);
        return new MockFilterChain(
                endpoint(fail, entered),
                new SecurityContextHolderFilter(new HttpSessionSecurityContextRepository()),
                identity);
    }

    /**
     * 同一线程连续处理两个人员后均无残留，不能只验证两个独立线程。
     */
    @Test
    void reusableWorkerDoesNotKeepPreviousIdentity() throws InterruptedException, ExecutionException {
        when(users.selectById(1L)).thenReturn(user(1));
        when(users.selectById(2L)).thenReturn(user(2));

        var pool = Executors.newSingleThreadExecutor();
        try {
            for (long id : List.of(1L, 2L)) {
                // 单线程执行器强制复用同一工作线程。
                long actor = pool.submit(() -> {
                            var request = new MockHttpServletRequest("GET", "/api/v1/account");
                            request.setSession(session(id));

                            var response = new MockHttpServletResponse();
                            chain(false, new AtomicBoolean()).doFilter(request, response);
                            assertNull(SecurityContextHolder.getContext().getAuthentication());
                            return Long.parseLong(response.getContentAsString());
                        })
                        .get();
                assertEquals(id, actor);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 下游异常继续传播给所属边界，但身份仍由安全框架清理。
     */
    @Test
    void downstreamExceptionStillClearsIdentity() {
        when(users.selectById(1L)).thenReturn(user(1));

        var request = new MockHttpServletRequest("GET", "/api/v1/account");
        request.setSession(session(1));
        assertThrows(ServletException.class, () -> chain(true, new AtomicBoolean())
                .doFilter(request, new MockHttpServletResponse()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 权威存储不可用时返回失败且完全不进入业务链。
     */
    @Test
    void storageFailureCannotUseRestoredOldContext() throws ServletException, IOException {
        when(users.selectById(1L)).thenThrow(new DataAccessResourceFailureException("synthetic storage failure"));

        var entered = new AtomicBoolean();

        var request = new MockHttpServletRequest("GET", "/api/v1/account");
        request.setSession(session(1));

        var response = new MockHttpServletResponse();
        chain(false, entered).doFilter(request, response);
        assertEquals(503, response.getStatus());
        assertFalse(entered.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 被动身份轮询不刷新空闲时刻，真实业务读取才刷新。
     */
    @Test
    void passiveIdentityPollingDoesNotExtendIdleTime() throws ServletException, IOException {
        when(users.selectById(1L)).thenReturn(user(1));

        var session = session(1);

        var passive = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        passive.setSession(session);
        chain(false, new AtomicBoolean()).doFilter(passive, new MockHttpServletResponse());
        assertEquals(clock.instant().getEpochSecond() - 10, session.getAttribute(CurrentIdentityFilter.ACTIVITY_AT));

        var active = new MockHttpServletRequest("GET", "/api/v1/account");
        active.setSession(session);
        chain(false, new AtomicBoolean()).doFilter(active, new MockHttpServletResponse());
        assertEquals(clock.instant().getEpochSecond(), session.getAttribute(CurrentIdentityFilter.ACTIVITY_AT));
    }
}
