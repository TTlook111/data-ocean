package com.dataocean.module.system.entity.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 配置更新请求 DTO
 */
@Data
public class AiConfigDTO {
    private String apiKey;
    private String baseUrl;
    private String model;
    private String temperature;
    private String timeout;
    private String embeddingModel;
    private String embeddingDimension;

    private ChatConfig chat;
    private EmbeddingConfig embedding;
    private EmbeddingConfig pendingEmbedding;

    @Data
    public static class ProviderPayload {
        private String id;
        private String name;
        private String baseUrl;
        private String apiKey;
        private List<ModelItem> chatModels;
        private List<ModelItem> embeddingModels;
    }

    @Data
    public static class ChatConfig {
        private String providerId;
        private String model;
        private String temperature;
        private String timeout;
        private String maxRetries;
    }

    @Data
    public static class EmbeddingConfig {
        private String providerId;
        private String model;
        private Integer dimension;
        private String collection;
        private String indexVersion;
    }

    @Data
    public static class ModelItem {
        private String name;
        private String displayName;
        private Integer dimension;
        private Integer maxContext;
        private String type;
        private Boolean manualType;
    }
}
