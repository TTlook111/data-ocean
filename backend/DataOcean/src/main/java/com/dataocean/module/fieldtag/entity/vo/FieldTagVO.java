package com.dataocean.module.fieldtag.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段标签视图对象
 * <p>
 * 返回给前端的字段标签信息。
 * </p>
 */
@Data
public class FieldTagVO {

    /** 标签ID */
    private Long id;

    /** 字段元数据ID */
    private Long columnMetaId;

    /** 标签编码 */
    private String tagCode;

    /** 标签显示名称 */
    private String tagName;

    /** 标签来源 */
    private String source;

    /** 创建人用户ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
