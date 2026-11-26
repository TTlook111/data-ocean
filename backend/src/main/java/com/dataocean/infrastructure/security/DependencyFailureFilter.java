package com.dataocean.infrastructure.security;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 在会话存储过滤器外层处理依赖故障，Redis 失败不会绕过 MVC 返回错误成功。
 */
@Slf4j
@RequiredArgsConstructor
public class DependencyFailureFilter extends OncePerRequestFilter {

    /**
     * 与认证和 MVC 共用的明确 JSON 失败结构。
     */
    private final ApiErrorWriter errors;

    /**
     * 未提交的普通 JSON 请求可改为明确暂不可用；已提交响应只能继续传播故障。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // 同时包裹会话恢复和保存，成功 Cookie 必须有真实持久化依据。
            chain.doFilter(request, response);
        } catch (DataAccessException error) {
            if (response.isCommitted()) {
                // 流式或文件响应已开始交付时不追加一份普通 JSON。
                throw error;
            }

            String trace = response.getHeader("X-Trace-Id");
            if (trace == null) {
                trace = UUID.randomUUID().toString();
            }
            // 丢弃未交付的成功正文和 Cookie，清理输出通道后才能安全返回失败。
            response.reset();
            response.setHeader("X-Trace-Id", trace);
            errors.write(response, 503, "DEPENDENCY_UNAVAILABLE", "当前服务暂不可用，请稍后重试");
            log.warn("请求依赖失败，traceId={}, errorType={}", trace, error.getClass().getSimpleName());
        }
    }
}
