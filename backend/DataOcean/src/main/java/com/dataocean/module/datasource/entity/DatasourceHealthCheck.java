package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("datasource_health_check")
public class DatasourceHealthCheck {

    public static final String TYPE_MANUAL = "MANUAL";
    public static final String TYPE_SCHEDULED = "SCHEDULED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private String checkType;
    private Integer success;
    private Integer responseTimeMs;
    private String serverVersion;
    private String errorMessage;
    private LocalDateTime checkedAt;
}
