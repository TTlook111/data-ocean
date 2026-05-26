package com.dataocean.module.fieldtag.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 字段标签请求 DTO
 * <p>
 * 用于单个字段打标签的请求参数。
 * </p>
 */
@Data
public class FieldTagRequestDTO {

    /** 字段元数据ID */
    @NotNull(message = "字段ID不能为空")
    private Long columnMetaId;

    /** 标签编码 */
    @NotBlank(message = "标签编码不能为空")
    private String tagCode;
}
