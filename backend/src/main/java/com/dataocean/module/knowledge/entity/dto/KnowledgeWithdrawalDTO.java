package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按用户已核对的完整范围原子撤下正式知识。
 *
 * @param reviewToken 确认弹窗实际展示范围对应的短时核对凭据
 * @param reason 本批正式退出的原因
 */
public record KnowledgeWithdrawalDTO(
        @NotBlank @Size(max = 4000) String reviewToken, @NotBlank @Size(max = 300) String reason) {}
