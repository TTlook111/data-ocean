package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 模型的实际提供者回执，不存提示词。
 */
@Data
@TableName("gov_model_call")
public class GovernanceModelCallPO {

    /**
     * 调用诊断编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 原任务 UUID。
     */
    private String requestId;

    /**
     * 运行的节点用途。
     */
    private String nodeCode;

    /**
     * 提供方报告的实际模型。
     */
    private String modelName;

    /**
     * 提供方返回的调用编号。
     */
    private String providerCallId;

    /**
     * 实际调用状态。
     */
    private String state;

    /**
     * 可披露的具体失败原因。
     */
    private String failureCode;

    /**
     * 已知耗时，单位毫秒。
     */
    private Integer elapsedMs;

    /**
     * 提供方报告的输入用量，未知为空。
     */
    private Integer inputTokens;

    /**
     * 提供方报告的输出用量，未知为空。
     */
    private Integer outputTokens;

    /**
     * 可披露的上下文裁剪原因编码列表，以逗号分隔。
     */
    private String inputLimits;

    /**
     * 调用记录实际落库时间。
     */
    private LocalDateTime recordedAt;
}
