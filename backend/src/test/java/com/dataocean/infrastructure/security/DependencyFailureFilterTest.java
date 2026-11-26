package com.dataocean.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dataocean.infrastructure.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * 会话恢复或提交失败不能遗留未持久化的成功正文和 Cookie。
 */
class DependencyFailureFilterTest {

    /**
     * 使用与真实认证入口相同的错误写出实现。
     */
    private final DependencyFailureFilter filter = new DependencyFailureFilter(
            new ApiErrorWriter(new JsonCodec(JsonMapper.builder().build())));

    /**
     * 已使用输出流但尚未提交的成功结果，依赖失败后仍应安全改为统一 JSON 失败。
     */
    @Test
    void discardsUncommittedSuccessAndCookie() throws ServletException, IOException {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/login"), response, (request, output) -> {
            // 模拟登录处理完毕但会话提交失败，正文未真正交付。
            response.setHeader("Set-Cookie", "SESSION=synthetic-session; Path=/; HttpOnly");
            response.getOutputStream().write("uncommitted success".getBytes(StandardCharsets.UTF_8));
            throw new DataAccessResourceFailureException("synthetic Redis failure");
        });
        assertEquals(503, response.getStatus());
        assertNull(response.getHeader("Set-Cookie"));
        assertFalse(response.getContentAsString().contains("uncommitted success"));
        assertTrue(response.getContentAsString().contains("503001"));
    }

    /**
     * 已开始交付的响应只能传播失败，不能追加普通错误 JSON 污染原协议。
     */
    @Test
    void committedResponseIsNotRewritten() throws IOException {
        var response = new MockHttpServletResponse();
        assertThrows(
                DataAccessResourceFailureException.class,
                () -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/v1/test/events"), response, (request, output) -> {
                            response.getWriter().write("event: prior\n\n");
                            response.flushBuffer();
                            throw new DataAccessResourceFailureException("synthetic Redis failure");
                        }));
        assertEquals("event: prior\n\n", response.getContentAsString());
    }

    /**
     * 输出 I/O 失败与存储异常不同，当前层不捕获后伪造另一份响应。
     */
    @Test
    void ioFailureKeepsItsOriginalBoundary() {
        assertThrows(
                IOException.class,
                () -> filter.doFilter(
                        new MockHttpServletRequest("GET", "/api/v1/health"),
                        new MockHttpServletResponse(),
                        (request, output) -> {
                            throw new IOException("synthetic connection closed");
                        }));
    }
}
