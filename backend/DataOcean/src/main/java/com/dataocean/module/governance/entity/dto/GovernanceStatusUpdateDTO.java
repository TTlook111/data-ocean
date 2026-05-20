package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GovernanceStatusUpdateDTO {

    @NotBlank(message = "治理状态不能为空")
    private String governanceStatus;

    private String remark;
}
