package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对已核实的正式绑定发起暂停。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeVersionPauseDTO {

    /**
     * 暂停本次使用范围的实际原因。
     */
    @NotBlank
    @Size(max = 300)
    private String reason;
}
