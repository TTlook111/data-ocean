package com.dataocean.module.knowledge.client.impl;

import com.dataocean.common.annotation.PythonServiceRetry;
import com.dataocean.common.client.PythonClientSupport;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.exception.PythonRetryableException;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Recover;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** 调用 Java 已筛选的快照生成与知识候选接口，统一保留失败原因。 */
@Service
public class PythonKnowledgeClientImpl implements PythonKnowledgeClient {

    @Override
    @PythonServiceRetry
    public Map<String, Object> generateBusinessCandidate(Map<String, Object> request) {
        return postDraft("/internal/knowledge/business-candidate", request);
    }

    @Override
    @PythonServiceRetry
    public Map<String, Object> generateSnapshotDraft(Map<String, Object> request) {
        return postDraft("/internal/knowledge/generate-draft", request);
    }

    @Override
    @PythonServiceRetry
    public Map<String, Object> supplementSemantics(Map<String, Object> request) {
        return postDraft("/internal/knowledge/supplement-semantics", request);
    }

    @Override
    @PythonServiceRetry
    public Map<String, Object> generateFieldSemanticCandidate(Map<String, Object> request) {
        return postDraft("/internal/knowledge/field-semantic-candidate", request);
    }

    private Map<String, Object> postDraft(String path, Map<String, Object> request) {
        try {
            return restClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
        } catch (ResourceAccessException ex) {
            if (PythonClientSupport.isReadTimeout(ex)) throw new BusinessException("业务释义生成超时，原有草稿未变更");
            throw new PythonRetryableException("知识生成服务暂不可用", ex);
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            String body = ex.getResponseBodyAsString();
            if (body.contains("AI_BILLING_REQUIRED"))
                throw new BusinessException("AI 供应商账户欠费，业务释义未生成；原有目录与人工内容已保留，请处理账户后重试");
            if (body.contains("AI_GENERATION_TIMEOUT"))
                throw new BusinessException("业务释义生成超时，原有目录与人工内容已保留，请稍后重试");
            if (body.contains("AI_INVALID_OUTPUT"))
                throw new BusinessException("AI 返回的释义或字段依据无效，原有目录与人工内容已保留，请重新生成");
            throw new BusinessException("知识生成失败，原有目录与人工内容已保留");
        }
    }

    /** 复用的 RestClient 实例 */
    private final RestClient restClient;

    /**
     * 构造 Python 知识库客户端。
     */
    public PythonKnowledgeClientImpl(@Qualifier("pythonRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Recover
    public Map<String, Object> preserveDraftFailure(BusinessException exception, Map<String, Object> request) {
        // 业务拒绝不重试，直接保留明确原因，避免被重试代理包装成通用 500。
        throw exception;
    }

    @Recover
    public Map<String, Object> recoverDraft(PythonRetryableException exception, Map<String, Object> request) {
        throw new BusinessException("知识生成服务暂不可用，原有目录与人工内容已保留");
    }

}
