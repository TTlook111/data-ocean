package com.dataocean.module.governance.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字段导航的搜索、审核状态与分页。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GovernanceColumnQuery extends PageQuery {

    /**
     * 字段名或来源注释的搜索关键词。
     */
    private String keyword;

    /**
     * 全部、待处理或已确认状态。
     */
    private String status;
}
