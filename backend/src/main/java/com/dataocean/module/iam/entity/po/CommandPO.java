package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 原主体的一次受控写命令，按完整内容 HMAC 防重并保留提交事实。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public final class CommandPO {

    /**
     * 原命令的可信主体类别。
     */
    private String principalKind;

    /**
     * 原命令可信主体的稳定标识。
     */
    private String principalId;

    /**
     * 实际操作人员标识，系统与初始化主体允许为空。
     */
    private Long actorUserId;

    /**
     * 原业务动作代码，与当前用户和命令编号共同定位命令。
     */
    private String operationCode;

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;

    /**
     * 带密钥的命令内容指纹，只用于同键内容一致性核对。
     */
    @ToString.Exclude
    private byte[] payloadHmac;

    /**
     * 生成受控指纹时使用的密钥修订标识，不含密钥本体。
     */
    private String hmacKeyVersion;

    /**
     * 原命令的接收、提交或拒绝状态。
     */
    private String state;

    /**
     * 命令最终结果的业务码。
     */
    private String resultCode;

    /**
     * 已提交命令的安全目标引用，不保存敏感响应正文。
     */
    private String resultRef;

    /**
     * 该记录创建的 UTC 时刻。
     */
    private LocalDateTime createdAt;

    /**
     * 消费或业务处理实际完成的 UTC 时刻。
     */
    private LocalDateTime completedAt;
}
