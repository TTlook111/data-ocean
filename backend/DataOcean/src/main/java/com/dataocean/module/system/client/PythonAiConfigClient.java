package com.dataocean.module.system.client;

import java.util.Map;

/**
 * Python AI 配置服务客户端接口。
 * <p>
 * 封装 AiConfigController 中对 Python 服务的调用，
 * 包括供应商测试、维度检测、重新向量化、配置重载通知等。
 * </p>
 */
public interface PythonAiConfigClient {

    /**
     * 测试供应商连接。
     * <p>
     * 调用 Python /internal/ai-config/test-provider 接口，
     * 验证供应商的 baseUrl 和 apiKey 是否有效。
     * </p>
     *
     * @param baseUrl 供应商基础 URL
     * @param apiKey  供应商 API Key（已解密）
     * @return 测试结果，包含模型列表等信息
     * @throws com.dataocean.common.exception.BusinessException 测试失败时抛出
     */
    Map<String, Object> testProvider(String baseUrl, String apiKey);

    /**
     * 检测嵌入维度。
     * <p>
     * 调用 Python /internal/ai-config/detect-dimension 接口，
     * 自动检测嵌入模型的维度。
     * </p>
     *
     * @param payload 检测参数（包含 provider、model 等信息）
     * @return 检测结果，包含 dimension 信息
     * @throws com.dataocean.common.exception.BusinessException 检测失败时抛出
     */
    Map<String, Object> detectDimension(Map<String, Object> payload);

    /**
     * 重新向量化。
     * <p>
     * 调用 Python /internal/rag/re-vectorize 接口，
     * 触发重新向量化任务。
     * </p>
     *
     * @param payload 向量化参数（可选）
     * @return 向量化任务信息
     * @throws com.dataocean.common.exception.BusinessException 向量化失败时抛出
     */
    Map<String, Object> reVectorize(Map<String, Object> payload);

    /**
     * 通知 Python 重载配置（best-effort）。
     * <p>
     * 调用 Python /internal/config/reload 接口，
     * 通知 Python 服务重新加载 AI 配置。
     * 失败时仅记录警告日志，不抛出异常。
     * </p>
     */
    void notifyReloadBestEffort();
}
