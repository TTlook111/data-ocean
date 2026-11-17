package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员身份与生命周期事实，凭据由独立受控记录保存。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserPO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private Long id;

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    private String username;

    /**
     * 人员当前显示姓名，不作为登录或授权依据。
     */
    private String displayName;

    /**
     * 本人联系手机号，不作为独立登录或认证方式。
     */
    private String phone;

    /**
     * 人员本人可维护的联系邮箱，不作为登录身份。
     */
    private String email;

    /**
     * 管理人员维护的备注，与本人联系方式区分。
     */
    private String managementNote;

    /**
     * 正常、停用或归档的持久生命周期状态。
     */
    private String lifecycle;

    /**
     * 是否只能先完成首次或重置后的必要改密。
     */
    private boolean mustChangePassword;

    /**
     * 账号的权威认证修订，旧会话版本不匹配时拒绝访问。
     */
    private long authenticationVersion;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 首次认证成功的 UTC 时刻，与资料更新时间分别保存。
     */
    private LocalDateTime firstLoginAt;

    /**
     * 停用实际发生的 UTC 时刻。
     */
    private LocalDateTime disabledAt;

    /**
     * 归档实际发生的 UTC 时刻。
     */
    private LocalDateTime archivedAt;

    /**
     * 人员归档时的主兼任与角色展示快照，不作为当前权限。
     */
    private String archivedAffiliation;

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
