package com.dataocean.module.system.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 自动同步计划更新请求参数。
 */
@Data
public class SyncScheduleDTO {

    private String cron;

    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;
}
