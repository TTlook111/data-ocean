package com.dataocean.module.iam.entity.query;

import com.dataocean.common.TextSearch;

import java.util.List;

import lombok.Data;

/**
 * 部门与成员的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
public class DepartmentTreeQuery {

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name = null;

    /**
     * 将角色名称搜索文本按规范化和字面转义规则生成查询条件。
     */
    public List<String> getNameTerms() {
        return TextSearch.terms(name);
    }

    /**
     * 正常、停用或归档的持久生命周期状态。
     */
    private String lifecycle = null;
}
