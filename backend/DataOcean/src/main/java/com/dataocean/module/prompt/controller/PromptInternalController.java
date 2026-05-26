package com.dataocean.module.prompt.controller;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.module.prompt.service.PromptTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Prompt 内部 API 控制器
 * <p>
 * 供 Python 服务调用获取当前活跃版本的模板内容。
 * 通过 X-Internal-Token 请求头校验内部调用身份。
 * 生产环境应配合网络隔离（mTLS）使用。
 * </p>
 */
@RestController
@RequestMapping("/internal/prompts")
@RequiredArgsConstructor
@Slf4j
public class PromptInternalController {

    private final PromptTemplateService promptTemplateService;

    @Value("${dataocean.internal.token:dataocean-internal-default}")
    private String internalToken;

    /**
     * 获取活跃版本的模板内容
     *
     * @param code 模板编码
     * @return 包含 code 和 content 的 Map
     */
    @GetMapping("/{code}")
    public Result<Map<String, String>> getActiveContent(@PathVariable String code, HttpServletRequest request) {
        validateInternalCall(request);
        String content = promptTemplateService.getActiveContent(code);
        return Result.success(Map.of("code", code, "content", content));
    }

    /**
     * 校验内部调用标识
     */
    private void validateInternalCall(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(internalToken)) {
            log.warn("内部 API 非法访问 path={} ip={}", request.getRequestURI(), request.getRemoteAddr());
            throw new BusinessException(403, "内部接口禁止外部访问");
        }
    }
}
