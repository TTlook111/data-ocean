package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 数据授权主事实。 */
@Data
@TableName("iam_s1_data_grant")
public class IamS1DataGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String protocolVersion;
    private String subjectType;
    private Long subjectId;
    private String departmentScope;
    private Long datasourceId;
    private String resourceScope;
    private Long metadataSnapshotId;
    private String tableName;
    private String effect;
    private String grantSource;
    private Long sourceReferenceId;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private String status;
    private Long revisionNo;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
