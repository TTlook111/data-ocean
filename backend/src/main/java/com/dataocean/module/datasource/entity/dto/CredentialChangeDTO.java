package com.dataocean.module.datasource.entity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * 提交实际的新凭据，使用已核对的非秘密提案。
 */
@Data
public class CredentialChangeDTO {

    /**
     * 请求替换时的配置版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 新账号与连接测试编号。
     */
    @NotNull
    @Valid
    private CredentialChangeProposalDTO proposal;

    /**
     * 新凭据，只进入受控秘密路径。
     */
    @NotNull
    @Size(min = 1, max = 1024)
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * 本次替换的核对签名。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
