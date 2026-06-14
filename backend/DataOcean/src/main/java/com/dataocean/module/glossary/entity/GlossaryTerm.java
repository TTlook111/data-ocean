package com.dataocean.module.glossary.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务术语条目实体。
 * <p>
 * 术语支持三级嵌套：术语表 → 术语 → 子术语。
 * 每个术语有同义词（JSON 数组），用于 NL2SQL 查询改写时扩展用户问题。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("glossary_term")
public class GlossaryTerm {

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：待审核 */
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";

    /** 状态：已通过 */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 状态：已拒绝 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属术语表 ID */
    private Long glossaryId;

    /** 父术语 ID（NULL 表示顶级术语） */
    private Long parentId;

    /** 术语名称 */
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 描述 */
    private String description;

    /** 同义词列表（JSON 数组） */
    private String synonyms;

    /** 关联术语 ID 列表（JSON 数组） */
    private String relatedTerms;

    /** 全限定名（glossary.术语表.术语） */
    private String fqn;

    /** 状态（DRAFT / PENDING_REVIEW / APPROVED / REJECTED） */
    private String status;

    /** 审核人 ID */
    private Long reviewerId;

    /** 审核时间 */
    private LocalDateTime reviewedAt;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
