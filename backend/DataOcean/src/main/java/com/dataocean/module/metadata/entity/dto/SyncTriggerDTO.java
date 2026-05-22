package com.dataocean.module.metadata.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 触发元数据同步的请求参数 DTO。
 * <p>
 * 前端手动触发同步时传入，指定目标数据源和是否采集统计信息。
 * </p>
 */
@Data
public class SyncTriggerDTO {

    /** 目标数据源ID（必填） */
    @NotNull(message = "数据源ID不能为空")
    private Long datasourceId;

    /** 是否同时采集统计信息（空值率、去重计数等），默认 false */
    private Boolean includeStatistics = false;
}
