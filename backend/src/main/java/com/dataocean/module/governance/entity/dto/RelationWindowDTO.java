package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 关系适用的业务日期边界，确认后随关系修订发布。
 */
@Data
public class RelationWindowDTO {

    /**
     * NONE 明确没有日期限制，DATE_RANGE 使用至少一个日期端点。
     */
    @Pattern(regexp = "NONE|DATE_RANGE")
    private String mode;

    /**
     * 可选的最早适用业务日期，包含当天。
     */
    @Size(max = 10)
    private String validFrom;

    /**
     * 可选的最晚适用业务日期，包含当天。
     */
    @Size(max = 10)
    private String validThrough;
}
