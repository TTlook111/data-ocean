package com.dataocean.module.iam.entity.query;

import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公共契约的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MemberQuery extends PersonQuery {

    /**
     * 查询是否包含当前部门的后代，层级不产生数据访问继承。
     */
    @NotNull
    private Boolean includeDescendants = true;
}
