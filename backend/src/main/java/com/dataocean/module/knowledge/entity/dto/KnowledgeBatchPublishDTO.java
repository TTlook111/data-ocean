package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对已逐项核对的全部发布成员做一次确认。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KnowledgeBatchPublishDTO extends KnowledgeBatchSelectionDTO {

    /**
     * 与本次整批固定输入绑定的核对凭据。
     */
    @NotBlank
    @Size(max = 4000)
    private String reviewToken;
}
