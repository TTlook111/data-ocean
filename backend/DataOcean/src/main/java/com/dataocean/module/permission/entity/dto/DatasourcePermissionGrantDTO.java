package com.dataocean.module.permission.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 数据源访问授权请求 DTO
 *
 * @author dataocean
 */
@Data
public class DatasourcePermissionGrantDTO {

    /** 数据源 ID */
    @NotNull(message = "数据源ID不能为空")
    private Long datasourceId;

    /** 授权主体类型: USER/ROLE/DEPARTMENT */
    @NotBlank(message = "主体类型不能为空")
    private String subjectType;

    /** 授权主体 ID */
    @NotNull(message = "主体ID不能为空")
    private Long subjectId;

    /** 是否允许查询 */
    private Boolean canQuery = true;

    /** 是否允许导出 */
    private Boolean canExport = false;

    /** 是否允许查看SQL */
    private Boolean canViewSql = true;

    private String accessEffect = "ALLOW";
}
