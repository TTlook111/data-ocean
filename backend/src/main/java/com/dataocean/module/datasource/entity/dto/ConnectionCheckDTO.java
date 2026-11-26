package com.dataocean.module.datasource.entity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * 以实际账号、范围和运行约束发起一次分项连接测试。
 */
@Data
public class ConnectionCheckDTO {

    /**
     * 待探测的非秘密配置。
     */
    @Valid
    @NotNull
    private SourceConfigurationDTO configuration;

    /**
     * 约束测试的运行参数。
     */
    @Valid
    @NotNull
    private RuntimePolicyDTO runtimePolicy = new RuntimePolicyDTO();

    /**
     * 本次探测的密码，缺省时使用原受控凭据。
     */
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 1024)
    private String password;

    /**
     * 测试已保存源时的数据源配置版本。
     */
    @Min(1)
    private Integer expectedVersion;
}
