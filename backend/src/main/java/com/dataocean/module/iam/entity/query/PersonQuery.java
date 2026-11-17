package com.dataocean.module.iam.entity.query;

import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;

import java.util.List;

import jakarta.validation.constraints.Pattern;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公共契约的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PersonQuery extends PageQuery {

    /**
     * 人员当前显示姓名，不作为登录或授权依据。
     */
    private String displayName;

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    private String username;

    /**
     * 由生命周期、首次登录和待改密事实推导的展示状态。
     */
    @Pattern(regexp = "PENDING_FIRST_LOGIN|PASSWORD_CHANGE_REQUIRED|ENABLED|DISABLED|ARCHIVED")
    private String displayStatus;

    /**
     * 将姓名搜索文本拆分为只匹配该字段的安全查询条件。
     */
    public List<String> getDisplayNameTerms() {
        return TextSearch.terms(displayName);
    }

    /**
     * 将登录名搜索文本拆分为只匹配该字段的安全查询条件。
     */
    public List<String> getUsernameTerms() {
        return TextSearch.terms(username);
    }
}
