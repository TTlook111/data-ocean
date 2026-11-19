package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 定位当前来源中的表说明或字段。
 *
 * @param objectId 当前稳定表或视图编号
 * @param kind 表说明 TABLE 或字段 COLUMN
 * @param targetId 表说明对应对象编号，字段对应列编号
 */
public record GovernanceTargetDTO(
        @NotBlank String objectId,
        @NotBlank String kind,
        @NotBlank String targetId) {}
