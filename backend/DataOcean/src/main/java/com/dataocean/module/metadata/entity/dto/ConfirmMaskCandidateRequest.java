package com.dataocean.module.metadata.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认 MASK 策略候选请求 DTO。
 * <p>
 * 替代原始 Map 接收请求体，提供参数校验。
 * </p>
 */
@Data
public class ConfirmMaskCandidateRequest {

    /** 脱敏策略（如 PHONE / ID_CARD / EMAIL / BANK_CARD / NAME） */
    @NotNull(message = "maskStrategy 不能为空")
    private String maskStrategy;
}
