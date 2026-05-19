package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DatasourceAccessGrantDTO {

    @NotEmpty(message = "授权用户不能为空")
    private List<Long> userIds;
    private LocalDateTime expiresAt;
}
