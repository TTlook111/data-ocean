package com.dataocean.module.system.entity.vo;

import lombok.Data;

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
}
