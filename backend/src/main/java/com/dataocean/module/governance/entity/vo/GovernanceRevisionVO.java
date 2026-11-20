package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 可查看的实际业务内容修订。
 */
@Data
@AllArgsConstructor
public class GovernanceRevisionVO {

    /**
     * 修订主键。
     */
    private String revisionId;

    /**
     * 该条目的修订序号。
     */
    private int revision;

    /**
     * 确切修订保存的业务字段。
     */
    private JsonNode content;

    /**
     * 人工或 AI 辅助。
     */
    private String origin;

    /**
     * 定义编辑时的固定结构。
     */
    private GovernanceBasisVO structureBasis;

    /**
     * 实际引用及当前披露资格。
     */
    private List<GovernanceEvidenceVO> evidenceRefs;

    /**
     * 保存者。
     */
    private String createdBy;

    /**
     * 实质修订创建时刻。
     */
    private Instant createdAt;
}
