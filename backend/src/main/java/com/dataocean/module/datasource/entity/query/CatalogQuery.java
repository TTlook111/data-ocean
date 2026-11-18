package com.dataocean.module.datasource.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 在当前源的结构目录中按名称或类型查找并分页。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CatalogQuery extends PageQuery {

    /**
     * 对象名或已存在业务解释的搜索词。
     */
    private String objectKeyword;

    /**
     * 按所需对象类别筛选。
     */
    private String objectType;

    /**
     * 当前目录中的关注状态。
     */
    private String catalogState;
}
