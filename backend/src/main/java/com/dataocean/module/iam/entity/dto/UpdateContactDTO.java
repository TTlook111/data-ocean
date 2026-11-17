package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员资料的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UpdateContactDTO {

    /**
     * 提交者核对时的聚合版本，服务端以当前版本检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 本人联系手机号，不作为独立登录或认证方式。
     */
    @CodePointLength(max = 32)
    private String phone;

    /**
     * 人员本人可维护的联系邮箱，不作为登录身份。
     */
    @Email
    @CodePointLength(max = 254)
    private String email;
}
