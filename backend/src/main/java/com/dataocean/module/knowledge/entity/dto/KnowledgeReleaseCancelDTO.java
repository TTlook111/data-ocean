package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 取消仅作用于已核对的候选版本，并留存实际原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeReleaseCancelDTO extends KnowledgeReleaseBuildDTO {

    /**
     * 留给审计的取消原因。
     */
    @NotBlank
    @Size(max = 500)
    private String reason;
}
