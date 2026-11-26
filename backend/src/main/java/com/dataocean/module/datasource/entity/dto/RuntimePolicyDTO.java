package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/**
 * 采集与定时设置，使用产品已确定的默认值。
 */
@Data
public class RuntimePolicyDTO {

    /**
     * 采集允许的连接数上限。
     */
    @Min(1)
    @Max(20)
    private int maxConnections = 4;

    /**
     * 允许并行读取的对象批次数。
     */
    @Min(1)
    @Max(8)
    private int collectionConcurrency = 2;

    /**
     * 固定采集预算内允许的最大重试次数。
     */
    @Min(0)
    @Max(5)
    private int retryLimit = 2;

    /**
     * 一次采集任务的执行时限，单位为秒。
     */
    @Min(30)
    @Max(3600)
    private int taskTimeoutSeconds = 600;

    /**
     * 单条元数据查询时限，单位为秒。
     */
    @Min(1)
    @Max(300)
    private int queryTimeoutSeconds = 30;

    /**
     * 单次受控业务查询的结果行上限。
     */
    @Min(1)
    @Max(10000)
    private int rowLimit = 1000;

    /**
     * 定时采集的周期小时数。
     */
    @Min(1)
    @Max(720)
    private int syncHours = 24;

    /**
     * 采集窗口开始时刻，使用部署时区。
     */
    @NotNull
    @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private String windowStart = "02:00";

    /**
     * 采集窗口结束时刻，使用部署时区。
     */
    @NotNull
    @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private String windowEnd = "05:00";
}
