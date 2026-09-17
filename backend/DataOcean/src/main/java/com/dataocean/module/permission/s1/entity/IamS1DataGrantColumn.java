package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 授权字段事实；没有行表示表级 DENY，不表示 ALLOW 全字段。 */
@Data
@TableName("iam_s1_data_grant_column")
public class IamS1DataGrantColumn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long grantId;
    private Long metadataSnapshotId;
    private Long columnMetaId;
    private String tableName;
    private String columnName;
    private LocalDateTime createdAt;
}
