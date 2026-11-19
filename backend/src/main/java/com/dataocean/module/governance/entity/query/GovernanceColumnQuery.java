package com.dataocean.module.governance.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Getter;
import lombok.Setter;

/**
 * 字段导航的搜索、审核状态与分页。
 */
@Getter
@Setter
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
