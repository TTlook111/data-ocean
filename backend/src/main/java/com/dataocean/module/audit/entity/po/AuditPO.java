package com.dataocean.module.audit.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 真实操作及必要历史身份快照，普通接口不提供修改或删除。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditPO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private Long id;

    /**
     * 主体类别，区分人员、系统和受控初始化。
     */
    private String actorKind;

    /**
     * 实际操作人员标识，系统与初始化主体允许为空。
     */
    private Long actorUserId;

    /**
     * 操作发生时的姓名和登录名快照，后续改名不重写历史。
     */
    private String actorSnapshot;

    /**
     * 实际操作代码，用于权限核验和审计关联。
     */
    private String actionCode;

    /**
     * 当前目标所属的明确业务类型。
     */
    private String targetType;

    /**
     * 当前业务目标的稳定标识。
     */
    private String targetId;

    /**
     * 操作发生时的业务目标名称快照。
     */
    private String targetName;

    /**
     * 操作实际结果，明确区分成功、拒绝和失败。
     */
    private String outcome;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    private String reason;

    /**
     * 操作前的最小事实和角色版本引用。
     */
    private String beforeSummary;

    /**
     * 操作后的最小差异与角色版本快照，不含秘密请求正文。
     */
    private String afterSummary;

    /**
     * 平台当前功能与组织配置的权威安全版本。
     */
    private Long securityVersion;

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;

    /**
     * 与该记录关联的业务请求标识。
     */
    private String businessRequestId;

    /**
     * 当前请求的受控诊断关联编号。
     */
    private String traceId;

    /**
     * 事件或审计真实发生的 UTC 毫秒时刻。
     */
    private LocalDateTime occurredAt;
}
