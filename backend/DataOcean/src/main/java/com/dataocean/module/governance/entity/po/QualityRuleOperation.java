package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 与规则和修订同事务保存；不保存客户端配置正文或认证凭据。 */
@Data
@TableName("quality_rule_operation")
public class QualityRuleOperation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorUserId;
    private String operationId;
    private String requestHash;
    private String action;
    private Long sourceRuleId;
    private String sourceScopeType;
    private Long sourceDatasourceId;
    private String targetScopeType;
    private Long targetDatasourceId;
    private String outcome;
    private Long resultRuleId;
    private Integer resultVersion;
    private Integer errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
