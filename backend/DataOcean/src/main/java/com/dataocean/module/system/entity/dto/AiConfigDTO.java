package com.dataocean.module.system.entity.dto;

import lombok.Data;

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
}
