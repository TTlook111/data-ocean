package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DatasourceAccessGrantDTO {

    @NotEmpty(message = "授权用户不能为空")
    @Size(max = 100, message = "单次授权用户不能超过100个")
    private List<@NotNull(message = "授权用户ID不能为空") Long> userIds;
    private LocalDateTime expiresAt;
}
