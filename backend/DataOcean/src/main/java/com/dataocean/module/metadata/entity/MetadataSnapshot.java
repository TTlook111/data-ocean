package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("metadata_snapshot")
public class MetadataSnapshot {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CHECKING = "CHECKING";
    public static final String STATUS_ISSUE_FOUND = "ISSUE_FOUND";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private Integer snapshotVersion;
    private String schemaHash;
    private String status;
    private Integer tableCount;
    private Integer columnCount;
    private Long totalRowsEstimate;
    private BigDecimal qualityScore;
    private Long syncTaskId;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime expiredAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
