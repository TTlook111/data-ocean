package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PermissionSaveDTO {

    @NotBlank(message = "权限编码不能为空")
    @Size(max = 100, message = "权限编码不能超过 100 字符")
    private String permissionCode;

    @NotBlank(message = "权限名称不能为空")
    @Size(max = 100, message = "权限名称不能超过 100 字符")
    private String permissionName;

    @NotBlank(message = "所属模块不能为空")
    @Size(max = 50, message = "所属模块不能超过 50 字符")
    private String module;

    @Size(max = 200, message = "权限描述不能超过 200 字符")
    private String description;
}
