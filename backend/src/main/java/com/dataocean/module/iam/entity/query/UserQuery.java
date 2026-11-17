package com.dataocean.module.iam.entity.query;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人员资料的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PersonQuery {

    /**
     * 本次查询或操作涉及的稳定部门标识。
     */
    private String departmentId = null;

    /**
     * 查询是否包含当前部门的后代，层级不产生数据访问继承。
     */
    @NotNull
    private Boolean includeDescendants = true;

    /**
     * 定义外部排序字段到内部数据库列的白名单，不透传外部 SQL 片段。
     */
    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("updatedAt", "u.updated_at", "username", "u.username", "displayName", "u.display_name");
    }
}
