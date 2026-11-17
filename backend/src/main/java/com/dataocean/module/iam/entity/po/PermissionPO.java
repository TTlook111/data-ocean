package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PermissionPO {
    private String code;
    private String pagePermissionCode;
    private String moduleCode;
    private String name;
    private String purpose;
    private String permissionKind;
    private boolean isProtected;
    private boolean enabled;
    private int sortOrder;
}
