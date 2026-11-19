package com.dataocean.module.access.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;
import lombok.ToString;

/**
 * 替换部门初始配置的完整提案。
 */
@Data
public class DepartmentAccessDTO {

    /**
     * 由服务端核对影响后签发的当前提案凭据。
     */
    @ToString.Exclude
    private String reviewToken;

    /**
     * 核对时的部门版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 完整配置，空数组结束初始访问。
     */
    @NotNull
    @Size(max = 50)
    private List<@Valid SourceRuleSelectionDTO> sources;

    /**
     * 配置变更原因。
     */
    @CodePointLength(max = 500)
    private String reason;
}
