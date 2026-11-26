package com.dataocean.module.iam.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.security.ApiErrorWriter;
import com.dataocean.module.audit.entity.dto.AuditFailureDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.security.CurrentIdentityFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * 使用正式装配方法构建真实安全链，失败审计必须先于请求身份清理执行。
 */
class MutationAuditChainTest {

    /**
     * 固定时钟避免测试受真实空闲时限影响。
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);

    /**
     * 同时覆盖业务拒绝和持久化失败，审计时仍应有本次可信身份，结束后则必须清理。
     */
    @ParameterizedTest
    @ValueSource(ints = {409, 503})
    void failedWriteIsAuditedBeforeIdentityCleanup(int status) throws ServletException, IOException {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(SecurityTestConfiguration.class);
            context.refresh();
            var users = context.getBean(UserMapper.class);
            var person = new UserPO();
            person.setId(7L);
            person.setLifecycle("ENABLED");
            person.setAuthenticationVersion(1);
            person.setUsername("tester");
            person.setDisplayName("验收人员");
            when(users.selectById(7L)).thenReturn(person);
            var audits = context.getBean(AuditService.class);
            doAnswer(invocation -> {
                        // 真实外层安全过滤器此时尚未清理本次请求主体。
                        assertEquals(7, UserContext.getUser());
                        AuditFailureDTO failure = invocation.getArgument(0);
                        assertEquals("users.disable", failure.getActionCode());
                        assertEquals("12", failure.getTargetId());
                        assertEquals(status, failure.getHttpStatus());
                        return null;
                    })
                    .when(audits)
                    .failure(any());

            var request = new MockHttpServletRequest("POST", "/api/v1/iam/users/12/disable");
            request.setServletPath(request.getRequestURI());
            var identity = SecurityContextHolder.createEmptyContext();
            identity.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new IamPrincipal(7, 1, "tester", "验收人员"), null, List.of()));
            var session = request.getSession();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, identity);
            session.setAttribute(CurrentIdentityFilter.AUTH_AT, CLOCK.instant().getEpochSecond());
            session.setAttribute(
                    CurrentIdentityFilter.ACTIVITY_AT, CLOCK.instant().getEpochSecond());
            var response = new MockHttpServletResponse();
            var token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-csrf");
            new HttpSessionCsrfTokenRepository().saveToken(token, request, response);
            request.addHeader(token.getHeaderName(), token.getToken());
            var endpoint = new HttpServlet() {
                /**
                 * 返回已经由业务边界决定的失败，不代替真实安全过滤器。
                 */
                @Override
                protected void doPost(HttpServletRequest input, HttpServletResponse output) throws IOException {
                    output.sendError(status);
                }
            };
            context.getBean(FilterChainProxy.class).doFilter(request, response, new MockFilterChain(endpoint));
            assertEquals(status, response.getStatus());
            verify(audits).failure(any());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    /**
     * 直接调用正式过滤链装配，避免测试按预想顺序另写一份过滤器列表。
     */
    @Configuration
    @EnableWebSecurity
    static class SecurityTestConfiguration {
        /**
         * 仅隔离数据库人员读取，其他安全组件使用实际实现。
         */
        @Bean
        UserMapper users() {
            return mock(UserMapper.class);
        }

        /**
         * 验证调用时机，不让当前用例写入外部审计库。
         */
        @Bean
        AuditService audits() {
            return mock(AuditService.class);
        }

        /**
         * 将生产安全链接入真实 WebSecurity 构建器，不启动 Redis 或业务应用。
         */
        @Bean
        SecurityFilterChain security(HttpSecurity http, UserMapper users, AuditService audits) throws Exception {
            return new IamSecurityConfiguration()
                    .security(
                            http,
                            users,
                            new ApiErrorWriter(
                                    new JsonCodec(JsonMapper.builder().build())),
                            audits,
                            CLOCK,
                            new HttpSessionSecurityContextRepository(),
                            new HttpSessionCsrfTokenRepository(),
                            1800,
                            28800);
        }
    }
}
