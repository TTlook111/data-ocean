package com.dataocean.module.knowledge.client.impl;

import com.dataocean.common.client.PythonClientSupport;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.exception.PythonRetryableException;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
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

    private final RestClient restClient;

    public PythonRagClientImpl(@Qualifier("pythonRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 组装请求体并调用 Python /internal/rag/vectorize 接口，写入 Milvus。
     * </p>
     * <p>
     * 向量化操作不做自动重试（无 @Retryable），原因：
     * <ul>
     *   <li>向量化涉及批量写入 Milvus，幂等性未完全确认</li>
     *   <li>重试可能导致重复写入，需要人工介入检查数据一致性</li>
     *   <li>失败时由上层调度器决定是否重试，而非客户端自动重试</li>
     * </ul>
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
                        throw new BusinessException("RAG 向量化失败，Python 服务返回 " + response.getStatusCode().value());
                    })
                    .body(new ParameterizedTypeReference<>() {});
        } catch (ResourceAccessException e) {
            log.error("调用 Python RAG 向量化访问异常 taskId={} reason={}", task.getId(), e.getMessage(), e);
            // 区分读超时和连接失败，提供更准确的错误提示
            if (PythonClientSupport.isReadTimeout(e)) {
                throw new BusinessException("RAG 向量化超时，请稍后重试");
            }
            throw new BusinessException("RAG 向量化服务暂时不可用，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Python RAG 向量化失败 taskId={} reason={}", task.getId(), e.getMessage(), e);
            throw new BusinessException("RAG 向量化失败，请稍后重试");
        }
    }

    /**
     * 将 KnowledgeChunk 实体转换为 Python 服务所需的请求载荷格式。
     */
    @Override
    @SuppressWarnings("unchecked")
    @Retryable(
            retryFor = PythonRetryableException.class,
            maxAttempts = 2,
            backoff = @Backoff(delay = 1000)
    )
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
                        throw PythonClientSupport.statusException(responseEntity.getStatusCode(), "RAG 文档切割失败");
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
        } catch (PythonRetryableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // 区分读超时和连接失败：读超时不重试，连接失败可重试
            if (PythonClientSupport.isReadTimeout(e)) {
                throw new BusinessException("RAG 文档切割超时，请稍后重试");
            }
            throw new PythonRetryableException("RAG 文档切割服务暂时不可用", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Python RAG 文档切割失败 taskId={} reason={}", task.getId(), e.getMessage(), e);
            throw new BusinessException("RAG 文档切割失败，请稍后重试");
        }
    }

    @Recover
    public List<Map<String, Object>> recoverChunkDocument(PythonRetryableException exception,
                                                           VectorIndexTask task,
                                                           String content) {
        log.error("Python RAG 文档切割重试后仍失败 taskId={} reason={}",
                task.getId(), exception.getMessage(), exception);
        throw new BusinessException("RAG 文档切割失败，请稍后重试");
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
