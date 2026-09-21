package com.dataocean.module.permission.s1.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 为用户分配 S1 角色。数据授权不在这里配置。 */
@Data
public class IamS1UserRoleAssignDTO {

    @NotNull(message = "请选择要分配的角色")
    private Long roleId;

    private String reason;
}
