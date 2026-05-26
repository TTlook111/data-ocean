package com.dataocean.module.prompt.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Prompt 版本回滚请求 DTO
 * <p>
 * 指定要回滚到的目标版本号。
 * </p>
 */
@Data
public class PromptRollbackDTO {

    /** 目标版本号 */
    @NotNull(message = "目标版本号不能为空")
    private Integer targetVersionNo;
}
