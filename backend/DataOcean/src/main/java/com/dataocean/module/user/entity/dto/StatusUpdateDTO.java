package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户状态更新请求参数。
 * <p>
 * 管理员启用/禁用用户账号时提交此对象。
 * </p>
 *
 * @author dataocean
 */
@Data
public class StatusUpdateDTO {

    /** 目标状态：1-正常，2-禁用 */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
