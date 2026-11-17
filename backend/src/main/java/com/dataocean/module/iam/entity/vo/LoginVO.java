package com.dataocean.module.iam.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 身份凭据的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class LoginVO {

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    private String userId;

    /**
     * 当前登录为完整身份或仅允许必要改密的受限身份。
     */
    private String authenticationState;

    /**
     * 是否只能先完成首次或重置后的必要改密。
     */
    private boolean mustChangePassword;

    /**
     * 本次登录按首次认证时刻计算的绝对到期时间。
     */
    private Instant absoluteExpiresAt;
}
