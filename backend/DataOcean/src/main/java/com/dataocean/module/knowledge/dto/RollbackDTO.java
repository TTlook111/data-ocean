package com.dataocean.module.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 版本回滚请求参数
 */
@Data
public class RollbackDTO {

    /** 目标版本号 */
    @NotNull(message = "目标版本号不能为空")
    private Integer targetVersionNo;
}
