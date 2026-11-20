package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import lombok.Data;

/**
 * 绑定当前治理条目及其结构依据。
 */
@Data
public class EntryPreconditionDTO {

    /**
     * 当前聚合版本，虚拟条目使用空值。
     */
    @Positive
    private Integer expectedVersion;

    /**
     * 表结构或当前字段结构修订编号。
     */
    @NotBlank
    private String expectedStructureRevisionId;
}
