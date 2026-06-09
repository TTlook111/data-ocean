package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dataocean.module.governance.constant.GovernanceStatuses;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库表元数据实体类。
 * <p>
 * 记录从业务数据源采集到的表级别元数据信息，包括表名、注释、引擎、字符集、
 * 行数估算、数据大小、索引大小等，用于元数据治理和 Schema RAG。
 * </p>
 */
@Data
@TableName("db_table_meta")
public class DbTableMeta {

    /** 治理状态：已发现（采集后的初始状态） - 使用统一常量 */
    public static final String GOVERNANCE_DISCOVERED = GovernanceStatuses.DISCOVERED;

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属快照ID */
    private Long snapshotId;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 表名 */
    private String tableName;

    /** 表注释 */
    private String tableComment;

    /** 表类型（TABLE / VIEW） */
    private String tableType;

    /** 存储引擎（如 InnoDB） */
    private String engine;

    /** 表字符集/排序规则 */
    private String tableCharset;

    /** 行数估算值 */
    private Long rowCountEstimate;

    /** 数据大小（字节） */
    private Long dataSizeBytes;

    /** 索引大小（字节） */
    private Long indexSizeBytes;

    /** 索引信息（JSON 格式） */
    private String indexesInfo;

    /** 治理状态（DISCOVERED / NORMAL / RECOMMENDED / SENSITIVE / DEPRECATED / BLOCKED） */
    private String governanceStatus;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
