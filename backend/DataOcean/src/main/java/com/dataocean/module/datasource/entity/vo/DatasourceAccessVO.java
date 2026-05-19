package com.dataocean.module.datasource.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DatasourceAccessVO {

    private Long id;
    private Long datasourceId;
    private Long userId;
    private String username;
    private String realName;
    private Long grantedBy;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;
}
