package com.dataocean.module.metadata.entity.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SyncTriggerRequest {

    @NotNull(message = "数据源ID不能为空")
    private Long datasourceId;

    private Boolean includeStatistics = false;
}
