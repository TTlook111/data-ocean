package com.dataocean.module.fieldtag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段标签实体类
 * <p>
 * 记录字段的业务标签信息，如金额类、时间类、状态类、敏感、废弃等。
 * 标签来源包括系统自动打标、人工打标和 AI 建议。
 * </p>
 */
@Data
@TableName("field_tag")
public class FieldTag {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的字段元数据ID */
    private Long columnMetaId;

    /** 标签编码（如 AMOUNT、TIME、STATUS） */
    private String tagCode;

    /** 标签显示名称 */
    private String tagName;

    /** 标签来源：SYSTEM/MANUAL/AI_SUGGESTED */
    private String source;

    /** 创建人用户ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
