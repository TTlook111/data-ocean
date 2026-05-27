package com.dataocean.module.permission.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 行列级策略创建请求 DTO
 *
 * @author dataocean
 */
@Data
public class AccessPolicyCreateDTO {

    /** 数据源 ID */
    @NotNull(message = "数据源ID不能为空")
    private Long datasourceId;

    /** 授权主体类型: USER/ROLE/DEPARTMENT */
    @NotBlank(message = "主体类型不能为空")
    private String subjectType;

    /** 授权主体 ID */
    @NotNull(message = "主体ID不能为空")
    private Long subjectId;

    /** 表名 */
    @NotBlank(message = "表名不能为空")
    private String tableName;

    /** 列名（NULL 表示表级策略） */
    private String columnName;

    /** 访问类型: ALLOW/DENY/MASK */
    @NotBlank(message = "访问类型不能为空")
    private String accessType;

    /** 脱敏策略（accessType 为 MASK 时必填） */
    private String maskStrategy;

    /** 行级过滤表达式 */
    private String rowFilterExpression;
}
