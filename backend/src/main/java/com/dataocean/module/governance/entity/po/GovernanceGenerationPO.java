package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.ToString;

/**
 * 一次持久接收的表说明生成任务。
 */
@Data
@TableName("gov_generation_task")
public class GovernanceGenerationPO {

    /**
     * 关联或指标发现类型，表说明和字段解释为空。
     */
    private String discoveryKind;

    /**
     * 生成任务的 UUID。
     */
    @TableId(type = IdType.INPUT)
    private String requestId;

    /**
     * 接收的原命令 UUID。
     */
    private String commandId;

    /**
     * 唯一数据源。
     */
    private Long sourceId;

    /**
     * 表说明目标对象。
     */
    private Long objectId;

    /**
     * 任务固定的成功目录快照。
     */
    private Long snapshotId;

    /**
     * 接收任务时固定的不可变模型配置修订。
     */
    private Long modelRevisionId;

    /**
     * 受控发出模型访问许可的时间，记录后不重复发出。
     */
    private LocalDateTime modelStartedAt;

    /**
     * 原发起人。
     */
    private Long requestedBy;

    /**
     * 接收任务时的认证版本。
     */
    private Long authenticationVersion;

    /**
     * 待派发、排队、运行及真实终态。
     */
    private String state;

    /**
     * 固定工作者执行代次。
     */
    private Integer runVersion;

    /**
     * 业务任务快照版本。
     */
    private Integer version;

    /**
     * 本次 Java 回读租约。
     */
    @ToString.Exclude
    private String leaseToken;

    /**
     * 允许后台回读此任务的时间。
     */
    private LocalDateTime dueAt;

    /**
     * 接收时刻，UTC。
     */
    private LocalDateTime createdAt;

    /**
     * 实际观察到运行时刻，UTC。
     */
    private LocalDateTime startedAt;

    /**
     * 实际终结时刻，UTC。
     */
    private LocalDateTime endedAt;

    /**
     * 安全稳定的失败原因。
     */
    private String failureCode;
}
