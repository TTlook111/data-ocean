package com.dataocean.module.iam.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthenticationInvalidatedDTO {

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    private String userId;

    /**
     * 账号的权威认证修订，旧会话版本不匹配时拒绝访问。
     */
    private long authenticationVersion;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    private String reason;
}
