package com.dataocean.module.permission.s1.entity.vo;

import lombok.Getter;

/** 启动式 IAM-SIMPLE-1 bootstrap 的非敏感结果。 */
@Getter
public class IamS1BootstrapResult {
    private final String state;
    private final Long targetUserId;
    private final Long userRoleId;
    private final boolean idempotent;

    public IamS1BootstrapResult(String state, Long targetUserId, Long userRoleId, boolean idempotent) {
        this.state = state;
        this.targetUserId = targetUserId;
        this.userRoleId = userRoleId;
        this.idempotent = idempotent;
    }
}
