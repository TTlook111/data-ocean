package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 给业务内容关联一份已采集结构。
 *
 * @param contentPath 采用依据的业务内容字段
 * @param objectId 实际引用的同源对象
 * @param columnId 实际引用的同源字段，可空
 */
public record GovernanceStructureRefDTO(
        @NotBlank String contentPath,
        @NotBlank String objectId,
        String columnId) {}
