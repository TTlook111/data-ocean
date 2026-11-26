package com.dataocean.module.iam.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CurrentSessionVO {

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    private String userId;

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    private String username;

    /**
     * 人员当前显示姓名，不作为登录或授权依据。
     */
    private String displayName;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

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

    /**
     * 完整最终功能使用集合，与可授予集合分别核对。
     */
    private List<String> permissionCodes;

    /**
     * 平台当前功能与组织配置的权威安全版本。
     */
    private String securityVersion;
}
