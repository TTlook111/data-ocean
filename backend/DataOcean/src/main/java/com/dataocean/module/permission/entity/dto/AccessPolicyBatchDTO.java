package com.dataocean.module.permission.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量创建策略请求 DTO
 *
 * @author dataocean
 */
@Data
public class AccessPolicyBatchDTO {

    /** 数据源 ID */
    @NotNull(message = "数据源ID不能为空")
    private Long datasourceId;

    /** 授权主体类型 */
    @NotBlank(message = "主体类型不能为空")
    private String subjectType;

    /** 授权主体 ID */
    @NotNull(message = "主体ID不能为空")
    private Long subjectId;

    /** 表名 */
    @NotBlank(message = "表名不能为空")
    private String tableName;

    /** 策略列表 */
    @NotNull(message = "策略列表不能为空")
    @Valid
    private List<PolicyItem> policies;

    /**
     * 单条策略项
     */
    @Data
    public static class PolicyItem {
        private String columnName;
        @NotBlank(message = "访问类型不能为空")
        private String accessType;
        private String maskStrategy;
        private String rowFilterExpression;
    }
}
