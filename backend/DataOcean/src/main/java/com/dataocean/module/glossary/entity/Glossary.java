package com.dataocean.module.glossary.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务术语表实体。
 * <p>
 * 术语表是术语的顶级分类容器，如"财务指标"、"销售术语"等。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("glossary")
public class Glossary {

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已发布 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 术语表名称（唯一） */
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 描述 */
    private String description;

    /** 负责人 ID */
    private Long ownerId;

    /** 状态（DRAFT / PUBLISHED） */
    private String status;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
