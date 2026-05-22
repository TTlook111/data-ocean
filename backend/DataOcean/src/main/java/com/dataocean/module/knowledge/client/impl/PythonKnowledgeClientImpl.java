package com.dataocean.module.knowledge.client.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python 知识库服务客户端实现类
 * <p>
 * 通过 HTTP POST 请求调用 Python AI 服务生成 skills.md 草稿。
 * AI 生成可能较慢，超时设置为 120 秒。
 * 调用失败时抛出 BusinessException，由上层统一处理。
 * </p>
 *
 * @author dataocean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PythonKnowledgeClientImpl implements PythonKnowledgeClient {

    private final RestClient.Builder restClientBuilder;

    /** Python AI 服务的基础 URL */
    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    /** AI 生成请求超时时间（毫秒） */
    private static final int TIMEOUT_MS = 120_000;

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> generateDraft(Long snapshotId, Long datasourceId,
                                              List<Map<String, Object>> tablesMetadata,
                                              List<Map<String, Object>> foreignKeys,
                                              List<Map<String, Object>> indexes) {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("snapshot_id", snapshotId);
        requestBody.put("datasource_id", datasourceId);
        requestBody.put("tables_metadata", tablesMetadata);
        requestBody.put("foreign_keys", foreignKeys);
        requestBody.put("indexes", indexes);

        try {
            log.info("调用 Python 服务生成 skills.md 草稿 snapshotId={} datasourceId={}", snapshotId, datasourceId);

            // 配置超时的请求工厂（AI 生成可能较慢，设置 120 秒超时）
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(TIMEOUT_MS);
            requestFactory.setReadTimeout(TIMEOUT_MS);

            // 发送 POST 请求到 Python 服务
            Map<String, Object> response = restClientBuilder
                    .requestFactory(requestFactory)
                    .build()
                    .post()
                    .uri(pythonServiceBaseUrl + "/internal/knowledge/generate-draft")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        log.error("Python 草稿生成接口返回异常 snapshotId={} status={}", snapshotId, resp.getStatusCode());
                        throw new BusinessException("AI 草稿生成失败");
                    })
                    .body(new ParameterizedTypeReference<>() {});

            log.info("Python 服务生成 skills.md 草稿成功 snapshotId={}", snapshotId);
            return response;
        } catch (BusinessException e) {
            // 业务异常直接向上抛出
            throw e;
        } catch (Exception e) {
            log.error("调用 Python 服务生成草稿失败 snapshotId={} reason={}", snapshotId, e.getMessage(), e);
            throw new BusinessException("AI 草稿生成失败");
        }
    }
}
