package com.dataocean.module.fieldtag.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量打标请求 DTO
 * <p>
 * 用于批量为多个字段打同一标签的请求参数。
 * </p>
 */
@Data
public class BatchTagRequestDTO {

    /** 字段元数据ID列表 */
    @NotEmpty(message = "字段ID列表不能为空")
    private List<Long> columnMetaIds;

    /** 标签编码 */
    @NotBlank(message = "标签编码不能为空")
    private String tagCode;
}
