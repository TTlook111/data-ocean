package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 确认按已核对的全批范围回到直接上一版。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBatchRollbackDTO {

    /**
     * 整批实际基础及目标的核对凭据。
     */
    @NotBlank
    @Size(max = 4000)
    @ToString.Exclude
    private String reviewToken;

    /**
     * 此次回退的业务依据。
     */
    @NotBlank
    @Size(max = 300)
    private String reason;
}
