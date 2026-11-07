package com.dataocean.module.governance.entity.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 质量校验触发请求参数。
 * <p>
 * 可指定校验维度和表名范围；为空时执行默认全量校验。
 * </p>
 */
@Data
public class QualityCheckRequestDTO {
    /** 网络重试和刷新核对沿用同一标识，不允许把未知检查当作新的执行。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private String operationId;

    private Long snapshotId;
    private List<String> dimensions;
    private List<String> tableNames;
}
