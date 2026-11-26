package com.dataocean.module.datasource.entity.dto;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;

/**
 * HTTP 表单中的资料修订提案，原文件在独立 multipart 部件。
 */
@Data
public class BusinessNoteDTO {

    /**
     * 新增资料时选择的稳定数据源。
     */
    private String sourceId;

    /**
     * 编辑时读取的聚合版本。
     */
    @Min(1)
    private Integer expectedVersion;

    /**
     * 资料标题。
     */
    @NotBlank
    @CodePointLength(max = 120)
    private String title;

    /**
     * 当前完整说明。
     */
    @CodePointLength(max = 5000)
    private String body;

    /**
     * 编辑时保留的现有附件，不跨资料使用。
     */
    @Size(max = 5)
    private List<@NotBlank String> keptAttachmentIds;
}
