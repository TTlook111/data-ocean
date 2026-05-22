package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 元数据快照实体类。
 * <p>
 * 每次元数据采集完成后生成一份快照，记录该时刻数据源的 Schema 全貌。
 * 快照经过质量校验和审核后可发布，发布后的快照用于生成 skills.md 并向量化进入 RAG。
 * 状态流转：DRAFT → CHECKING → ISSUE_FOUND/APPROVED → PUBLISHED → EXPIRED。
 * </p>
 */
@Data
@TableName("metadata_snapshot")
public class MetadataSnapshot {

    /** 快照状态：草稿（刚采集完成） */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 快照状态：校验中 */
    public static final String STATUS_CHECKING = "CHECKING";

    /** 快照状态：发现问题 */
    public static final String STATUS_ISSUE_FOUND = "ISSUE_FOUND";

    /** 快照状态：审核通过 */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 快照状态：已发布 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 快照状态：已过期 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 快照版本号（同一数据源递增） */
    private Integer snapshotVersion;

    /** Schema 哈希值（用于快速判断是否有变更） */
    private String schemaHash;

    /** 快照状态 */
    private String status;

    /** 表数量 */
    private Integer tableCount;

    /** 字段数量 */
    private Integer columnCount;

    /** 总行数估算 */
    private Long totalRowsEstimate;

    /** 质量评分（0~100） */
    private BigDecimal qualityScore;

    /** 关联的同步任务ID */
    private Long syncTaskId;

    /** 审核人用户ID */
    private Long reviewedBy;

    /** 审核时间 */
    private LocalDateTime reviewedAt;

    /** 发布时间 */
    private LocalDateTime publishedAt;

    /** 过期时间 */
    private LocalDateTime expiredAt;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
