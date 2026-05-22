package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 数据源状态更新请求 DTO
 * <p>
 * 用于接收管理员修改数据源启用/禁用状态的请求参数。
 * </p>
 *
 * @author dataocean
 */
@Data
public class DatasourceStatusUpdateDTO {

    /** 目标状态：0-禁用，1-启用 */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
