package com.dataocean.module.datasource.entity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * 提交已核对的数据源新建动作，凭据只在服务端受控处理。
 */
@Data
public class SourceCreateDTO {

    /**
     * 与核对凭据关联的新增提案。
     */
    @Valid
    @NotNull
    private SourceCreateProposalDTO proposal;

    /**
     * 可选的源库凭据。
     */
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 1024)
    private String password;

    /**
     * 本次核对签发的提交凭据。
     */
    @NotNull
    @ToString.Exclude
    private String reviewToken;
}
