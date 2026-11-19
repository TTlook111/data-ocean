package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 为未定位资料变化登记实际的同源影响目标。
 *
 * @param expectedVersion 待定位记录的当前版本
 * @param targets 一次实际核对后选定的治理条目
 */
public record GovernanceImpactTargetsDTO(
        @Positive int expectedVersion,
        @NotEmpty @Size(max = 100) List<@Valid GovernanceTargetDTO> targets) {}
