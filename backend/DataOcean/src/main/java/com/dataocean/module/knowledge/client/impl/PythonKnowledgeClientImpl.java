package com.dataocean.module.knowledge.client.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import jakarta.annotation.PostConstruct;
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
 * RestClient 在初始化时创建一次，后续复用同一实例。
 * </p>
 *
 * @author DataOcean
 */
@Service
@Slf4j
public class PythonKnowledgeClientImpl implements PythonKnowledgeClient {

    /** Python AI 服务的基础 URL */
    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    /** AI 生成请求超时时间（毫秒） */
    private static final int TIMEOUT_MS = 120_000;

    /** 复用的 RestClient 实例 */
    private RestClient restClient;

    /**
     * 初始化 RestClient 实例（仅创建一次）
     */
    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT_MS);
        requestFactory.setReadTimeout(TIMEOUT_MS);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(pythonServiceBaseUrl)
                .build();
        log.info("PythonKnowledgeClient 初始化完成 baseUrl={} timeout={}ms", pythonServiceBaseUrl, TIMEOUT_MS);
    }

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

            // 使用预初始化的 RestClient 发送请求
            Map<String, Object> response = restClient.post()
                    .uri("/internal/knowledge/generate-draft")
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
            throw e;
        } catch (Exception e) {
            log.error("调用 Python 服务生成草稿失败 snapshotId={} reason={}", snapshotId, e.getMessage(), e);
            throw new BusinessException("AI 草稿生成失败");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> analyzeAndGenerate(Long snapshotId, Long datasourceId,
                                                    List<Map<String, Object>> tablesMetadata,
                                                    List<Map<String, Object>> foreignKeys,
                                                    List<Map<String, Object>> indexes) {
        // 构建请求体（与 generateDraft 相同）
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("snapshot_id", snapshotId);
        requestBody.put("datasource_id", datasourceId);
        requestBody.put("tables_metadata", tablesMetadata);
        requestBody.put("foreign_keys", foreignKeys);
        requestBody.put("indexes", indexes);

        try {
            log.info("调用 Python 服务域分析+批量生成 snapshotId={} datasourceId={}", snapshotId, datasourceId);

            Map<String, Object> response = restClient.post()
                    .uri("/internal/knowledge/analyze-and-generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        log.error("Python 域分析接口返回异常 snapshotId={} status={}", snapshotId, resp.getStatusCode());
                        throw new BusinessException("AI 域分析+批量生成失败");
                    })
                    .body(new ParameterizedTypeReference<>() {});

            log.info("Python 服务域分析+批量生成成功 snapshotId={}", snapshotId);
            return response;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Python 服务域分析失败 snapshotId={} reason={}", snapshotId, e.getMessage(), e);
            throw new BusinessException("AI 域分析+批量生成失败");
        }
    }
}
