package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 新体系访问申请事实。只保存资源标识与申请范围，不保存业务原值。 */
@Data
@TableName("iam_s1_access_request")
public class IamS1AccessRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String protocolVersion;
    private Long requesterId;
    private Long datasourceId;
    private Long metadataSnapshotId;
    private String tableName;
    /** 申请字段名列表的 JSON 数组，不含字段值与记录条件原值。 */
    private String requestedColumnsJson;
    /** B4 第一期只支持 ALL（全部记录），不保存记录条件原值。 */
    private String rowScope;
    private LocalDateTime requestedValidUntil;
    private String purpose;
    private String status;
    private Long revisionNo;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
