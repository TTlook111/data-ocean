package com.dataocean.module.knowledge.entity.vo;

import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO.Content;

import java.time.Instant;
import java.util.List;

/**
 * 发布范围、读回的版本证据及实际生效状态。
 */
public final class KnowledgePublicationVO {

    /**
     * 契约只通过明示的业务记录返回。
     */
    private KnowledgePublicationVO() {}

    /**
     * 核对时看到的完整单文档范围及确切构建、验证结果。
     */
    public record Preview(
            String title,
            Integer beforeRevision,
            int targetRevision,
            String candidateId,
            String buildId,
            String runId,
            int verifiedChunks,
            String reviewToken) {}

    /**
     * 正式版本中的原始引用及当前使用状态。
     */
    public record Version(
            String id,
            String scopeId,
            String batchId,
            String taskId,
            String documentId,
            String documentRevisionId,
            int revision,
            String title,
            String candidateId,
            String buildId,
            String validationId,
            String embeddingRevisionId,
            String collection,
            String publishedBy,
            Instant switchedAt,
            Instant verifiedAt,
            List<String> objects,
            String availability) {}

    /**
     * 版本详情只允许从实际绑定回读当前和直接上一版。
     */
    public record Detail(Version current, Version previous, Content content, Content previousContent) {}

    /**
     * 回读本次发布结果以及当前真实指针，历史发布和当前版本可独立变化。
     */
    public record Outcome(
            String state, Version published, Version active, Version previous, String issue, boolean canReconcile) {}
}
