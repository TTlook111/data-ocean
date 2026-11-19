package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 完整的业务字段及可选择更新的来源引用。
 *
 * @param content 根据条目类型绑定明确的表说明或字段定义
 * @param structureRefs 同源技术依据，空值表示保留
 * @param manualEvidence 人工说明及资料引用，空值表示保留
 * @param suggestionSelection 人工选择写入的建议字段
 * @param gapResolutions 本次已核清的事项
 */
public record GovernanceDraftInputDTO(
        @NotNull JsonNode content,
        @Size(max = 100) List<@Valid GovernanceStructureRefDTO> structureRefs,
        @Size(max = 100) List<@Valid GovernanceManualEvidenceDTO> manualEvidence,
        @Valid SuggestionSelectionDTO suggestionSelection,
        @Size(max = 100) List<@Valid GovernanceGapResolutionDTO> gapResolutions) {}
