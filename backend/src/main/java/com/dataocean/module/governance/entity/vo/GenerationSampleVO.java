package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 实际辅助读取的摘要，原始业务行仅在工作者内短期使用。
 */
@Data
@AllArgsConstructor
public class GenerationSampleVO {

    /**
     * 当前任务的实际读取轮次。
     */
    private int attempt;

    /**
     * 本轮处理的业务缺口类型。
     */
    private String reason;

    /**
     * 成功、失败或因当前资格变化而未交付。
     */
    private String status;

    /**
     * 业务源实际返回的记录条数。
     */
    private int returnedRows;

    /**
     * 有诊断权限时可回看的固定只读语句，无样本值。
     */
    private String statement;
}
