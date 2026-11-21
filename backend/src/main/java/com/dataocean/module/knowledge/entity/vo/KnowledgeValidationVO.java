package com.dataocean.module.knowledge.entity.vo;

import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Fragment;

import java.time.Instant;
import java.util.List;

/**
 * 验证运行的当前状态、可以披露的实际证据和原始历史。
 */
public record KnowledgeValidationVO(
        Run run, List<Run> history, boolean canReadResults, boolean canReadEvidence, List<Question> questions) {

    /**
     * 每次独立运行关联确切候选与索引代次。
     */
    public record Run(String id, String candidateId, String buildId, String state, Instant createdAt) {}

    /**
     * 审核预期与技术实际值分别回读，结论不覆盖原始证据。
     */
    public record Question(
            String id,
            String testId,
            String testRevisionId,
            int revision,
            String mode,
            String question,
            String expected,
            String basis,
            String actualCount,
            String statement,
            String failureCode,
            String state,
            String executorId,
            List<Fragment> evidence,
            Judgment judgment) {}

    /**
     * 人工判断与说明题证据分别保存。理由可能包含原文，仅向有权读正文的人员披露。
     */
    public record Judgment(String decision, String reason, String decidedBy, Instant decidedAt) {}
}
