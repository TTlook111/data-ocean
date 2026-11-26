package com.dataocean.module.iam.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 人员详情复用当前或归档归属摘要，只补充管理资料及真实状态时间。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class UserDetailVO extends UserRowVO {

    /**
     * 持久生命周期，与待改密展示状态分别表达。
     */
    private String lifecycle;

    /**
     * 是否必须先完成首次或重置后的必要改密。
     */
    private boolean mustChangePassword;

    /**
     * 当前联系手机号。
     */
    private String phone;

    /**
     * 当前联系邮箱。
     */
    private String email;

    /**
     * 管理人员维护的备注。
     */
    private String managementNote;

    /**
     * 首次认证成功的 UTC 时刻。
     */
    private Instant firstLoginAt;

    /**
     * 停用实际发生的 UTC 时刻。
     */
    private Instant disabledAt;

    /**
     * 归档实际发生的 UTC 时刻。
     */
    private Instant archivedAt;
}
