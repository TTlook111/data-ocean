package com.dataocean.module.iam.entity.query;

import com.dataocean.common.TextSearch;

import java.util.List;

import lombok.Data;

/**
 * 角色与授予的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
public class RoleOptionQuery {

    /**
     * 本次选项查询或系统功能的明确业务用途。
     */
    private String purpose = null;

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    private String userId = null;

    /**
     * 仅在该选项目的允许范围内匹配的搜索文本。
     */
    private String q = null;

    /**
     * 将选项关键词转换为本次用途范围内的字面匹配条件。
     */
    public List<String> getQTerms() {
        return TextSearch.terms(q);
    }
}
