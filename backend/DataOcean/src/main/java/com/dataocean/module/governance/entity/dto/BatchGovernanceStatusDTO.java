package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 批量治理状态更新请求参数。
 * <p>
 * 用于将表下字段统一更新为指定治理状态，并支持排除部分字段。
 * </p>
 */
@Data
public class BatchGovernanceStatusDTO {

    @NotBlank(message = "治理状态不能为空")
    private String governanceStatus;

    private String remark;

    private List<String> excludeColumns;
}
