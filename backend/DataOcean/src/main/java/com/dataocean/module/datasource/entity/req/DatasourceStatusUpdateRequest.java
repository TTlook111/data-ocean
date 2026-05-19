package com.dataocean.module.datasource.entity.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DatasourceStatusUpdateRequest {

    @NotNull(message = "状态不能为空")
    private Integer status;
}
