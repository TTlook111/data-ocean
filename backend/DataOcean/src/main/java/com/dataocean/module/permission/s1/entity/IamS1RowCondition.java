package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 结构化记录条件；不包含手写 SQL。 */
@Data
@TableName("iam_s1_row_condition")
public class IamS1RowCondition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long grantId;
    private Long metadataSnapshotId;
    private String tableName;
    private String matchType;
    private Integer sequenceNo;
    private Long columnMetaId;
    private String columnName;
    private String operatorCode;
    private String valueType;
    private String structuredValueJson;
    private String parameterReference;
    private LocalDateTime createdAt;
}
