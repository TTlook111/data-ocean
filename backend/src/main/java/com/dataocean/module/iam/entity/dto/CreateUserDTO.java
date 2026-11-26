package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.NotBlank;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 新增人员输入，复用非秘密资料，初始凭据只用于本次提交。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public final class CreateUserDTO extends NewUserProfileDTO {

    /**
     * 初始密码，不进入核对结果、日志或命令返回。
     */
    @NotBlank
    @CodePointLength(min = 8, max = 32)
    @ToString.Exclude
    private String initialPassword;

    /**
     * 绑定本次完整资料提案的签名核对凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
