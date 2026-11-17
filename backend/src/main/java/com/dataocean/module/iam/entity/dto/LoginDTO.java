package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;

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
public final class LoginDTO {

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    @NotBlank
    @CodePointLength(max = 80)
    private String username;

    /**
     * 本次登录的明文凭据，仅用于验密，不记录或回显。
     */
    @NotBlank
    @CodePointLength(max = 32)
    @ToString.Exclude
    private String password;
}
