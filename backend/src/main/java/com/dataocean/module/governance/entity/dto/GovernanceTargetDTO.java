package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * 定位当前来源中的表说明或字段。
 */
@Data
public class GovernanceTargetDTO {

    /**
     * 当前稳定表或视图编号。
     */
    @NotBlank
    private String objectId;

    /**
     * 表说明 TABLE 或字段 COLUMN。
     */
    @NotBlank
    private String kind;

    /**
     * 表说明对应对象编号，字段对应列编号。
     */
    @NotBlank
    private String targetId;
}
