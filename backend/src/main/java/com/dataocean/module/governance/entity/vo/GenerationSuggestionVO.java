package com.dataocean.module.governance.entity.vo;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 可披露的真实建议及依据。
 *
 * @param suggestionId 实际保存的原始建议
 * @param content 模型生成的确切业务字段
 * @param evidenceRefs 采用的确切资料片段
 * @param modelCallId 有诊断权限时可定位实际模型调用
 */
public record GenerationSuggestionVO(
        String suggestionId,
        JsonNode content,
        List<GovernanceEvidenceVO> evidenceRefs,
        String modelCallId) {}
