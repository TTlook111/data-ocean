package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 按用户已核对的完整范围原子撤下正式知识。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeWithdrawalDTO {

    /**
     * 确认弹窗实际展示范围对应的短时核对凭据。
     */
    @NotBlank
    @Size(max = 4000)
    @ToString.Exclude
    private String reviewToken;

    /**
     * 本批正式退出的原因。
     */
    @NotBlank
    @Size(max = 300)
    private String reason;
}
