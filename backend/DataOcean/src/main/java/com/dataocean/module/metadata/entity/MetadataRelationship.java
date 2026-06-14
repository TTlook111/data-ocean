package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一关系边表。
 * <p>
 * 存储实体之间的关系（血缘、标签、术语、外键等），
 * 通过 relation_type 区分关系类型，通过 relation_metadata 存储扩展信息。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("metadata_relationship")
public class MetadataRelationship {

    // ========== 关系类型常量 ==========

    /** 关系类型：包含（数据源 CONTAINS 表） */
    public static final String TYPE_CONTAINS = "CONTAINS";

    /** 关系类型：组成（表 HAS_PART 列） */
    public static final String TYPE_HAS_PART = "HAS_PART";

    /** 关系类型：血缘（表A LINEAGE 表B） */
    public static final String TYPE_LINEAGE = "LINEAGE";

    /** 关系类型：标签（列 TAGGED_WITH 标签） */
    public static final String TYPE_TAGGED_WITH = "TAGGED_WITH";

    /** 关系类型：术语（列 GLOSSARY_OF 术语） */
    public static final String TYPE_GLOSSARY_OF = "GLOSSARY_OF";

    /** 关系类型：外键（列A FOREIGN_KEY 列B） */
    public static final String TYPE_FOREIGN_KEY = "FOREIGN_KEY";

    /** 关系类型：派生（指标 DERIVED_FROM 原始列） */
    public static final String TYPE_DERIVED_FROM = "DERIVED_FROM";

    /** 关系类型：相关（术语 RELATED_TO 术语） */
    public static final String TYPE_RELATED_TO = "RELATED_TO";

    // ========== 血缘类型常量（存储在 relation_metadata.lineage_type） ==========

    /** 血缘类型：查询血缘（sqlglot 自动提取） */
    public static final String LINEAGE_QUERY = "QUERY";

    /** 血缘类型：ETL 血缘（管理员手动创建） */
    public static final String LINEAGE_ETL = "ETL";

    /** 血缘类型：手动血缘（业务层面补充） */
    public static final String LINEAGE_MANUAL = "MANUAL";

    // ========== 字段 ==========

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源实体 ID */
    private Long sourceId;

    /** 源实体类型 */
    private String sourceType;

    /** 目标实体 ID */
    private Long targetId;

    /** 目标实体类型 */
    private String targetType;

    /** 关系类型 */
    private String relationType;

    /** 关系扩展元数据（JSON，如血缘类型、列映射等） */
    private String relationMetadata;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
