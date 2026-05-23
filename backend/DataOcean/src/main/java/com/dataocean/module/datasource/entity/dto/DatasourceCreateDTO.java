package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 数据源创建请求参数。
 * <p>
 * 描述新增 MySQL 数据源所需的连接信息和只读账号凭据。
 * </p>
 */
@Data
public class DatasourceCreateDTO {

    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 100, message = "数据源名称不能超过100位")
    private String name;

    @Size(max = 500, message = "描述不能超过500位")
    private String description;

    @NotBlank(message = "主机不能为空")
    @Size(max = 255, message = "主机不能超过255位")
    @Pattern(regexp = "^[A-Za-z0-9.-]+$", message = "主机只能包含字母、数字、点或连字符")
    private String host;

    @NotNull(message = "端口不能为空")
    @Min(value = 1, message = "端口不合法")
    @Max(value = 65535, message = "端口不合法")
    private Integer port = 3306;

    @NotBlank(message = "数据库名不能为空")
    @Size(max = 100, message = "数据库名不能超过100位")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "数据库名只能包含字母、数字、下划线或连字符")
    private String databaseName;

    @Pattern(regexp = "^[A-Za-z0-9_\\-]{1,20}$", message = "字符集格式不正确")
    private String charset = "utf8mb4";

    @NotBlank(message = "只读账号不能为空")
    @Size(max = 100, message = "只读账号不能超过100位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 200, message = "密码不能超过200位")
    private String password;
}
