package com.dataocean.module.system.client.impl;

import com.dataocean.common.client.PythonClientSupport;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.exception.PythonRetryableException;
import com.dataocean.module.system.client.PythonAiConfigClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Python AI 配置服务客户端实现类。
 * <p>
 * 封装 AiConfigController 中对 Python 服务的调用，
 * 使用短超时 RestClient（15s）用于快速操作，
 * 使用长超时 RestClient（120s）用于重新向量化。
 * </p>
 */
@Service
@Slf4j
public class PythonAiConfigClientImpl implements PythonAiConfigClient {

    /** 短超时 RestClient，用于测试供应商、检测维度、重载配置 */
    private final RestClient shortTimeoutRestClient;

    /** 长超时 RestClient，用于重新向量化 */
    private final RestClient restClient;

    /**
     * 构造 Python AI 配置客户端。
     *
     * @param shortTimeoutRestClient 短超时 RestClient（15s）
     * @param restClient             长超时 RestClient（120s）
     */
    public PythonAiConfigClientImpl(
            @Qualifier("pythonShortTimeoutRestClient") RestClient shortTimeoutRestClient,
            @Qualifier("pythonRestClient") RestClient restClient) {
        this.shortTimeoutRestClient = shortTimeoutRestClient;
        this.restClient = restClient;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> testProvider(String baseUrl, String apiKey) {
        try {
            log.info("测试供应商连接 baseUrl={}", baseUrl);

            Map<String, Object> response = shortTimeoutRestClient.post()
                    .uri("/internal/ai-config/test-provider")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("baseUrl", baseUrl, "apiKey", apiKey))
                    .retrieve()
                    .onStatus(org.springframework.http.HttpStatusCode::isError, (request, responseEntity) -> {
                        log.error("Python 供应商测试接口返回异常 status={}", responseEntity.getStatusCode());
                        throw PythonClientSupport.statusException(responseEntity.getStatusCode(), "供应商连接测试失败");
                    })
                    .body(new ParameterizedTypeReference<>() {});

            log.info("供应商连接测试成功 baseUrl={}", baseUrl);
            return response;
        } catch (ResourceAccessException e) {
            log.warn("供应商连接测试超时 baseUrl={} reason={}", baseUrl, e.getMessage());
            if (PythonClientSupport.isReadTimeout(e)) {
                throw new BusinessException("供应商连接测试超时，请检查网络后重试");
            }
            throw new BusinessException("供应商连接测试失败，请检查配置后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("供应商连接测试失败 baseUrl={} reason={}", baseUrl, e.getMessage());
            throw new BusinessException("供应商连接测试失败，请检查配置后重试");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> detectDimension(Map<String, Object> payload) {
        try {
            log.info("检测嵌入维度 providerId={} model={}",
                    payload == null ? null : payload.get("providerId"),
                    payload == null ? null : payload.get("model"));

            Map<String, Object> response = shortTimeoutRestClient.post()
                    .uri("/internal/ai-config/detect-dimension")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(org.springframework.http.HttpStatusCode::isError, (request, responseEntity) -> {
                        log.error("Python 维度检测接口返回异常 status={}", responseEntity.getStatusCode());
                        throw PythonClientSupport.statusException(responseEntity.getStatusCode(), "嵌入维度检测失败");
                    })
                    .body(new ParameterizedTypeReference<>() {});

            log.info("嵌入维度检测成功 dimension={}", response.get("dimension"));
            return response;
        } catch (ResourceAccessException e) {
            log.warn("嵌入维度检测超时 reason={}", e.getMessage());
            if (PythonClientSupport.isReadTimeout(e)) {
                throw new BusinessException("嵌入维度检测超时，请稍后重试");
            }
            throw new BusinessException("嵌入维度检测失败，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("嵌入维度检测失败 reason={}", e.getMessage(), e);
            throw new BusinessException("嵌入维度检测失败，请稍后重试");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> reVectorize(Map<String, Object> payload) {
        try {
            log.info("触发重新向量化 payload={}", payload);

            Map<String, Object> body = payload == null ? Map.of() : payload;
            Map<String, Object> response = restClient.post()
                    .uri("/internal/rag/re-vectorize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(org.springframework.http.HttpStatusCode::isError, (request, responseEntity) -> {
                        log.error("Python 重新向量化接口返回异常 status={}", responseEntity.getStatusCode());
                        throw PythonClientSupport.statusException(responseEntity.getStatusCode(), "重新向量化失败");
                    })
                    .body(new ParameterizedTypeReference<>() {});

            log.info("重新向量化任务已触发");
            return response;
        } catch (ResourceAccessException e) {
            log.warn("重新向量化超时 reason={}", e.getMessage());
            if (PythonClientSupport.isReadTimeout(e)) {
                throw new BusinessException("重新向量化超时，请稍后重试");
            }
            throw new BusinessException("重新向量化服务暂时不可用，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("重新向量化失败 reason={}", e.getMessage(), e);
            throw new BusinessException("重新向量化失败，请稍后重试");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyReloadBestEffort() {
        try {
            shortTimeoutRestClient.post()
                    .uri("/internal/config/reload")
                    .retrieve()
                    .toBodilessEntity();
            log.info("已通知 Python 服务重载 AI 配置");
        } catch (Exception e) {
            // best-effort 语义：失败只记录警告，不抛出异常
            log.warn("通知 Python 重载配置失败（Python 可能未启动）: {}", e.getMessage());
        }
    }
}
