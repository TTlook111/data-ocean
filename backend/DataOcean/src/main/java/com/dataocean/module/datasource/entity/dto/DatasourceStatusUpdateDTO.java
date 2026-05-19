package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DatasourceStatusUpdateDTO {

    @NotNull(message = "状态不能为空")
    private Integer status;
}
