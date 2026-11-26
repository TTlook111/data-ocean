package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import lombok.Data;

/**
 * 明确使用该条目的哪些建议内容。
 */
@Data
public class SuggestionSelectionDTO {

    /**
     * 实际建议编号。
     */
    @NotBlank
    private String suggestionId;

    /**
     * 人工核对后选择的业务字段。
     */
    @NotEmpty
    private List<@NotBlank String> contentPaths;
}
