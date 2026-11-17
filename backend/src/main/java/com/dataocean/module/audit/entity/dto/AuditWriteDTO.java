package com.dataocean.module.audit.entity.dto;

import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史审计的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditWriteDTO {

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
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    private String reason;

    /**
     * 本次实际变化的必要字段差异。
     */
    private List<ChangeVO> changes;

    /**
     * 提交前真实分配的角色及对应版本。
     */
    private List<RoleVersionRefVO> beforeRoles;

    /**
     * 成功提交后关联的角色及对应版本。
     */
    private List<RoleVersionRefVO> afterRoles;

    /**
     * 平台当前功能与组织配置的权威安全版本。
     */
    private long securityVersion;

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;
}
