package com.dataocean.module.iam.entity.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统维护的功能目录，页面归属和受保护标记不接受客户端定义。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PermissionPO {

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private String code;

    /**
     * 该动作显式所属的页面权，页面项本身为空。
     */
    private String pagePermissionCode;

    /**
     * 系统功能目录所属模块编码。
     */
    private String moduleCode;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 本次选项查询或系统功能的明确业务用途。
     */
    private String purpose;

    /**
     * 区分系统页面权与页面内动作权。
     */
    private String permissionKind;

    /**
     * 该系统目录项是否必须经过受保护流程配置。
     */
    private boolean isProtected;

    /**
     * 当前系统目录项是否启用。
     */
    private boolean enabled;

    /**
     * 部门同级显示顺序，按整数范围校验。
     */
    private int sortOrder;
}
