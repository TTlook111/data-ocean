package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 身份凭据的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public final class ChangePasswordDTO {

    /**
     * 提交者核对时的聚合版本，服务端以当前版本检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 本人改密时提交的当前密码，仅用于安全散列校验。
     */
    @NotBlank
    @CodePointLength(max = 32)
    @ToString.Exclude
    private String currentPassword;

    /**
     * 本次拟设置的新密码，不做 trim 或字符截断。
     */
    @NotBlank
    @CodePointLength(min = 8, max = 32)
    @ToString.Exclude
    private String newPassword;

    /**
     * 本次新密码的二次确认，仅用于校验，不持久化或输出。
     */
    @NotBlank
    @ToString.Exclude
    private String confirmPassword;
}
