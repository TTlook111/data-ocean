package com.dataocean.module.audit.entity.vo;

import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 审计详情复用列表中的历史主体与目标快照，仅增加获准查看的业务明细。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ToString(callSuper = true)
public final class AuditDetailVO extends AuditRowVO {

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
