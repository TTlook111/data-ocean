package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 可安全用于核对替换的变更参数，秘密由正式提交携带。
 */
@Data
public class CredentialChangeProposalDTO {

    /**
     * 更换后使用的只读账号。
     */
    @NotBlank
    @Size(max = 160)
    private String account;

    /**
     * 以新账号与密码完成的本次测试。
     */
    @NotBlank
    private String checkId;

    /**
     * 表示本次提案将提交新凭据。
     */
    private boolean credentialProvided;
}
