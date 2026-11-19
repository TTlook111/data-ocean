package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 明确使用该条目的哪些建议内容。
 *
 * @param suggestionId 实际建议编号
 * @param contentPaths 人工核对后选择的业务字段
 */
public record SuggestionSelectionDTO(
        @NotBlank String suggestionId,
        @NotEmpty List<@NotBlank String> contentPaths) {}
