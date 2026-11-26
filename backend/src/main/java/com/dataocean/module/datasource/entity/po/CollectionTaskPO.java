package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 采集任务的持久化事实，凭据和源端会话定位独立存储。
 */
@Data
@TableName("ds_collection_task")
public class CollectionTaskPO {

    /**
     * 任务 UUID。
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 原接收命令。
     */
    private String startCommandId;

    /**
     * 列表展示的任务编号。
     */
    private String taskNo;

    /**
     * 归属源。
     */
    private long sourceId;

    /**
     * 创建时固定的配置。
     */
    private long configRevisionId;

    /**
     * 手动操作者，定时任务为空。
     */
    private Long actorId;

    /**
     * 固定的可披露操作者 JSON。
     */
    private String actorRef;

    /**
     * 重试来源。
     */
    private String parentTaskId;

    /**
     * 发起方式。
     */
    private String triggerKind;

    /**
     * 任务五态之一。
     */
    private String state;

    /**
     * 源侧执行是否确认结束。
     */
    private String executionState;

    /**
     * 运行阶段。
     */
    private String phase;

    /**
     * 任务可见状态版本。
     */
    private int version;

    /**
     * 工作代次。
     */
    private int runVersion;

    /**
     * 当前执行器身份。
     */
    private String leaseOwner;

    /**
     * 执行事件流标识。
     */
    private String streamEpoch;

    /**
     * 接收时间，UTC。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /**
     * 真实开始时间，UTC。
     */
    private LocalDateTime startedAt;

    /**
     * 真实结束时间，UTC。
     */
    private LocalDateTime endedAt;

    /**
     * 总执行时限，UTC。
     */
    private LocalDateTime executionDeadlineAt;

    /**
     * 租约截至时间，UTC。
     */
    private LocalDateTime leaseUntil;

    /**
     * 排队等待截至时间，UTC。
     */
    private LocalDateTime queueDeadlineAt;

    /**
     * 人工或凭据替换请求取消的时间。
     */
    private LocalDateTime cancelRequestedAt;

    /**
     * 依据源侧证据确认停止的时间。
     */
    private LocalDateTime stopConfirmedAt;

    /**
     * 已接受的取消原因。
     */
    private String cancelReason;

    /**
     * 失败分类。
     */
    private String failureCode;

    /**
     * 失败可披露原因。
     */
    private String failureMessage;

    /**
     * 完成枚举后才确定的总数。
     */
    private Long totalCount;

    /**
     * 本次采集是否已完成源端对象枚举。
     */
    private boolean enumerationComplete;

    /**
     * 本次已发现的可见对象。
     */
    private long discoveredCount;

    /**
     * 完整读取对象数。
     */
    private long completeCount;

    /**
     * 部分读取对象数。
     */
    private long partialCount;

    /**
     * 未取得核心结构的对象数。
     */
    private long failedCount;
}
