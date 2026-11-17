package com.dataocean.infrastructure.security;

import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.result.Result;
import com.dataocean.infrastructure.JsonCodec;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 为过滤链中的认证和依赖失败写出统一 JSON 错误。
 */
@Component
@RequiredArgsConstructor
public class ApiErrorWriter {

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonCodec json;

    /**
     * 写出统一结构或编码明确类型数据，I/O 失败由允许处理的边界承接。
     */
    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        write(response, status, ErrorCode.valueOf(code).getCode(), message);
    }

    /**
     * 写出统一结构或编码明确类型数据，I/O 失败由允许处理的边界承接。
     */
    public void write(HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json.write(new Result<>(code, message, null)));
    }
}
