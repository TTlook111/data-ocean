package com.dataocean.module.iam.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门与成员的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentDetailVO {

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
     * 当前部门的直接上级安全引用。
     */
    private DepartmentRefVO parent;

    /**
     * 部门负责人当前的安全人员引用。
     */
    private UserRefVO manager;

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
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 当前部门直属成员数量，包含直属兼任。
     */
    private int directMemberCount;

    /**
     * 仅后代部门范围内按人员去重的成员数。
     */
    private int descendantMemberCount;

    /**
     * 本部门及后代范围按人员去重的当前成员数。
     */
    private int totalMemberCount;

    /**
     * 组织恢复后保留访问配置是否仍须复核。
     */
    private boolean accessReviewRequired;

    /**
     * 该记录最近实际变化的 UTC 时刻。
     */
    private Instant updatedAt;
}
