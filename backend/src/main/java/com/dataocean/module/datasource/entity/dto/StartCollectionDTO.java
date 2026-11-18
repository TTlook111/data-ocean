package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.ToString;

/**
 * 提交核对后的采集动作；无重试来源时为普通手动采集。
 */
@Data
public class StartCollectionDTO {

    /**
     * 发起时使用的数据源配置版本。
     */
    @Min(1)
    @NotNull
    private Integer expectedVersion;

    /**
     * 本次关联的已结束任务编号，可空。
     */
    private String retryOfTaskId;

    /**
     * 已核对采集范围和账号后的受控签名。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
