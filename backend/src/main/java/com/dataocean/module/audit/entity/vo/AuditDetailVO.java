package com.dataocean.module.audit.entity.vo;

import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史审计的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditDetailVO {

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

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    private String reason;

    /**
     * 本次实际变化的必要字段差异。
     */
    private List<ChangeVO> changes;

    /**
     * 平台当前功能与组织配置的权威安全版本。
     */
    private String securityVersion;

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;

    /**
     * 与该记录关联的业务请求标识。
     */
    private String businessRequestId;

    /**
     * 该历史事实所关联的不可变角色版本引用。
     */
    private List<RoleVersionRefVO> roleSnapshots;
}
