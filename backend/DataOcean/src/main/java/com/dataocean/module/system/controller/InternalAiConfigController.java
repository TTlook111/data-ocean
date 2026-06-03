package com.dataocean.module.system.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.system.entity.vo.AiConfigVO;
import com.dataocean.module.system.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 配置内部接口（供 Python 服务调用）。
 */
@RestController
@RequestMapping("/internal/ai-config")
@RequiredArgsConstructor
public class InternalAiConfigController {

    private static final String UNSAFE_DEFAULT_TOKEN = "dataocean-internal-default";
    private static final String PROVIDER_PREFIX = "ai.provider.";
    private static final String ACTIVE_CHAT_KEY = "ai.active.chat";
    private static final String ACTIVE_EMBEDDING_KEY = "ai.active.embedding";
    private static final String DEFAULT_PROVIDER_ID = "dashscope";

    private final SysConfigService configService;
    private final DatasourceSecretService secretService;
    private final ObjectMapper objectMapper;

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

    @GetMapping
    public Result<Map<String, String>> getRawConfig(
            @RequestHeader(value = "X-Internal-Token", defaultValue = "") String token) {
        if (!expectedToken.equals(token)) {
            return Result.error(403, "无权访问");
        }
        AiConfigVO.ChatConfig chat = readJson(ACTIVE_CHAT_KEY, AiConfigVO.ChatConfig.class, defaultChat());
        AiConfigVO.EmbeddingConfig embedding = readJson(ACTIVE_EMBEDDING_KEY, AiConfigVO.EmbeddingConfig.class, defaultEmbedding());
        AiConfigVO.Provider chatProvider = readJson(PROVIDER_PREFIX + chat.getProviderId(), AiConfigVO.Provider.class, defaultProvider());
        AiConfigVO.Provider embeddingProvider = readJson(PROVIDER_PREFIX + embedding.getProviderId(), AiConfigVO.Provider.class, chatProvider);

        Map<String, String> config = new HashMap<>();
        config.put("ai.dashscope.apiKey", decrypt(chatProvider));
        config.put("ai.dashscope.baseUrl", chatProvider.getBaseUrl() == null ? "" : chatProvider.getBaseUrl());
        config.put("ai.llm.model", blankToDefault(chat.getModel(), "qwen-plus"));
        config.put("ai.llm.temperature", blankToDefault(chat.getTemperature(), "0.3"));
        config.put("ai.llm.timeout", blankToDefault(chat.getTimeout(), "120"));
        config.put("ai.llm.maxRetries", blankToDefault(chat.getMaxRetries(), "2"));
        config.put("ai.embedding.apiKey", decrypt(embeddingProvider));
        config.put("ai.embedding.baseUrl", embeddingProvider.getBaseUrl() == null ? "" : embeddingProvider.getBaseUrl());
        config.put("ai.embedding.model", blankToDefault(embedding.getModel(), "text-embedding-v4"));
        config.put("ai.embedding.dimension", String.valueOf(embedding.getDimension() == null ? 1024 : embedding.getDimension()));
        config.put("ai.embedding.collection", blankToDefault(embedding.getCollection(), "schema_knowledge"));
        return Result.success(config);
    }

    private AiConfigVO.ChatConfig defaultChat() {
        AiConfigVO.ChatConfig chat = new AiConfigVO.ChatConfig();
        chat.setProviderId(DEFAULT_PROVIDER_ID);
        chat.setModel(configService.getValue("ai.llm.model", "qwen-plus"));
        chat.setTemperature(configService.getValue("ai.llm.temperature", "0.3"));
        chat.setTimeout(configService.getValue("ai.llm.timeout", "120"));
        chat.setMaxRetries(configService.getValue("ai.llm.maxRetries", "2"));
        return chat;
    }

    private AiConfigVO.EmbeddingConfig defaultEmbedding() {
        AiConfigVO.EmbeddingConfig embedding = new AiConfigVO.EmbeddingConfig();
        embedding.setProviderId(DEFAULT_PROVIDER_ID);
        embedding.setModel(configService.getValue("ai.embedding.model", "text-embedding-v4"));
        embedding.setDimension(parseInt(configService.getValue("ai.embedding.dimension", "1024"), 1024));
        embedding.setCollection(configService.getValue("ai.embedding.collection", "schema_knowledge"));
        embedding.setIndexVersion(configService.getValue("ai.embedding.indexVersion", "v1"));
        return embedding;
    }

    private AiConfigVO.Provider defaultProvider() {
        AiConfigVO.Provider provider = new AiConfigVO.Provider();
        provider.setId(DEFAULT_PROVIDER_ID);
        provider.setBaseUrl(configService.getValue("ai.dashscope.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        provider.setApiKeyMasked(configService.getValue("ai.dashscope.apiKey", ""));
        return provider;
    }

    private <T> T readJson(String key, Class<T> type, T defaultValue) {
        String value = configService.getValue(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String decrypt(AiConfigVO.Provider provider) {
        String encrypted = provider == null ? "" : provider.getApiKeyMasked();
        if (encrypted == null || encrypted.isBlank()) return "";
        try {
            return secretService.decrypt(encrypted);
        } catch (Exception e) {
            return "";
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
