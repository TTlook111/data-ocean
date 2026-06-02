package com.dataocean.module.system.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.system.entity.dto.AiConfigDTO;
import com.dataocean.module.system.entity.vo.AiConfigVO;
import com.dataocean.module.system.service.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

/**
 * AI 服务配置管理控制器。
 * <p>
 * 提供 AI 模型配置的查询和更新接口，支持热更新（无需重启 Python 服务）。
 * API Key 使用 AES 加密存储，查询时返回掩码。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/system/ai-config")
@Slf4j
@PreAuthorize("hasAuthority('*')")
public class AiConfigController {

    private final SysConfigService configService;
    private final DatasourceSecretService secretService;
    private final RestClient pythonRestClient;

    public AiConfigController(SysConfigService configService, DatasourceSecretService secretService,
                              @Value("${dataocean.python-service.base-url:http://localhost:8000}") String pythonServiceBaseUrl) {
        this.configService = configService;
        this.secretService = secretService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.pythonRestClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(pythonServiceBaseUrl)
                .build();
    }

    /**
     * 查询当前 AI 配置（apiKey 返回掩码）。
     */
    @GetMapping
    public Result<AiConfigVO> getConfig() {
        AiConfigVO vo = new AiConfigVO();

        // 读取加密的 apiKey 并掩码
        String encryptedKey = configService.getValue("ai.dashscope.apiKey", "");
        if (encryptedKey != null && !encryptedKey.isEmpty()) {
            try {
                String plainKey = secretService.decrypt(encryptedKey);
                vo.setApiKeyMasked(maskApiKey(plainKey));
            } catch (Exception e) {
                vo.setApiKeyMasked("****");
            }
        } else {
            vo.setApiKeyMasked("");
        }

        vo.setBaseUrl(configService.getValue("ai.dashscope.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        vo.setModel(configService.getValue("ai.llm.model", "qwen-plus"));
        vo.setTemperature(configService.getValue("ai.llm.temperature", "0.3"));
        vo.setTimeout(configService.getValue("ai.llm.timeout", "120"));
        vo.setEmbeddingModel(configService.getValue("ai.embedding.model", "text-embedding-v4"));
        vo.setEmbeddingDimension(configService.getValue("ai.embedding.dimension", "1024"));

        return Result.success(vo);
    }

    /**
     * 更新 AI 配置并触发热重载。
     * <p>
     * apiKey 使用 AES 加密后存储；更新完成后回调 Python 服务重载配置。
     * </p>
     */
    @PutMapping
    public Result<AiConfigVO> updateConfig(@RequestBody AiConfigDTO dto) {
        log.info("更新 AI 配置");

        // apiKey 单独处理：加密存储
        if (dto.getApiKey() != null && !dto.getApiKey().isEmpty()) {
            String encrypted = secretService.encrypt(dto.getApiKey());
            configService.setValue("ai.dashscope.apiKey", encrypted);
        }

        // 其他配置直接存储
        if (dto.getBaseUrl() != null) configService.setValue("ai.dashscope.baseUrl", dto.getBaseUrl());
        if (dto.getModel() != null) configService.setValue("ai.llm.model", dto.getModel());
        if (dto.getTemperature() != null) configService.setValue("ai.llm.temperature", dto.getTemperature());
        if (dto.getTimeout() != null) configService.setValue("ai.llm.timeout", dto.getTimeout());
        if (dto.getEmbeddingModel() != null) configService.setValue("ai.embedding.model", dto.getEmbeddingModel());
        if (dto.getEmbeddingDimension() != null) configService.setValue("ai.embedding.dimension", dto.getEmbeddingDimension());

        // 回调 Python 服务重载配置
        notifyPythonReload();

        return getConfig();
    }

    /**
     * 通知 Python 服务重载 AI 配置。
     */
    private void notifyPythonReload() {
        try {
            pythonRestClient.post().uri("/internal/config/reload").retrieve().toBodilessEntity();
            log.info("已通知 Python 服务重载 AI 配置");
        } catch (Exception e) {
            log.warn("通知 Python 重载配置失败（Python 可能未启动）: {}", e.getMessage());
        }
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
