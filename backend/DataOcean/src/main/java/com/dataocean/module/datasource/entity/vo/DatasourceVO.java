package com.dataocean.module.datasource.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DatasourceVO {

    private Long id;
    private String name;
    private String description;
    private String dbType;
    private String host;
    private Integer port;
    private String databaseName;
    private String charset;
    private Integer status;
    private String healthStatus;
    private String username;
    private String creatorName;
    private Boolean lastCheckSuccess;
    private LocalDateTime lastCheckTime;
    private LocalDateTime createdAt;
}
