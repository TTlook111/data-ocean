package com.dataocean.module.governance.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 表说明或字段解释的生成目标和固定内容范围。
 */
@Data
public class GenerateTargetDTO {

    /**
     * TABLE 表示表说明，COLUMN 表示字段解释。
     */
    @NotBlank
    private String kind;

    /**
     * 被选择的稳定表或字段编号。
     */
    @NotBlank
    private String targetId;

    /**
     * 实际预览的并发和结构版本。
     */
    @Valid
    @NotNull
    private EntryPreconditionDTO precondition;

    /**
     * 拟生成的业务字段，空值代表整项。
     */
    private List<@NotBlank String> contentPaths;
}
