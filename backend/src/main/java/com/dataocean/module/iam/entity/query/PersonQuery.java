package com.dataocean.module.iam.entity.query;

import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Pattern;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人员列表及部门、角色成员查询共用的人员筛选和排序条件，资源范围由业务服务限定。
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

    /**
     * 统一映射人员查询允许的排序字段，调用方 SQL 使用人员表别名 u。
     */
    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("updatedAt", "u.updated_at", "username", "u.username", "displayName", "u.display_name");
    }
}
