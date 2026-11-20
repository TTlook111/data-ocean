package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * 给业务内容关联一份已采集结构。
 */
@Data
public class GovernanceStructureRefDTO {

    /**
     * 采用依据的业务内容字段。
     */
    @NotBlank
    private String contentPath;

    /**
     * 实际引用的同源对象。
     */
    @NotBlank
    private String objectId;

    /**
     * 实际引用的同源字段，可空。
     */
    private String columnId;
}
