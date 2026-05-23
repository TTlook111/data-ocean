package com.dataocean.module.versioning.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 快照状态变更请求参数。
 */
@Data
public class SnapshotStatusChangeDTO {

    @NotNull(message = "目标状态不能为空")
    private String targetStatus;

    private String reason;
}
