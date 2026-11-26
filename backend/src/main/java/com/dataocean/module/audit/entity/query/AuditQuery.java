package com.dataocean.module.audit.entity.query;

import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;

import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 历史审计的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditQuery extends PageQuery {

    /**
     * 操作发生时的可信主体快照。
     */
    private String actor = null;

    /**
     * 将历史操作者搜索文本拆分为已字面转义的匹配条件。
     */
    public List<String> getActorTerms() {
        return TextSearch.terms(actor);
    }

    /**
     * 实际操作代码，用于权限核验和审计关联。
     */
    private String actionCode = null;

    /**
     * 本次命令已提交的安全目标引用。
     */
    private String target = null;

    /**
     * 将历史目标搜索文本转换为安全的名称或编号匹配条件。
     */
    public List<String> getTargetTerms() {
        return TextSearch.terms(target);
    }

    /**
     * 操作实际结果，明确区分成功、拒绝和失败。
     */
    private String outcome = null;

    /**
     * 以指定时区自然日解释的查询开始日期。
     */
    private String fromDate = null;

    /**
     * 以指定时区自然日解释的查询结束日期，包含当天。
     */
    private String toDate = null;

    /**
     * 自然日查询所使用的 IANA 时区标识。
     */
    private String timeZone = "Asia/Shanghai";

    /**
     * 定义外部排序字段到内部数据库列的白名单，不透传外部 SQL 片段。
     */
    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("occurredAt", "a.occurred_at");
    }
}
