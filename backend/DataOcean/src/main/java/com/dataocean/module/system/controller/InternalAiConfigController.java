package com.dataocean.module.system.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.system.service.SysConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 配置内部接口（供 Python 服务调用）。
 * <p>
 * 返回原始配置值，apiKey 返回解密后的明文。
 * 通过 X-Internal-Token 请求头进行简单鉴权。
 * </p>
 */
@RestController
@RequestMapping("/internal/ai-config")
@RequiredArgsConstructor
public class InternalAiConfigController {

    private static final String UNSAFE_DEFAULT_TOKEN = "dataocean-internal-default";

    private final SysConfigService configService;
    private final DatasourceSecretService secretService;

    @Value("${dataocean.internal.token:dataocean-internal-default}")
    private String expectedToken;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @PostConstruct
    void validateTokenConfig() {
        boolean devProfile = activeProfile != null && activeProfile.contains("dev");
        if (UNSAFE_DEFAULT_TOKEN.equals(expectedToken) && !devProfile) {
            throw new IllegalStateException("Production profile must configure dataocean.internal.token explicitly");
        }
    }

    /**
     * 获取原始 AI 配置（供 Python 拉取，apiKey 返回解密后的明文）。
     */
    @GetMapping
    public Result<Map<String, String>> getRawConfig(
            @RequestHeader(value = "X-Internal-Token", defaultValue = "") String token) {
        if (!expectedToken.equals(token)) {
            return Result.error(403, "无权访问");
        }
        Map<String, String> config = new HashMap<>();
        config.put("ai.dashscope.apiKey", getDecryptedApiKey());
        config.put("ai.dashscope.baseUrl", configService.getValue("ai.dashscope.baseUrl", ""));
        config.put("ai.llm.model", configService.getValue("ai.llm.model", ""));
        config.put("ai.llm.temperature", configService.getValue("ai.llm.temperature", ""));
        config.put("ai.llm.timeout", configService.getValue("ai.llm.timeout", ""));
        config.put("ai.embedding.model", configService.getValue("ai.embedding.model", ""));
        config.put("ai.embedding.dimension", configService.getValue("ai.embedding.dimension", ""));
        return Result.success(config);
    }

    /**
     * 获取解密后的 API Key。
     */
    private String getDecryptedApiKey() {
        String encrypted = configService.getValue("ai.dashscope.apiKey", "");
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            return secretService.decrypt(encrypted);
        } catch (Exception e) {
            return "";
        }
    }
}
