package com.dataocean.module.knowledge.client.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
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
 * Python RAG 服务客户端实现。
 */
@Service
@Slf4j
public class PythonRagClientImpl implements PythonRagClient {

    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    private static final int TIMEOUT_MS = 120_000;

    private RestClient restClient;

    /**
     * 初始化 RestClient，配置连接和读取超时。
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
        log.info("PythonRagClient 初始化完成 baseUrl={} timeout={}ms", pythonServiceBaseUrl, TIMEOUT_MS);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 组装请求体并调用 Python /internal/rag/vectorize 接口，
     * 超时时间 120 秒，失败时抛出 BusinessException。
     * </p>
     */
    @Override
    public Map<String, Object> vectorize(VectorIndexTask task, List<KnowledgeChunk> chunks, boolean forceRebuild) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("taskId", task.getId());
        requestBody.put("datasourceId", task.getDatasourceId());
        requestBody.put("targetType", task.getTargetType());
        requestBody.put("docId", task.getTargetId());
        requestBody.put("metadataSnapshotId", task.getMetadataSnapshotId());
        requestBody.put("knowledgeVersionNo", task.getKnowledgeVersionNo());
        requestBody.put("previousVersionNo", task.getPreviousVersionNo());
        requestBody.put("force", forceRebuild);
        requestBody.put("chunks", chunks.stream().map(this::toChunkPayload).toList());

        try {
            log.info(
                    "调用 Python RAG 向量化 taskId={} docId={} versionNo={} chunks={} force={}",
                    task.getId(),
                    task.getTargetId(),
                    task.getKnowledgeVersionNo(),
                    chunks.size(),
                    forceRebuild);

            return restClient.post()
                    .uri("/internal/rag/vectorize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        log.error("Python RAG 向量化接口返回异常 taskId={} status={}",
                                task.getId(), response.getStatusCode());
                        throw new BusinessException("RAG 向量化失败");
                    })
                    .body(new ParameterizedTypeReference<>() {});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Python RAG 向量化失败 taskId={} reason={}", task.getId(), e.getMessage(), e);
            throw new BusinessException("RAG 向量化失败：" + e.getMessage());
        }
    }

    /**
     * 将 KnowledgeChunk 实体转换为 Python 服务所需的请求载荷格式。
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> chunkDocument(VectorIndexTask task, String content) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("taskId", task.getId());
        requestBody.put("datasourceId", task.getDatasourceId());
        requestBody.put("docId", task.getTargetId());
        requestBody.put("metadataSnapshotId", task.getMetadataSnapshotId());
        requestBody.put("knowledgeVersionNo", task.getKnowledgeVersionNo());
        requestBody.put("content", content == null ? "" : content);

        try {
            log.info(
                    "调用 Python RAG 文档切割 taskId={} docId={} versionNo={}",
                    task.getId(),
                    task.getTargetId(),
                    task.getKnowledgeVersionNo());

            Map<String, Object> response = restClient.post()
                    .uri("/internal/rag/chunk")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, responseEntity) -> {
                        log.error("Python RAG 文档切割接口返回异常 taskId={} status={}",
                                task.getId(), responseEntity.getStatusCode());
                        throw new BusinessException("RAG 文档切割失败");
                    })
                    .body(new ParameterizedTypeReference<>() {});

            Object chunks = response == null ? null : response.get("chunks");
            if (chunks instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
            }
            return List.of();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Python RAG 文档切割失败 taskId={} reason={}", task.getId(), e.getMessage(), e);
            throw new BusinessException("RAG 文档切割失败：" + e.getMessage());
        }
    }

    @Override
    public void deleteDocVersionVectors(VectorIndexTask task, Integer versionNo) {
        if (versionNo == null) {
            return;
        }
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("datasourceId", task.getDatasourceId());
        requestBody.put("docId", task.getTargetId());
        requestBody.put("knowledgeVersionNo", versionNo);

        try {
            restClient.post()
                    .uri("/internal/rag/vectors/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, responseEntity) -> {
                        log.warn("Python RAG 旧向量清理失败 taskId={} versionNo={} status={}",
                                task.getId(), versionNo, responseEntity.getStatusCode());
                        throw new BusinessException("RAG 旧向量清理失败");
                    })
                    .toBodilessEntity();
            log.info("Python RAG 旧向量清理完成 taskId={} docId={} versionNo={}",
                    task.getId(), task.getTargetId(), versionNo);
        } catch (Exception e) {
            log.warn("Python RAG 旧向量清理异常 taskId={} versionNo={} reason={}",
                    task.getId(), versionNo, e.getMessage());
        }
    }

    private Map<String, Object> toChunkPayload(KnowledgeChunk chunk) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sourceId", chunk.getId());
        payload.put("chunkType", chunk.getChunkType());
        payload.put("chunkText", chunk.getChunkText());
        payload.put("tableName", chunk.getRelatedTable());
        payload.put("relatedColumn", chunk.getRelatedColumn());
        payload.put("reviewStatus", chunk.getReviewStatus());
        payload.put("governanceStatus", "NORMAL");
        return payload;
    }
}
