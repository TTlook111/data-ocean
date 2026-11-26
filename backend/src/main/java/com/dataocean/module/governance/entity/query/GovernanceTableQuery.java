package com.dataocean.module.governance.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 治理工作台表级筛选及分页。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GovernanceTableQuery extends PageQuery {

    /**
     * 表名、业务名称或原注释的关键词。
     */
    private String keyword;

    /**
     * 只看有指定待处理项的表。
     */
    private String pendingKind;

    /**
     * 按有无来源待复核项筛选。
     */
    private Boolean hasPendingImpact;

    /**
     * 表说明和字段的当前治理进度。
     */
    private String progress;
}
