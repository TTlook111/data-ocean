package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 治理状态更新请求参数。
 * <p>
 * 用于表或字段的单条治理状态调整。
 * </p>
 */
@Data
public class GovernanceStatusUpdateDTO {

    @NotBlank(message = "治理状态不能为空")
    private String governanceStatus;

    private String remark;
}
