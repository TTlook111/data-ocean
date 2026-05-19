package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StatusUpdateDTO {
    @NotNull(message = "状态不能为空")
    private Integer status;
}
