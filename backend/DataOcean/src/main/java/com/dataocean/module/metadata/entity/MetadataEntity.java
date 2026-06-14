package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一实体注册表。
 * <p>
 * 借鉴 OpenMetadata 的 Entity-Relationship 模型，将数据源、表、列、术语、标签等
 * 统一注册为实体，通过 FQN（全限定名）唯一标识，支持全文搜索和血缘图谱。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("metadata_entity")
public class MetadataEntity {

    // ========== 实体类型常量 ==========

    /** 实体类型：数据源 */
    public static final String TYPE_DATASOURCE = "DATASOURCE";

    /** 实体类型：数据库 */
    public static final String TYPE_DATABASE = "DATABASE";

    /** 实体类型：表 */
    public static final String TYPE_TABLE = "TABLE";

    /** 实体类型：列 */
    public static final String TYPE_COLUMN = "COLUMN";

    /** 实体类型：业务术语 */
    public static final String TYPE_GLOSSARY_TERM = "GLOSSARY_TERM";

    /** 实体类型：标签 */
    public static final String TYPE_TAG = "TAG";

    // ========== 字段 ==========

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 实体类型（DATASOURCE / DATABASE / TABLE / COLUMN / GLOSSARY_TERM / TAG） */
    private String entityType;

    /** 实体 UUID（全局唯一标识） */
    private String entityUuid;

    /** 全限定名（datasource.db.table.column 格式） */
    private String fqn;

    /** 实体名称 */
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 实体描述 */
    private String description;

    /** 实体扩展元数据（JSON） */
    private String entityMetadata;

    /** 负责人 ID */
    private Long ownerId;

    /** 版本号 */
    private Integer version;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // ========== FQN 构建工具方法 ==========

    /**
     * 构建数据源级 FQN
     *
     * @param datasourceName 数据源名称
     * @return FQN（如 mysql_prod）
     */
    public static String fqnDatasource(String datasourceName) {
        return datasourceName.toLowerCase();
    }

    /**
     * 构建表级 FQN
     *
     * @param datasourceName 数据源名称
     * @param databaseName   数据库名称
     * @param tableName      表名
     * @return FQN（如 mysql_prod.mydb.orders）
     */
    public static String fqnTable(String datasourceName, String databaseName, String tableName) {
        return datasourceName.toLowerCase() + "." + databaseName.toLowerCase() + "." + tableName.toLowerCase();
    }

    /**
     * 构建列级 FQN
     *
     * @param datasourceName 数据源名称
     * @param databaseName   数据库名称
     * @param tableName      表名
     * @param columnName     列名
     * @return FQN（如 mysql_prod.mydb.orders.customer_id）
     */
    public static String fqnColumn(String datasourceName, String databaseName, String tableName, String columnName) {
        return fqnTable(datasourceName, databaseName, tableName) + "." + columnName.toLowerCase();
    }

    /**
     * 构建术语级 FQN
     *
     * @param glossaryName 术语表名称
     * @param termName     术语名称
     * @return FQN（如 glossary.销售.订单金额）
     */
    public static String fqnGlossaryTerm(String glossaryName, String termName) {
        return "glossary." + glossaryName.toLowerCase() + "." + termName.toLowerCase();
    }

    /**
     * 构建标签级 FQN
     *
     * @param classificationName 分类名称
     * @param tagName            标签名称
     * @return FQN（如 tag.PII.敏感）
     */
    public static String fqnTag(String classificationName, String tagName) {
        return "tag." + classificationName.toLowerCase() + "." + tagName.toLowerCase();
    }
}
