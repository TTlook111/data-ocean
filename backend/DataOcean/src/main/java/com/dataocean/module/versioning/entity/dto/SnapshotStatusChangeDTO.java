package com.dataocean.module.versioning.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SnapshotStatusChangeDTO {

    @NotNull(message = "目标状态不能为空")
    private String targetStatus;

    private String reason;
}
