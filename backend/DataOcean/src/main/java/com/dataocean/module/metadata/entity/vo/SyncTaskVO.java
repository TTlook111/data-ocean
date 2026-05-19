package com.dataocean.module.metadata.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SyncTaskVO {

    private Long id;
    private String datasourceName;
    private String triggerType;
    private String status;
    private Integer progressTotal;
    private Integer progressCurrent;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
}
