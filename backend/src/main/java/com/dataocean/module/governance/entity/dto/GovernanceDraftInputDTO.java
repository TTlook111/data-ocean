package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

import lombok.Data;

/**
 * 完整的业务字段及可选择更新的来源引用。
 */
@Data
public class GovernanceDraftInputDTO {

    /**
     * 根据条目类型绑定明确的表说明或字段定义。
     */
    @NotNull
    private JsonNode content;

    /**
     * 同源技术依据，空值表示保留。
     */
    @Size(max = 100)
    private List<@Valid GovernanceStructureRefDTO> structureRefs;

    /**
     * 人工说明及资料引用，空值表示保留。
     */
    @Size(max = 100)
    private List<@Valid GovernanceManualEvidenceDTO> manualEvidence;

    /**
     * 人工选择写入的建议字段。
     */
    @Valid
    private SuggestionSelectionDTO suggestionSelection;

    /**
     * 本次已核清的事项。
     */
    @Size(max = 100)
    private List<@Valid GovernanceGapResolutionDTO> gapResolutions;
}
