package com.dataocean.module.datasource.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceConnectionTestResult {

    private Boolean success;
    private Long responseTimeMs;
    private String serverVersion;
    private String message;
}
