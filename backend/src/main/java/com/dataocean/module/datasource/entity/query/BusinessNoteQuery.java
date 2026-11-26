package com.dataocean.module.datasource.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 资料列表限定数据源后按标题、提交人与状态分页。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class BusinessNoteQuery extends PageQuery {

    /**
     * 限定到当前数据源，可同时选择所有有权限的源。
     */
    private String sourceId;

    /**
     * 资料标题搜索条件。
     */
    private String keyword;

    /**
     * 提交人姓名或登录名搜索条件。
     */
    private String author;

    /**
     * 当前业务说明可用状态。
     */
    private String state;
}
