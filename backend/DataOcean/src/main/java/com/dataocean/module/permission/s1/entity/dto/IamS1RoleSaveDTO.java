package com.dataocean.module.permission.s1.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** IAM-SIMPLE-1 普通角色保存请求；不暴露受保护角色字段。 */
@Data
public class IamS1RoleSaveDTO {
    @NotBlank
    private String roleCode;
    @NotBlank
    private String roleName;
    private String description;
    private Integer status;
    private List<String> functionCodes;
    private String reason;
}
