package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("datasource")
public class Datasource {

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;

    public static final String DB_TYPE_MYSQL = "MYSQL";
    public static final String HEALTH_UNKNOWN = "UNKNOWN";
    public static final String HEALTH_HEALTHY = "HEALTHY";
    public static final String HEALTH_UNHEALTHY = "UNHEALTHY";

    @TableId(type = IdType.AUTO)
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
    private Long creatorId;
    private Long deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
