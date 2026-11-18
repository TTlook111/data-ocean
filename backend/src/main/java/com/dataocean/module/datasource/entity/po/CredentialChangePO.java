package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 映射 ds_connection_change 的持久化字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("ds_connection_change")
public class CredentialChangePO {

    /**
     * 替换编号。
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 命令编号。
     */
    private String commandId;

    /**
     * 来源编号。
     */
    private long sourceId;

    /**
     * 原操作者。
     */
    private long actorId;

    /**
     * 原连接修订。
     */
    private long baseConnectionRevisionId;

    /**
     * 测试编号。
     */
    private long checkId;

    /**
     * 候选凭据编号。
     */
    private long candidateCredentialRevisionId;

    /**
     * 预期配置版本。
     */
    private int expectedSourceVersion;

    /**
     * 候选账号。
     */
    private String candidateAccount;

    /**
     * 原等待停止任务。
     */
    private String blockingTaskId;

    /**
     * 替换阶段。
     */
    private String state;

    /**
     * 接收时间。
     */
    private LocalDateTime acceptedAt;

    /**
     * 确认停止时间。
     */
    private LocalDateTime stopConfirmedAt;

    /**
     * 结束时间。
     */
    private LocalDateTime completedAt;

    /**
     * 失败原因。
     */
    private String failureCode;

    /**
     * 可披露信息。
     */
    private String failureMessage;
}
