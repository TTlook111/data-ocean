package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class RoleSaveDTO {

    @NotBlank(message = "角色编码不能为空")
    @Size(max = 50, message = "角色编码不能超过 50 字符")
    private String roleCode;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称不能超过 50 字符")
    private String roleName;

    @Size(max = 200, message = "角色描述不能超过 200 字符")
    private String description;

    private Integer status = 1;

    private List<Long> permissionIds;
}
