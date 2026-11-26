package com.dataocean.module.datasource.entity.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 数据源的非秘密连接配置，可用于草稿、测试和核对。
 */
@Data
public class SourceConfigurationDTO {

    /**
     * 后台数据源展示名称。
     */
    @Size(max = 80)
    private String name;

    /**
     * 配置用途说明。
     */
    @Size(max = 1000)
    private String purpose;

    /**
     * 技术联系人的人员编号。
     */
    private String technicalContactId;

    /**
     * 业务联系人的人员编号。
     */
    private String businessContactId;

    /**
     * 数据库类型编码。
     */
    @Pattern(regexp = "MYSQL|POSTGRESQL")
    private String databaseType;

    /**
     * 目标主机名或地址。
     */
    @Size(max = 253)
    private String host;

    /**
     * 目标 TCP 端口。
     */
    @Min(1)
    @Max(65535)
    private Integer port;

    /**
     * 目标数据库名。
     */
    @Size(max = 160)
    private String databaseName;

    /**
     * 专用只读账号名。
     */
    @Size(max = 160)
    private String account;

    /**
     * 数据库实际可见的 Schema 名称。
     */
    @Size(max = 256)
    private List<String> schemas = List.of();
}
