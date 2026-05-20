package com.dataocean.module.system.entity.vo;

import lombok.Data;

@Data
public class SyncScheduleVO {

    private String cron;
    private Boolean enabled;
    private Boolean running;
}
