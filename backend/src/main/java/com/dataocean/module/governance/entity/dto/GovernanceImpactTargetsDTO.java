package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 为未定位资料变化登记实际的同源影响目标。
 */
@Data
public class GovernanceImpactTargetsDTO {

    /**
     * 待定位记录的当前版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 一次实际核对后选定的治理条目。
     */
    @NotEmpty
    @Size(max = 100)
    private List<@Valid GovernanceTargetDTO> targets;
}
