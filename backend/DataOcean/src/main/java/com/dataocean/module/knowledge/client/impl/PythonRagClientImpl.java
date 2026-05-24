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
