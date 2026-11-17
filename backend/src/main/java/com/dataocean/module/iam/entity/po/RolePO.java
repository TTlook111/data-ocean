package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前角色配置，使用能力和可授予上限分别保存。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RolePO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private Long id;

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private String code;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 角色名称经 trim、NFKC 和统一小写后形成的唯一键。
     */
    private String nameKey;

    /**
     * 当前对象面向业务人员的职责说明。
     */
    private String description;

    /**
     * 角色是否来自初始化模板，不代表最高管理权限。
     */
    private boolean isBuiltin;

    /**
     * 正常、停用或归档的持久生命周期状态。
     */
    private String lifecycle;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 停用实际发生的 UTC 时刻。
     */
    private LocalDateTime disabledAt;

    /**
     * 归档实际发生的 UTC 时刻。
     */
    private LocalDateTime archivedAt;

    /**
     * 最近一次生命周期变化的业务原因。
     */
    private String statusReason;

    /**
     * 创建该记录的操作人员标识。
     */
    private Long createdBy;

    /**
     * 该记录创建的 UTC 时刻。
     */
    private LocalDateTime createdAt;

    /**
     * 该记录最近实际变化的 UTC 时刻。
     */
    private LocalDateTime updatedAt;
}
