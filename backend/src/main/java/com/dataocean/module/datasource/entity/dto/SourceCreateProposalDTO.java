package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/**
 * 核对新增数据源时的非秘密提案。
 */
@Data
public class SourceCreateProposalDTO {

    /**
     * 拟保存的非秘密配置。
     */
    @Valid
    @NotNull
    private SourceConfigurationDTO configuration;

    /**
     * 拟保存的运行和定时参数。
     */
    @Valid
    @NotNull
    private RuntimePolicyDTO runtimePolicy = new RuntimePolicyDTO();

    /**
     * 保存结果的生命周期。
     */
    @NotNull
    @Pattern(regexp = "DRAFT|ENABLED")
    private String targetLifecycle;

    /**
     * 本次提交是否会提供密码。
     */
    private boolean credentialProvided;

    /**
     * 核对使用的服务端分项测试编号。
     */
    private String checkId;
}
