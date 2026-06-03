package com.dataocean.module.system.entity.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 配置展示 VO（apiKey 返回掩码）
 */
@Data
public class AiConfigVO {
    private String apiKeyMasked;
    private String baseUrl;
    private String model;
    private String temperature;
    private String timeout;
    private String embeddingModel;
    private String embeddingDimension;

    private ChatConfig activeChat;
    private EmbeddingConfig activeEmbedding;
    private EmbeddingConfig pendingEmbedding;
    private VectorizeStatus vectorizeStatus;
    private List<Provider> providers;

    @Data
    public static class Provider {
        private String id;
        private String name;
        private String baseUrl;
        private String apiKeyMasked;
        private List<ModelItem> chatModels;
        private List<ModelItem> embeddingModels;
        private String status;
        private String lastTestedAt;
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
    public static class VectorizeStatus {
        private String status;
        private EmbeddingConfig active;
        private EmbeddingConfig pending;
        private Integer totalChunks;
        private Integer completedChunks;
        private Integer failedChunks;
        private String startedAt;
        private String completedAt;
        private String errorMessage;
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
