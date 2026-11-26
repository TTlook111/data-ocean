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
 * 修改现有源的配置，复用与新增一致的连接和运行参数。
 */
@Data
public class SourceUpdateDTO {

    /**
     * 核对时的源写入版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 拟提交的配置。
     */
    @NotNull
    @Valid
    private SourceConfigurationDTO configuration;

    /**
     * 拟提交的运行设置。
     */
    @NotNull
    @Valid
    private RuntimePolicyDTO runtimePolicy = new RuntimePolicyDTO();

    /**
     * 此次是否提供新密码。
     */
    private boolean credentialProvided;

    /**
     * 对应参数的分项测试编号。
     */
    private String checkId;

    /**
     * 拟保存的草稿凭据，仅用于实际提交。
     */
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 1024)
    private String password;

    /**
     * 确认此次修改的签名凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
