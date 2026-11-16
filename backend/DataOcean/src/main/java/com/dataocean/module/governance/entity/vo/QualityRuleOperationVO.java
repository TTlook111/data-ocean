package com.dataocean.module.governance.entity.vo;

import lombok.Data;

/** 原操作的持久回执；不复制规则正文，当前内容须按规则 ID 重新授权读取。 */
@Data
public class QualityRuleOperationVO {
    private String operationId;
    private String action;
    private String outcome;
    private Long ruleId;
    private Integer configVersion;
    private Integer errorCode;
    private String errorMessage;
}
