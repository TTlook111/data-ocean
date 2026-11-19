package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 绑定当前治理条目及其结构依据。
 *
 * @param expectedVersion 当前聚合版本，虚拟条目使用空值
 * @param expectedStructureRevisionId 表结构或当前字段结构修订编号
 */
public record EntryPreconditionDTO(
        @Positive Integer expectedVersion,
        @NotBlank String expectedStructureRevisionId) {}
