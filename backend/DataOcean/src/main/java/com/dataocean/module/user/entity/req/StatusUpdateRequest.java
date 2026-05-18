package com.dataocean.module.user.entity.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StatusUpdateRequest {
    @NotNull(message = "状态不能为空")
    private Integer status;
}
