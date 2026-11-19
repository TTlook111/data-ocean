package com.dataocean.module.governance.entity.vo;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 可查看的实际业务内容修订。
 *
 * @param revisionId 修订主键
 * @param revision 该条目的修订序号
 * @param content 确切修订保存的业务字段
 * @param origin 人工或 AI 辅助
 * @param structureBasis 定义编辑时的固定结构
 * @param evidenceRefs 实际引用及当前披露资格
 * @param createdBy 保存者
 * @param createdAt 实质修订创建时刻
 */
public record GovernanceRevisionVO(
        String revisionId,
        int revision,
        JsonNode content,
        String origin,
        GovernanceBasisVO structureBasis,
        List<GovernanceEvidenceVO> evidenceRefs,
        String createdBy,
        Instant createdAt) {}
