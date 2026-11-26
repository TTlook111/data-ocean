package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 人工为关联明确选择的一个连接字段对。
 */
@Data
public class GovernanceFieldPairDTO {

    /**
     * 连接一侧的同源字段，必须属于明确表对象。
     */
    @Valid
    @NotNull
    private GovernanceTargetDTO left;

    /**
     * 连接另一侧的同源字段，必须属于另一个表对象。
     */
    @Valid
    @NotNull
    private GovernanceTargetDTO right;
}
