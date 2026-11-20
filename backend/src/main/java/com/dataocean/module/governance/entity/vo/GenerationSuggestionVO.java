package com.dataocean.module.governance.entity.vo;

import java.util.List;

import tools.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 可披露的真实建议及依据。
 */
@Data
@AllArgsConstructor
public class GenerationSuggestionVO {

    /**
     * 实际保存的原始建议。
     */
    private String suggestionId;

    /**
     * 模型生成的确切业务字段。
     */
    private JsonNode content;

    /**
     * 采用的确切资料片段。
     */
    private List<GovernanceEvidenceVO> evidenceRefs;

    /**
     * 有诊断权限时可定位实际模型调用。
     */
    private String modelCallId;
}
