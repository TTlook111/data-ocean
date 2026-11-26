package com.dataocean.module.iam.entity.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统功能的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PermissionVO {

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private String code;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 本次选项查询或系统功能的明确业务用途。
     */
    private String purpose;

    /**
     * 该功能是否为受保护管理能力。
     */
    @JsonProperty("protected")
    private boolean protectedAbility;

    /**
     * 当前系统目录项是否启用。
     */
    private boolean enabled;

    /**
     * 该动作显式所属的页面权，页面项本身为空。
     */
    private String pagePermissionCode;
}
