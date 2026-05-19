package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("db_table_meta")
public class DbTableMeta {

    public static final String GOVERNANCE_DISCOVERED = "DISCOVERED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private Long datasourceId;
    private String tableName;
    private String tableComment;
    private String tableType;
    private String engine;
    private String tableCharset;
    private Long rowCountEstimate;
    private Long dataSizeBytes;
    private Long indexSizeBytes;
    private String governanceStatus;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
