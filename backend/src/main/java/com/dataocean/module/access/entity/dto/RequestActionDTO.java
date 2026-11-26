package com.dataocean.module.access.entity.dto;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;
import lombok.ToString;

/**
 * 申请办理动作的明确提案，具体字段由动作校验。
 */
@Data
public class RequestActionDTO {

    /**
     * 办理前读取的申请版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 当前办理动作编码。
     */
    @NotBlank
    private String action;

    /**
     * 单个审批子项编号。
     */
    private String itemId;

    /**
     * 核定后的数据源编号。
     */
    private String sourceId;

    /**
     * 分拆时提交的完整数据源范围。
     */
    @Size(max = 30)
    private List<@NotBlank String> sourceIds;

    /**
     * 分拆后的整体办理方式。
     */
    private Boolean joint;

    /**
     * 批准时选择的共享规则编号。
     */
    @Size(max = 30)
    private List<@NotBlank String> ruleIds;

    /**
     * 办理原因或补充要求。
     */
    @CodePointLength(max = 1000)
    private String reason;

    /**
     * 绑定操作内容、当前权限与源／规则的核对签名。
     */
    @ToString.Exclude
    private String reviewToken;
}
