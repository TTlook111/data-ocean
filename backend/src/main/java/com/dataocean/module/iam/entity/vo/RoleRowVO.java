package com.dataocean.module.iam.entity.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色与授予的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleRowVO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private String code;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 当前对象面向业务人员的职责说明。
     */
    private String description;

    /**
     * 正常、停用或归档的持久生命周期状态。
     */
    private String lifecycle;

    /**
     * 角色是否来自初始化模板，不代表最高管理权限。
     */
    @JsonProperty("isBuiltin")
    private boolean builtin;

    /**
     * 完整实际能力中是否包含受保护使用或授予项。
     */
    private boolean containsProtected;

    /**
     * 当前仍保留该角色分配的人员数量，包含停用账号。
     */
    private int memberCount;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 该记录最近实际变化的 UTC 时刻。
     */
    private Instant updatedAt;
}
