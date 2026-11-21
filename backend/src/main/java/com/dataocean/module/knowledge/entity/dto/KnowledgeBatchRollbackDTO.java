package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 确认按已核对的全批范围回到直接上一版。
 *
 * @param reviewToken 整批实际基础及目标的核对凭据
 * @param reason 此次回退的业务依据
 */
public record KnowledgeBatchRollbackDTO(
        @NotBlank @Size(max = 4000) String reviewToken, @NotBlank @Size(max = 300) String reason) {}
