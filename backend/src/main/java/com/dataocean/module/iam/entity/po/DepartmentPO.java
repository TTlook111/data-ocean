package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门组织事实，组织状态与成员账号状态分别维护。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentPO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private Long id;

    /**
     * 当前部门的直接上级标识，空值表示根部门。
     */
    private Long parentId;

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private String code;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 以稳定人员标识绑定的部门负责人。
     */
    private Long managerUserId;

    /**
     * 当前部门或配置对象的可空业务说明。
     */
    private String note;

    /**
     * 部门同级显示顺序，按整数范围校验。
     */
    private int sortOrder;

    /**
     * 正常、停用或归档的持久生命周期状态。
     */
    private String lifecycle;

    /**
     * 组织恢复后保留访问配置是否仍须复核。
     */
    private boolean accessReviewRequired;

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
