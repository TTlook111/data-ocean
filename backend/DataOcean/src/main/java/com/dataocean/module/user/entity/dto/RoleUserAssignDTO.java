package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 角色成员分配请求。
 */
@Data
public class RoleUserAssignDTO {

    @NotNull(message = "用户ID不能为空")
    private Long userId;
}
