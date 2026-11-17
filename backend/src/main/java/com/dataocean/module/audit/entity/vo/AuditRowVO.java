package com.dataocean.module.audit.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史审计的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditRowVO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 操作发生时的可信主体快照。
     */
    private AuditActorVO actor;

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
     * 事件或审计真实发生的 UTC 毫秒时刻。
     */
    private Instant occurredAt;
}
