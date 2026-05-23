package com.dataocean.module.system.entity.vo;

import lombok.Data;

/**
 * 自动同步计划视图对象。
 */
@Data
public class SyncScheduleVO {

    private String cron;
    private Boolean enabled;
    private Boolean running;
}
