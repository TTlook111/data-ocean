package com.dataocean.module.iam.entity.query;

import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;

import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色与授予的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleQuery extends PageQuery {

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

    /**
     * 定义外部排序字段到内部数据库列的白名单，不透传外部 SQL 片段。
     */
    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("updatedAt", "r.updated_at", "name", "r.name_key");
    }
}
