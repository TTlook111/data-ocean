package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;
import lombok.ToString;

/**
 * 撤下、恢复或删除资料的核对输入。
 */
@Data
public class BusinessNoteStateDTO {

    /**
     * 读到的资料聚合版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 撤下、恢复或删除动作。
     */
    @NotBlank
    private String action;

    /**
     * 撤下时需提供的实际原因。
     */
    @CodePointLength(max = 500)
    private String reason;

    /**
     * 服务端绑定内容与权限的核对令牌。
     */
    @ToString.Exclude
    private String reviewToken;
}
